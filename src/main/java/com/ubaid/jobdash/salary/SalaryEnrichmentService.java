package com.ubaid.jobdash.salary;

import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.SalaryEstimate;
import com.ubaid.jobdash.store.SalaryEstimateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Fills in the salary band for the jobs of one sweep run: for every passing row that has no
 * salary yet, it consults the {@code salary_estimate} cache and, on a miss, runs the source
 * cascade (LCA, then Adzuna, then h1bapi) and writes both the cache row and the job's band.
 * <p>
 * Best-effort by design: it is called inline from the sweep loop and <b>never throws</b> — a
 * failing row is logged and skipped, a disabled feature is a no-op.
 */
@Service
public class SalaryEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(SalaryEnrichmentService.class);

    /** Cascade order, most-trusted first. Sources not named here sort last, keeping bean order out of it. */
    private static final List<String> CASCADE_ORDER = List.of("lca", "adzuna", "h1bapi");

    private final List<SalarySource> sources;
    private final SalaryEstimateRepository salaryEstimateRepository;
    private final JobListingRepository jobListingRepository;
    private final SalaryProperties properties;
    private final java.time.Clock clock;

    public SalaryEnrichmentService(List<SalarySource> sources,
                                   SalaryEstimateRepository salaryEstimateRepository,
                                   JobListingRepository jobListingRepository,
                                   SalaryProperties properties,
                                   java.time.Clock clock) {
        this.sources = sources.stream()
                .sorted(Comparator.comparingInt(s -> {
                    int i = CASCADE_ORDER.indexOf(s.name());
                    return i < 0 ? Integer.MAX_VALUE : i;
                }))
                .toList();
        this.salaryEstimateRepository = salaryEstimateRepository;
        this.jobListingRepository = jobListingRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Enriches every passing, salary-less job of run {@code runId}. Checks
     * {@code cancelled} at the top of every row and stops promptly when it flips true.
     *
     * @param location the run's search location, forwarded to the sources for state/metro
     *                  resolution (blank when the run is sharded)
     */
    public void enrichRun(long runId, String location, BooleanSupplier cancelled) {
        if (!properties.enabled()) {
            return;
        }
        Instant now = clock.instant();
        List<JobListing> rows = jobListingRepository.findPassingWithoutSalaryByRun(runId);

        int seen = 0;
        int enriched = 0;
        int cachedHits = 0;
        int missed = 0;

        for (JobListing job : rows) {
            if (cancelled.getAsBoolean()) {
                break;
            }
            seen++;
            try {
                String companyKey = CompanyKey.of(job.company());
                String titleKey = TitleKey.of(job.title());
                if (companyKey.isBlank() || titleKey.isBlank()) {
                    continue;
                }

                Optional<SalaryEstimate> cached = salaryEstimateRepository.find(companyKey, titleKey);
                if (cached.isPresent()
                        && salaryEstimateRepository.isFresh(cached.get(), now, properties.cacheTtl())) {
                    SalaryEstimate c = cached.get();
                    if (!"none".equals(c.source())) {
                        jobListingRepository.applySalary(job.jobId(), c.salaryMin(), c.salaryMax(), c.source());
                        cachedHits++;
                    }
                    continue;
                }

                SalaryLookup q = new SalaryLookup(job.company(), job.title(), location, companyKey, titleKey);
                SalaryResult win = runCascade(q, now);

                if (win != null) {
                    salaryEstimateRepository.upsert(new SalaryEstimate(
                            companyKey, titleKey, win.salaryMin(), win.salaryMax(),
                            win.currency() == null ? "USD" : win.currency(), win.source(),
                            win.dataDate() == null ? null : win.dataDate().toString(),
                            win.sampleCount(), now));
                    jobListingRepository.applySalary(job.jobId(), win.salaryMin(), win.salaryMax(), win.source());
                    enriched++;
                } else {
                    salaryEstimateRepository.upsert(new SalaryEstimate(
                            companyKey, titleKey, null, null, "USD", "none", null, null, now));
                    missed++;
                }
            } catch (Exception e) {
                log.warn("salary enrichment failed for job {}: {}", job.jobId(), e.toString());
            }
        }

        log.info("salary enrichment for run {}: seen={} enriched={} cacheHit={} missed={}",
                runId, seen, enriched, cachedHits, missed);
    }

    /** First source to return a fresh-enough, non-empty result wins; stale results are skipped. */
    private SalaryResult runCascade(SalaryLookup q, Instant now) {
        Instant floor = now.minus(properties.maxDataAge());
        for (SalarySource source : sources) {
            Optional<SalaryResult> r;
            try {
                r = source.lookup(q);
            } catch (RuntimeException e) {
                log.warn("salary source {} threw: {}", source.name(), e.toString());
                continue;
            }
            if (r.isEmpty()) {
                continue;
            }
            SalaryResult res = r.get();
            if (isTooOld(res.dataDate(), floor)) {
                log.debug("dropping {} result dated {} (older than max-data-age)", res.source(), res.dataDate());
                continue;
            }
            return res;
        }
        return null;
    }

    private static boolean isTooOld(LocalDate dataDate, Instant floor) {
        if (dataDate == null) {
            return false;
        }
        return dataDate.atStartOfDay(ZoneOffset.UTC).toInstant().isBefore(floor);
    }
}
