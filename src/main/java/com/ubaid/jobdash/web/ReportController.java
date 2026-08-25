package com.ubaid.jobdash.web;

import com.ubaid.jobdash.store.JobListingRepository;
import com.ubaid.jobdash.web.dto.CompanyVolumeResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Read-only reporting endpoints. {@link #companyVolume} is a structural relay-detection signal
 * derived from card data alone (posting volume, title/location diversity per company) — it never
 * writes to {@code company_blocklist}; a human decides what to do with the numbers.
 */
@RestController
public class ReportController {

    private static final int DEFAULT_WINDOW_DAYS = 7;
    private static final int DEFAULT_THRESHOLD = 40;

    private final JobListingRepository jobListingRepository;
    private final Clock clock;

    public ReportController(JobListingRepository jobListingRepository, Clock clock) {
        this.jobListingRepository = jobListingRepository;
        this.clock = clock;
    }

    @GetMapping("/api/reports/company-volume")
    public List<CompanyVolumeResponse> companyVolume(
            @RequestParam(name = "days", defaultValue = "" + DEFAULT_WINDOW_DAYS) int days,
            @RequestParam(name = "threshold", defaultValue = "" + DEFAULT_THRESHOLD) int threshold) {
        var since = clock.instant().minus(Duration.ofDays(days));
        return jobListingRepository.companyVolumeSince(since, threshold).stream()
                .map(CompanyVolumeResponse::from)
                .toList();
    }
}
