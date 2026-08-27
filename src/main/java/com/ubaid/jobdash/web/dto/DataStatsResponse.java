package com.ubaid.jobdash.web.dto;

import com.ubaid.jobdash.store.DataRepository;

import java.time.Instant;

/**
 * @param databaseSizeBytes  combined size of the SQLite main file plus its WAL/SHM side files.
 * @param requestLogEntries  total audit rows ever logged - NOT affected by clearing job data,
 *                           since it backs the rate limiter's rolling daily budget.
 * @param requestsLast24h    requests counted toward the currently enforced rolling budget.
 */
public record DataStatsResponse(
        long databaseSizeBytes,
        long totalJobs,
        long jobsPassed,
        long jobsRejected,
        long jobsUnevaluated,
        long jobsApplied,
        long jobsNotInterested,
        long jobsUntriaged,
        long totalRuns,
        long requestLogEntries,
        long requestsLast24h,
        long excludeWordCount,
        long blockedCompanyCount,
        Instant oldestFirstSeenAt,
        Instant newestLastSeenAt
) {
    public static DataStatsResponse of(long databaseSizeBytes, DataRepository.JobStats jobStats, long totalRuns,
                                        long requestLogEntries, long requestsLast24h, long excludeWordCount,
                                        long blockedCompanyCount) {
        return new DataStatsResponse(
                databaseSizeBytes,
                jobStats.total(), jobStats.passCount(), jobStats.rejectCount(), jobStats.unevaluatedCount(),
                jobStats.appliedCount(), jobStats.notInterestedCount(), jobStats.untriagedCount(),
                totalRuns, requestLogEntries, requestsLast24h,
                excludeWordCount, blockedCompanyCount,
                jobStats.oldestFirstSeenAt(), jobStats.newestLastSeenAt()
        );
    }
}
