package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.domain.Resume;
import com.ubaid.jobdash.store.ResumeRepository;

import java.time.Instant;

/**
 * A resume as exposed to the UI. Deliberately leaves out {@code contentText} - resumes are
 * large and the list/creation responses don't need the body; see {@code GET /api/resumes/{id}/text}.
 */
public record ResumeResponse(
        long id,
        String name,
        String originalFilename,
        int charCount,
        boolean isDefault,
        Instant uploadedAt
) {
    public static ResumeResponse of(Resume r) {
        return new ResumeResponse(r.id(), r.name(), r.originalFilename(), r.charCount(), r.isDefault(), r.uploadedAt());
    }

    public static ResumeResponse of(ResumeRepository.ResumeSummary s) {
        return new ResumeResponse(s.id(), s.name(), s.originalFilename(), s.charCount(), s.isDefault(), s.uploadedAt());
    }
}
