package com.ubaid.jobdash.web;

import com.ubaid.jobdash.domain.AiMatch;
import com.ubaid.jobdash.domain.FilterVerdict;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.domain.SweepRun;
import com.ubaid.jobdash.domain.UserStatus;
import com.ubaid.jobdash.filter.FilterEngine;
import com.ubaid.jobdash.store.AiMatchRepository;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.ResumeRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import com.ubaid.jobdash.web.dto.JobResponse;
import com.ubaid.jobdash.web.dto.JobStatusUpdateRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REST surface over {@code job_listing}: the tabbed browse list and per-job status triage.
 * See {@link JobSortOrder} for the (deliberately placeholder) sort applied to every tab.
 */
@RestController
public class JobController {

    private static final List<String> VALID_TABS = List.of("search", "applied", "not_interested");

    private final JobListingRepository jobListingRepository;
    private final SweepRunRepository sweepRunRepository;
    private final FilterEngine filterEngine;
    private final ResumeRepository resumeRepository;
    private final AiMatchRepository aiMatchRepository;
    private final Clock clock;

    public JobController(JobListingRepository jobListingRepository, SweepRunRepository sweepRunRepository,
                          FilterEngine filterEngine, ResumeRepository resumeRepository,
                          AiMatchRepository aiMatchRepository, Clock clock) {
        this.jobListingRepository = jobListingRepository;
        this.sweepRunRepository = sweepRunRepository;
        this.filterEngine = filterEngine;
        this.resumeRepository = resumeRepository;
        this.aiMatchRepository = aiMatchRepository;
        this.clock = clock;
    }

    @GetMapping("/api/jobs")
    public List<JobResponse> jobs(
            @RequestParam(name = "tab", defaultValue = "search") String tab,
            @RequestParam(name = "includePreviousRuns", defaultValue = "false") boolean includePreviousRuns,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "resumeId", required = false) Long resumeId) {

        String normalizedTab = tab.toLowerCase(Locale.ROOT);
        if (!VALID_TABS.contains(normalizedTab)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Unknown tab '" + tab + "'. Valid values are: " + String.join(", ", VALID_TABS) + ".");
        }

        List<JobListing> jobs = switch (normalizedTab) {
            case "search" -> searchTab(includePreviousRuns);
            case "applied" -> jobListingRepository.findByUserStatus(UserStatus.APPLIED);
            case "not_interested" -> jobListingRepository.findByUserStatus(UserStatus.NOT_INTERESTED);
            default -> throw new IllegalStateException("unreachable: " + normalizedTab);
        };

        List<JobListing> sorted = jobs.stream().sorted(JobSortOrder.DEFAULT).toList();
        // `sort` is currently accepted but not otherwise interpreted - the two-bucket order
        // above is the only sort defined so far; see JobSortOrder's javadoc.

        Long effectiveResumeId = resumeId != null ? resumeId
                : resumeRepository.findDefault().map(Resume::id).orElse(null);
        // One batched lookup for the whole page, never a query per row.
        Map<Long, AiMatch> aiMatches = effectiveResumeId == null ? Map.of()
                : aiMatchRepository.findByJobIds(sorted.stream().map(JobListing::jobId).toList(), effectiveResumeId);

        return sorted.stream().map(job -> JobResponse.from(job, aiMatches.get(job.jobId()))).toList();
    }

    private List<JobListing> searchTab(boolean includePreviousRuns) {
        if (includePreviousRuns) {
            return jobListingRepository.findByVerdictAndNullUserStatus(FilterVerdict.PASS);
        }
        List<SweepRun> recent = sweepRunRepository.findRecent(1);
        if (recent.isEmpty()) {
            return List.of();
        }
        long currentRunId = recent.get(0).id();
        return jobListingRepository.findByRunAndVerdictAndNullUserStatus(currentRunId, FilterVerdict.PASS);
    }

    @PostMapping("/api/jobs/{id}/status")
    public JobResponse setStatus(@PathVariable long id, @RequestBody(required = false) JobStatusUpdateRequest body) {
        UserStatus status = parseStatus(body == null ? null : body.status());

        int updated = jobListingRepository.setUserStatus(id, status, clock.instant());
        if (updated == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No job found with id " + id + ".");
        }
        return jobListingRepository.findById(id)
                .map(JobResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No job found with id " + id + "."));
    }

    private static UserStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UserStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "status must be 'applied', 'not_interested', or null - got '" + raw + "'.");
        }
    }
}
