package com.ubaid.jobdash.ai;

/**
 * Receives {@link ScanProgress} snapshots while a scan runs. Implementations must be cheap and
 * must not throw: they are called from the scan's worker threads, and a listener failure must
 * never be able to derail the scan.
 */
@FunctionalInterface
public interface ScanProgressListener {

    /** Used when a caller wants no progress reporting (a plain re-scan, or a test). */
    ScanProgressListener NONE = progress -> {
    };

    void onProgress(ScanProgress progress);
}
