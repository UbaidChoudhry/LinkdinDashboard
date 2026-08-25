package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.store.JobListingRepository;

/** One row of {@code GET /api/reports/company-volume}. */
public record CompanyVolumeResponse(String company, int postings, int titles, int locations) {

    public static CompanyVolumeResponse from(JobListingRepository.CompanyVolume volume) {
        return new CompanyVolumeResponse(volume.company(), volume.postings(), volume.titles(), volume.locations());
    }
}
