package com.ubaid.jobdash.web;

import com.ubaid.jobdash.ai.ResumeMatchService;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.SweepRun;
import com.ubaid.jobdash.source.location.RemoteClassifier;
import com.ubaid.jobdash.store.AiMatchRepository;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.ResumeRepository;
import com.ubaid.jobdash.store.SweepRunRepository;
import com.ubaid.jobdash.web.dto.MatchResponse;
import com.ubaid.jobdash.web.dto.MatchScanRequest;
import com.ubaid.jobdash.web.dto.MatchScanResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface for AI resume-match scoring: kicking off a scan against a run (or an explicit
 * job list), and reading back the cached verdicts. All the CLI batching/correlation work lives
 * in {@link ResumeMatchService}; this controller only resolves defaults and translates HTTP.
 */
@RestController
public class MatchController {

    private static final Logger log = LoggerFactory.getLogger(MatchController.class);

    private final ResumeMatchService resumeMatchService;
    private final ResumeRepository resumeRepository;
    private final SweepRunRepository sweepRunRepository;
    private final JobListingRepository jobListingRepository;
    private final AiMatchRepository aiMatchRepository;
    private final RemoteClassifier remoteClassifier;

    public MatchController(ResumeMatchService resumeMatchService, ResumeRepository resumeRepository,
                            SweepRunRepository sweepRunRepository, JobListingRepository jobListingRepository,
                            AiMatchRepository aiMatchRepository, RemoteClassifier remoteClassifier) {
        this.resumeMatchService = resumeMatchService;
        this.resumeRepository = resumeRepository;
        this.sweepRunRepository = sweepRunRepository;
        this.jobListingRepository = jobListingRepository;
        this.aiMatchRepository = aiMatchRepository;
        this.remoteClassifier = remoteClassifier;
    }

    @PostMapping("/api/matches/scan")
    public MatchScanResponse scan(@RequestBody(required = false) MatchScanRequest body) {
        MatchScanRequest request = body == null ? new MatchScanRequest(null, null, null) : body;
        long resumeId = resolveResumeId(request.resumeId());

        // Re-scan is also how rows collected before the Remote column existed get their answer
        // without waiting for the next run. Cheap when there is nothing left to decide.
        RemoteClassifier.ClassifyResult remote = remoteClassifier.classifyPending(() -> false);

        ResumeMatchService.ScanResult result;
        if (request.jobIds() != null && !request.jobIds().isEmpty()) {
            result = resumeMatchService.rescan(request.jobIds(), resumeId, () -> false);
        } else {
            result = resumeMatchService.scan(resolveRunId(request.runId()), resumeId, () -> false);
        }
        return MatchScanResponse.of(result, remote);
    }

    @GetMapping("/api/matches")
    public List<MatchResponse> matches(@RequestParam("runId") long runId,
                                        @RequestParam(name = "resumeId", required = false) Long resumeId) {
        long resolvedResumeId = resolveResumeId(resumeId);
        List<Long> jobIds = jobListingRepository.findByRunAndVerdictAndUserStatus(runId, null, null).stream()
                .map(JobListing::jobId)
                .toList();
        return aiMatchRepository.findByJobIds(jobIds, resolvedResumeId).values().stream()
                .map(MatchResponse::of)
                .toList();
    }

    @DeleteMapping("/api/matches")
    public void clear(@RequestParam("resumeId") long resumeId) {
        aiMatchRepository.deleteByResume(resumeId);
    }

    private long resolveResumeId(Long requested) {
        if (requested != null) {
            return requested;
        }
        return resumeRepository.findDefault()
                .map(r -> r.id())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No default resume set. Upload a resume first."));
    }

    private long resolveRunId(Long requested) {
        if (requested != null) {
            return requested;
        }
        List<SweepRun> recent = sweepRunRepository.findRecent(1);
        if (recent.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No sweep run found to scan. Start a run first.");
        }
        return recent.get(0).id();
    }
}
