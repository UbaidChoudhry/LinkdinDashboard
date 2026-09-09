import { useEffect, useState } from "react";
import { ApiError, cancelRun } from "../api/client";
import type { RunResponse, ScanProgress } from "../types/api";
import { isRunInFlight, runStatusInfo } from "../utils/runStatus";

interface RunProgressProps {
  run: RunResponse;
  streamError: string | null;
  onCancelled: () => void;
}

export function RunProgress({ run, streamError, onCancelled }: RunProgressProps) {
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  const info = runStatusInfo(run.status);
  // Cancel must stay available during the scan phase too, not just while collecting.
  const isRunning = isRunInFlight(run.status);

  async function handleCancel() {
    setCancelError(null);
    setCancelling(true);
    try {
      await cancelRun(run.id);
      onCancelled();
    } catch (err) {
      setCancelError(err instanceof ApiError ? err.message : "Failed to cancel run.");
    } finally {
      setCancelling(false);
    }
  }

  return (
    <section className="run-progress" aria-live="polite">
      <h2>Run #{run.id}</h2>

      <div className={`status-banner status-${info.kind}`}>
        <span className="status-label">{info.label}</span>
        <span className="status-message">{info.message}</span>
      </div>

      {run.saturated && (
        <div className="saturation-warning" role="alert">
          <strong>Results were truncated.</strong> This run hit LinkedIn's 1000-result cap for the
          search. Some jobs that matched were never visible to this run and are missing from the
          results - this is a data-loss condition, not a minor limitation.
        </div>
      )}

      <dl className="progress-stats">
        <div>
          <dt>Pages fetched</dt>
          <dd>{run.pagesFetched}</dd>
        </div>
        <div>
          <dt>Requests made</dt>
          <dd>{run.requestsMade}</dd>
        </div>
        <div>
          <dt>Cards seen</dt>
          <dd>{run.cardsSeen}</dd>
        </div>
        <div>
          <dt>New jobs</dt>
          <dd>{run.jobsNew}</dd>
        </div>
        <div>
          <dt>Current shard</dt>
          <dd>{run.currentShard ?? "-"}</dd>
        </div>
        {(run.companiesTotal ?? 0) > 0 && (
          <div>
            <dt>Companies visited</dt>
            <dd>
              {run.companiesDone ?? 0}/{run.companiesTotal}
            </dd>
          </div>
        )}
        {(run.detailsTotal ?? 0) > 0 && (
          <div>
            <dt>Descriptions fetched</dt>
            <dd>
              {run.detailsDone ?? 0}/{run.detailsTotal}
            </dd>
          </div>
        )}
      </dl>

      {run.scan && <ScanPanel scan={run.scan} live={run.status === "scanning"} />}

      {streamError && (
        <p className="form-error" role="alert">
          {streamError}
        </p>
      )}
      {cancelError && (
        <p className="form-error" role="alert">
          {cancelError}
        </p>
      )}

      {isRunning && (
        <button type="button" onClick={handleCancel} disabled={cancelling} className="danger">
          {cancelling ? "Cancelling..." : "Cancel run"}
        </button>
      )}
    </section>
  );
}

/** Formats a duration in whole seconds as m:ss. */
function elapsed(fromIso: string | null): string {
  if (!fromIso) return "-";
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(fromIso).getTime()) / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

/**
 * Live numbers for the AI scan. The scan is the one phase with no per-item feedback of its own -
 * a batch takes seconds and several run at once - so without this the UI sits on "Scanning with
 * AI" and looks hung.
 */
function ScanPanel({ scan, live }: { scan: ScanProgress; live: boolean }) {
  // The elapsed clock has to advance between server updates, which only arrive when a batch
  // finishes; without a local tick it would freeze for seconds at a time and read as a stall.
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!live) return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [live]);

  const pct = scan.jobsTotal > 0 ? Math.round((scan.jobsScanned / scan.jobsTotal) * 100) : 0;

  return (
    <div className="scan-panel">
      <div className="scan-head">
        <strong>AI scan</strong>
        <span className="scan-counts">
          batch {scan.batchesDone}/{scan.batchesTotal} · {scan.jobsScanned}/{scan.jobsTotal} jobs
        </span>
        {live && <span className="scan-elapsed">{elapsed(scan.startedAt)}</span>}
      </div>

      <div className="scan-bar" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
        <div className="scan-bar-fill" style={{ width: `${pct}%` }} />
      </div>

      <dl className="scan-stats">
        <div>
          <dt>Recommended</dt>
          <dd className="scan-rec">{scan.recommended}</dd>
        </div>
        <div>
          <dt>Not recommended</dt>
          <dd>{scan.notRecommended}</dd>
        </div>
        <div>
          <dt>Failed batches</dt>
          <dd className={scan.failedBatches > 0 ? "scan-fail" : undefined}>{scan.failedBatches}</dd>
        </div>
        <div>
          <dt>Cost</dt>
          <dd>${scan.costUsd.toFixed(4)}</dd>
        </div>
      </dl>

      <p className="scan-hint">
        Live detail: <code>tail -f logs/ai-scan.log</code>
      </p>
    </div>
  );
}
