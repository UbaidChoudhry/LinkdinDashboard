package com.ubaid.jobdash.domain;

/**
 * User-applied triage status on a {@link JobListing}. Stored as lowercase text in
 * {@code job_listing.user_status}.
 */
public enum UserStatus {
    APPLIED,
    NOT_INTERESTED;

    public String toDb() {
        return name().toLowerCase();
    }

    public static UserStatus fromDb(String text) {
        return text == null ? null : UserStatus.valueOf(text.toUpperCase());
    }
}
