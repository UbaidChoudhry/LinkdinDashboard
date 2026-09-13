package com.ubaid.jobdash.web;

import com.ubaid.jobdash.ai.AiProperties;
import com.ubaid.jobdash.apply.ApplyOrchestrator;
import com.ubaid.jobdash.apply.ApplyProperties;
import com.ubaid.jobdash.domain.ApplicantProfile;
import com.ubaid.jobdash.domain.ApplyBatch;
import com.ubaid.jobdash.domain.JobApplication;
import com.ubaid.jobdash.domain.JobListing;
import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.store.ApplicantProfileRepository;
import com.ubaid.jobdash.store.ApplicationRepository;
import com.ubaid.jobdash.store.ApplyBatchRepository;
import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.store.ResumeRepository;
import com.ubaid.jobdash.web.dto.ApplicantProfileRequest;
import com.ubaid.jobdash.web.dto.ApplicantProfileResponse;
import com.ubaid.jobdash.web.dto.ApplyBatchResponse;
import com.ubaid.jobdash.web.dto.ApplyRequest;
import com.ubaid.jobdash.web.dto.JobApplicationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST surface for the applicant profile and "Apply with Claude" batches: {@code /api/profile}
 * and {@code /api/applications}. All the actual browser-driving work lives in
 * {@link ApplyOrchestrator}; this controller only validates the request, resolves defaults (the
 * same {@code resolveResumeId} shape as {@link MatchController}), and translates HTTP.
 */
@RestController
public class ApplicationController {

    private final ApplicantProfileRepository applicantProfileRepository;
    private final ApplyBatchRepository applyBatchRepository;
    private final ApplicationRepository applicationRepository;
    private final ResumeRepository resumeRepository;
    private final JobListingRepository jobListingRepository;
    private final ApplyOrchestrator applyOrchestrator;
    private final ApplyProperties applyProperties;
    private final AiProperties aiProperties;
    private final Clock clock;

    public ApplicationController(ApplicantProfileRepository applicantProfileRepository,
                                  ApplyBatchRepository applyBatchRepository,
                                  ApplicationRepository applicationRepository,
                                  ResumeRepository resumeRepository,
                                  JobListingRepository jobListingRepository,
                                  ApplyOrchestrator applyOrchestrator,
                                  ApplyProperties applyProperties,
                                  AiProperties aiProperties,
                                  Clock clock) {
        this.applicantProfileRepository = applicantProfileRepository;
        this.applyBatchRepository = applyBatchRepository;
        this.applicationRepository = applicationRepository;
        this.resumeRepository = resumeRepository;
        this.jobListingRepository = jobListingRepository;
        this.applyOrchestrator = applyOrchestrator;
        this.applyProperties = applyProperties;
        this.aiProperties = aiProperties;
        this.clock = clock;
    }

    // ---- applicant profile -------------------------------------------------------------------

    @GetMapping("/api/profile")
    public ResponseEntity<ApplicantProfileResponse> getProfile() {
        return applicantProfileRepository.find()
                .map(p -> ResponseEntity.ok(ApplicantProfileResponse.of(p)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PutMapping("/api/profile")
    public ApplicantProfileResponse saveProfile(@RequestBody(required = false) ApplicantProfileRequest body) {
        if (body == null || body.fullName() == null || body.fullName().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "fullName is required.");
        }
        if (body.email() == null || body.email().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "email is required.");
        }
        ApplicantProfile profile = new ApplicantProfile(
                body.fullName(),
                body.email(),
                nullToEmpty(body.phone()),
                nullToEmpty(body.location()),
                nullToEmpty(body.linkedinUrl()),
                nullToEmpty(body.portfolioUrl()),
                nullToEmpty(body.workAuthorization()),
                body.requiresSponsorship() != null && body.requiresSponsorship(),
                nullToEmpty(body.salaryExpectation()),
                nullToEmpty(body.extraAnswers()),
                clock.instant());
        applicantProfileRepository.save(profile);
        return ApplicantProfileResponse.of(profile);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ---- apply batches ------------------------------------------------------------------------

    @PostMapping("/api/applications")
    public ResponseEntity<Map<String, Long>> startApplications(@RequestBody(required = false) ApplyRequest body) {
        ApplyRequest request = body == null ? new ApplyRequest(null, null, null) : body;

        ApplicantProfile profile = applicantProfileRepository.find()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "Fill in your applicant profile in the Resumes tab first."));

        long resumeId = resolveResumeId(request.resumeId());

        List<Long> jobIds = request.jobIds();
        if (jobIds == null || jobIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "jobIds must be a non-empty list.");
        }
        for (Long jobId : jobIds) {
            if (jobListingRepository.findById(jobId).isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "No job found with id " + jobId + ".");
            }
        }

        if (applyOrchestrator.inFlightBatchId().isPresent() || applyBatchRepository.findInFlight().isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "An apply batch is already in progress.");
        }

        if (!applyProperties.enabled() || !cliAvailable(aiProperties.cliPath())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Apply with Claude is not available: the claude CLI is not enabled or not on the path.");
        }

        boolean submit = request.submit() != null && request.submit();
        long batchId = applyOrchestrator.start(jobIds, resumeId, submit);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("batchId", batchId));
    }

    @GetMapping("/api/applications/current")
    public ResponseEntity<ApplyBatchResponse> current() {
        Optional<Long> inFlightId = applyOrchestrator.inFlightBatchId();
        Optional<ApplyBatch> batch = inFlightId.isPresent()
                ? applyBatchRepository.findById(inFlightId.get())
                : applyBatchRepository.findInFlight();
        return batch.map(b -> ResponseEntity.ok(toResponse(b)))
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/api/applications/{id}")
    public ApplyBatchResponse getBatch(@PathVariable long id) {
        ApplyBatch batch = applyBatchRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No apply batch found with id " + id + "."));
        return toResponse(batch);
    }

    @PostMapping("/api/applications/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable long id) {
        if (applyBatchRepository.findById(id).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No apply batch found with id " + id + ".");
        }
        applyOrchestrator.cancel(id); // no-op (false) if this batch isn't the one running - still 202.
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping("/api/applications")
    public List<ApplyBatchResponse> recent(@RequestParam(name = "limit", defaultValue = "20") int limit) {
        return applyBatchRepository.findRecent(limit).stream().map(this::toResponse).toList();
    }

    private ApplyBatchResponse toResponse(ApplyBatch batch) {
        List<JobApplication> applications = applicationRepository.findByBatch(batch.id());
        Map<Long, JobListing> jobs = jobListingRepository.findByIds(
                applications.stream().map(JobApplication::jobId).toList());
        List<JobApplicationResponse> jobResponses = applications.stream()
                .map(a -> JobApplicationResponse.of(a, jobs.get(a.jobId())))
                .toList();
        return ApplyBatchResponse.of(batch, jobResponses);
    }

    private long resolveResumeId(Long requested) {
        if (requested != null) {
            return requested;
        }
        return resumeRepository.findDefault()
                .map(Resume::id)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "No default resume set. Upload a resume first."));
    }

    /**
     * Whether the configured claude CLI can actually be invoked: an absolute/relative path
     * (contains a '/') is checked directly, otherwise every {@code PATH} entry is searched for an
     * executable of that name - mirrors what a shell would do to resolve a bare command name.
     * Package-private so a test can call it directly rather than needing the real binary on PATH.
     */
    static boolean cliAvailable(String cliPath) {
        if (cliPath == null || cliPath.isBlank()) {
            return false;
        }
        if (cliPath.contains("/")) {
            return Files.isExecutable(Path.of(cliPath));
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return false;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, cliPath);
            if (Files.isExecutable(candidate)) {
                return true;
            }
        }
        return false;
    }
}
