package com.ubaid.jobdash.sweep;

import com.ubaid.jobdash.domain.AtsCompany;
import com.ubaid.jobdash.filter.FilterEngine;
import com.ubaid.jobdash.salary.SalaryEnrichmentService;
import com.ubaid.jobdash.source.JobSource;
import com.ubaid.jobdash.source.SourceFetchResult;
import com.ubaid.jobdash.source.SourceQuery;
import com.ubaid.jobdash.source.SourcedJob;
import com.ubaid.jobdash.source.ats.AtsProperties;
import com.ubaid.jobdash.source.workday.WorkdaySiteResolver;
import com.ubaid.jobdash.store.AtsCompanyRepository;
import com.ubaid.jobdash.store.JobCardInsert;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * The ATS equivalent of {@link SweepService}: visits every enabled, non-dead company registered
 * for the run's selected ATS sources (Greenhouse/Lever/Workday), fetching and storing jobs one
 * company at a time. Unlike a LinkedIn sweep, a single unreachable company must never abort the
 * whole run - see the per-company {@code TransportError}/{@code DeadSlug} handling below.
 * <p>
 * Publishes progress into the same {@link RunProgressRegistry} {@link SweepService} uses, so
 * {@code RunController}'s SSE endpoint works identically for either kind of run. Does not itself
 * write the run's terminal status to {@code sweep_run} - {@link RunOrchestrator} does that once,
 * after this method returns and (for a successful collection) after the AI resume scan.
 */
@Service
public class AtsSweepService {

    private final Map<String, JobSource> jobSourcesByName;
    private final AtsCompanyRepository atsCompanyRepository;
    private final WorkdaySiteResolver workdaySiteResolver;
    private final JobListingRepository jobListingRepository;
    private final FilterEngine filterEngine;
    private final SalaryEnrichmentService salaryEnrichmentService;
    private final SweepRunRepository sweepRunRepository;
    private final RunProgressRegistry progressRegistry;
    private final AtsProperties atsProperties;
    private final Clock clock;

    public AtsSweepService(List<JobSource> jobSources, AtsCompanyRepository atsCompanyRepository,
                            WorkdaySiteResolver workdaySiteResolver, JobListingRepository jobListingRepository,
                            FilterEngine filterEngine, SalaryEnrichmentService salaryEnrichmentService,
                            SweepRunRepository sweepRunRepository, RunProgressRegistry progressRegistry,
                            AtsProperties atsProperties, Clock clock) {
        this.jobSourcesByName = jobSources.stream().collect(Collectors.toMap(JobSource::name, s -> s));
        this.atsCompanyRepository = atsCompanyRepository;
        this.workdaySiteResolver = workdaySiteResolver;
        this.jobListingRepository = jobListingRepository;
        this.filterEngine = filterEngine;
        this.salaryEnrichmentService = salaryEnrichmentService;
        this.sweepRunRepository = sweepRunRepository;
        this.progressRegistry = progressRegistry;
        this.atsProperties = atsProperties;
        this.clock = clock;
    }

    /**
     * Runs the ATS collection pass synchronously on the calling thread, visiting companies in
     * order and checking {@code cancelled} between each. Returns the run's terminal status
     * ({@code "ok"}, {@code "no_sources"}, {@code "budget_exhausted"} or {@code "cancelled"}) -
     * the caller ({@link RunOrchestrator}) is responsible for persisting it.
     */
    public String run(long runId, AtsRunRequest request, BooleanSupplier cancelled) {
        String sources = String.join(",", request.atsNames());
        List<AtsCompany> companies = atsCompanyRepository.findForRun(request.atsNames(), atsProperties.maxCompaniesPerRun());

        // An empty result here means "you haven't enabled any companies for these sources" - a
        // configuration problem the user must be told about, not a successful run that simply
        // found nothing.
        if (companies.isEmpty()) {
            publish(runId, "no_sources", sources, 0, 0, new Counters());
            return "no_sources";
        }

        Counters acc = new Counters();
        int total = companies.size();

        for (AtsCompany company : companies) {
            if (cancelled.getAsBoolean()) {
                publish(runId, "cancelled", sources, acc.companiesDone, total, acc);
                return "cancelled";
            }

            String site = company.site();
            if ("workday".equals(company.ats()) && (site == null || site.isBlank())) {
                Optional<String> resolved = workdaySiteResolver.resolve(company.host());
                if (resolved.isPresent()) {
                    site = resolved.get();
                    // Cache it so this company is resolved via robots.txt at most once, ever.
                    atsCompanyRepository.upsertSite(company.id(), site);
                } else {
                    // Could not be resolved - treat exactly like a dead slug: it counts toward
                    // the retirement threshold and the run moves on to the next company.
                    atsCompanyRepository.recordFailure(company.id(), clock.instant(), atsProperties.deadSlugThreshold());
                    acc.companiesDone++;
                    publish(runId, "running", sources, acc.companiesDone, total, acc);
                    continue;
                }
            }

            JobSource jobSource = jobSourcesByName.get(company.ats());
            if (jobSource == null) {
                // No JobSource registered for this ats name - can't happen with today's catalog
                // (findForRun only returns rows whose ats matches the selected sources, and every
                // selectable ats has a JobSource bean), but fail this one company, not the run.
                atsCompanyRepository.recordFailure(company.id(), clock.instant(), atsProperties.deadSlugThreshold());
                acc.companiesDone++;
                publish(runId, "running", sources, acc.companiesDone, total, acc);
                continue;
            }

            SourceQuery query = new SourceQuery(request.keywords(), request.location(), request.hours(),
                    company.slug(), company.company(), company.host(), site);
            SourceFetchResult result = jobSource.fetch(query);

            // Exhaustive over the sealed SourceFetchResult - the compiler enforces that every
            // permitted subtype is handled, so a new outcome variant can't silently fall through.
            switch (result) {
                case SourceFetchResult.Ok ok -> {
                    acc.requestsMade += ok.requestsMade();
                    acc.cardsSeen += ok.jobs().size();
                    if (!ok.jobs().isEmpty()) {
                        List<JobCardInsert> inserts = ok.jobs().stream().map(AtsSweepService::toInsert).toList();
                        int newCount = jobListingRepository.upsertAll(inserts, runId, clock.instant());
                        acc.jobsNew += newCount;
                        // Same post-upsert sequence SweepService performs, and for the same
                        // reasons (HANDOFF.md §3: filtering belongs to ingestion, not to a read).
                        filterEngine.evaluateNewRows();
                        salaryEnrichmentService.enrichRun(runId, request.location(), cancelled);
                    }
                    atsCompanyRepository.recordSuccess(company.id(), clock.instant(), ok.jobs().size());
                }
                case SourceFetchResult.DeadSlug deadSlug -> {
                    // Automatic retirement: after ats.dead-slug-threshold consecutive failures
                    // this row goes status='dead', enabled=0 and findForRun never returns it again.
                    atsCompanyRepository.recordFailure(company.id(), clock.instant(), atsProperties.deadSlugThreshold());
                }
                case SourceFetchResult.RateLimited rateLimited -> {
                    // The source's daily cap or pacing gate refused every further request - this
                    // is a run-level terminal state, unlike a single dead/unreachable company.
                    acc.companiesDone++;
                    publish(runId, "budget_exhausted", sources, acc.companiesDone, total, acc);
                    return "budget_exhausted";
                }
                case SourceFetchResult.TransportError transportError -> {
                    // One unreachable company must never abort a 150-company run: count it,
                    // record the failure against that company, and continue.
                    acc.requestsMade++;
                    atsCompanyRepository.recordFailure(company.id(), clock.instant(), atsProperties.deadSlugThreshold());
                }
            }

            acc.companiesDone++;
            publish(runId, "running", sources, acc.companiesDone, total, acc);
        }

        // A TransportError on every single company still ends "ok" - the per-company failures
        // are recorded on the ats_company rows; only RateLimited and cancellation stop the run.
        return "ok";
    }

    private void publish(long runId, String status, String sources, int companiesDone, int companiesTotal, Counters acc) {
        sweepRunRepository.updateAtsProgress(runId, companiesDone, companiesTotal, acc.requestsMade, acc.cardsSeen, acc.jobsNew);
        progressRegistry.publish(runId, new SweepProgress(runId, status, null, 0, acc.requestsMade,
                acc.cardsSeen, acc.jobsNew, false, companiesDone, companiesTotal, sources));
    }

    private static JobCardInsert toInsert(SourcedJob job) {
        return new JobCardInsert(job.source(), job.sourceJobId(), job.title(), job.company(), job.location(),
                job.postedAt(), job.jobUrl(), job.companyUrl(), job.description());
    }

    /** Mutable per-run counters, accumulated across companies. */
    private static final class Counters {
        int requestsMade;
        int cardsSeen;
        int jobsNew;
        int companiesDone;
    }
}
