import { useState } from "react";
import { ApiError, cancelRun } from "../api/client";
import type { RunResponse } from "../types/api";
import { runStatusInfo } from "../utils/runStatus";

interface RunProgressProps {
  run: RunResponse;
  streamError: string | null;
  onCancelled: () => void;
}

export function RunProgress({ run, streamError, onCancelled }: RunProgressProps) {
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  const info = runStatusInfo(run.status);
  const isRunning = run.status === "running";

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
      </dl>

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
