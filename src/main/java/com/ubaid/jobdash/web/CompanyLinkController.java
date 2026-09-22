package com.ubaid.jobdash.web;

import com.ubaid.jobdash.ai.AiProperties;
import com.ubaid.jobdash.apply.CompanyLinkFinder;
import com.ubaid.jobdash.apply.CompanyLinkProperties;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.domain.SweepRun;
import com.ubaid.jobdash.store.ResumeRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.OptionalInt;

/**
 * The Results tab's Find company links button: starts a {@link CompanyLinkFinder} search over the
 * latest run's recommended LinkedIn jobs that have no apply link yet (judged against the default
 * resume, like the Results tab's buckets), and reports its progress. The same search also runs by
 * itself as the last phase of every run; only one runs at a time.
 */
@RestController
public class CompanyLinkController {

    private final CompanyLinkFinder finder;
    private final CompanyLinkProperties properties;
    private final AiProperties aiProperties;
    private final SweepRunRepository sweepRunRepository;
    private final ResumeRepository resumeRepository;

    public CompanyLinkController(CompanyLinkFinder finder, CompanyLinkProperties properties, AiProperties aiProperties,
                                 SweepRunRepository sweepRunRepository, ResumeRepository resumeRepository) {
        this.finder = finder;
        this.properties = properties;
        this.aiProperties = aiProperties;
        this.sweepRunRepository = sweepRunRepository;
        this.resumeRepository = resumeRepository;
    }

    @PostMapping("/api/company-links/search")
    public ResponseEntity<Map<String, Integer>> start() {
        if (!properties.enabled() || !ApplicationController.cliAvailable(aiProperties.cliPath())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Finding company links is not available: apply.company-links is off or the claude CLI is not on the path.");
        }
        long runId = sweepRunRepository.findRecent(1).stream().findFirst().map(SweepRun::id)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "No run yet - start one in the Search tab."));
        long resumeId = resumeRepository.findDefault().map(Resume::id)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No default resume set - the search looks for recommended jobs, which need one."));
        OptionalInt total = finder.startInBackground(runId, resumeId);
        if (total.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "A company-link search is already running.");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("total", total.getAsInt()));
    }

    /** The running search, or the last one to finish; 204 before the first. */
    @GetMapping("/api/company-links/search")
    public ResponseEntity<CompanyLinkFinder.Progress> progress() {
        return finder.progress().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/api/company-links/search/cancel")
    public ResponseEntity<Void> cancel() {
        finder.cancel();
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
