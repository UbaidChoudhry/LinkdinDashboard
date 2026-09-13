import { useCallback, useEffect, useRef, useState } from "react";
import {
  ApiError,
  cancelApplyBatch,
  getApplyBatch,
  getCurrentApplyBatch,
  startApplications,
} from "../api/client";
import type { ApplyBatchResponse } from "../types/api";

interface ApplyControlsProps {
  /** Job ids eligible for this run: recommended, untriaged, non-LinkedIn rows on screen. */
  eligibleJobIds: number[];
  /** How many recommended rows on screen are LinkedIn (skipped, apply manually). */
  linkedinCount: number;
  /** Called once a batch finishes, so the caller can reload its job list. */
  onFinished: () => void;
}

const POLL_MS = 2000;

function summaryLine(batch: ApplyBatchResponse): string {
  return (
    `Done: ${batch.needsReview} need review, ${batch.submitted} submitted, ` +
    `${batch.skipped} skipped (LinkedIn: apply manually), ${batch.failed} failed · ` +
    `$${batch.costUsd.toFixed(2)}`
  );
}

export function ApplyControls({ eligibleJobIds, linkedinCount, onFinished }: ApplyControlsProps) {
  const [submitChecked, setSubmitChecked] = useState(false);
  const [batch, setBatch] = useState<ApplyBatchResponse | null>(null);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // Guards against onFinished() firing twice for the same batch (e.g. a poll tick landing right
  // after cancel/finish has already been handled).
  const finishedBatchIdRef = useRef<number | null>(null);

  const stopPolling = useCallback(() => {
    if (pollRef.current != null) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const handleFinished = useCallback(
    (finished: ApplyBatchResponse) => {
      stopPolling();
      setBatch(finished);
      if (finishedBatchIdRef.current !== finished.id) {
        finishedBatchIdRef.current = finished.id;
        onFinished();
      }
    },
    [onFinished, stopPolling],
  );

  const poll = useCallback(
    (batchId: number) => {
      stopPolling();
      pollRef.current = setInterval(async () => {
        try {
          const latest = await getApplyBatch(batchId);
          setBatch(latest);
          if (latest.status !== "running") {
            handleFinished(latest);
          }
        } catch {
          // A transient poll failure isn't worth surfacing as an error - the next tick retries.
        }
      }, POLL_MS);
    },
    [handleFinished, stopPolling],
  );

  // Re-attach to a batch that's still running after a page reload.
  useEffect(() => {
    (async () => {
      try {
        const current = await getCurrentApplyBatch();
        if (current) {
          setBatch(current);
          if (current.status === "running") {
            poll(current.id);
          }
        }
      } catch {
        // No current batch, or the endpoint isn't reachable yet - nothing to re-attach to.
      }
    })();
    return stopPolling;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleApply() {
    setStarting(true);
    setError(null);
    try {
      const { batchId } = await startApplications({ jobIds: eligibleJobIds, submit: submitChecked });
      finishedBatchIdRef.current = null;
      const fresh = await getApplyBatch(batchId);
      setBatch(fresh);
      if (fresh.status === "running") {
        poll(batchId);
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to start applying.");
    } finally {
      setStarting(false);
    }
  }

  async function handleCancel() {
    if (!batch) return;
    setError(null);
    try {
      await cancelApplyBatch(batch.id);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to cancel.");
    }
  }

  const running = batch?.status === "running";
  const currentJob = running ? batch?.jobs.find((j) => j.status === "filling" || j.status === "queued") : undefined;

  return (
    <div className="apply-controls">
      <button type="button" onClick={handleApply} disabled={eligibleJobIds.length === 0 || running || starting}>
        {starting ? "Starting…" : `Apply with Claude (${eligibleJobIds.length})`}
      </button>

      <span className="checkbox-row">
        <input
          id="apply-submit"
          type="checkbox"
          checked={submitChecked}
          onChange={(e) => setSubmitChecked(e.target.checked)}
          disabled={running}
        />
        <label htmlFor="apply-submit">Submit applications</label>
      </span>
      <span className="apply-note">
        Unchecked: Claude fills each form and stops before Submit so you can review
      </span>

      {linkedinCount > 0 && (
        <span className="apply-note">
          {linkedinCount} LinkedIn job{linkedinCount === 1 ? "" : "s"} will be skipped — apply to
          those manually.
        </span>
      )}

      {running && batch && (
        <span className="apply-note apply-progress">
          Applying {batch.done}/{batch.total}
          {currentJob ? ` · ${currentJob.title} — ${currentJob.company}…` : "…"}
          <button type="button" className="secondary" onClick={handleCancel}>
            Cancel
          </button>
        </span>
      )}

      {!running && batch && batch.status !== "running" && (
        <span className="apply-note">{summaryLine(batch)}</span>
      )}

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
