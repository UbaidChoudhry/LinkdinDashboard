import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, cancelApplyBatch, getApplyBatch, getCurrentApplyBatch } from "../api/client";
import type { ApplyBatchResponse } from "../types/api";

const POLL_MS = 2000;

/**
 * The batch this component is watching: re-attaches to one still running after a page reload,
 * polls it every 2s while it runs, and calls `onFinished` once when it stops. `start` takes the
 * request that starts a batch, so each caller decides what it applies to.
 */
export function useApplyBatch(onFinished: () => void) {
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

  /** Starts a batch with `request` and begins watching it. Resolves true when it started. */
  async function start(request: () => Promise<{ batchId: number }>): Promise<boolean> {
    setStarting(true);
    setError(null);
    try {
      const { batchId } = await request();
      finishedBatchIdRef.current = null;
      const fresh = await getApplyBatch(batchId);
      setBatch(fresh);
      if (fresh.status === "running") {
        poll(batchId);
      }
      return true;
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to start applying.");
      return false;
    } finally {
      setStarting(false);
    }
  }

  async function cancel() {
    if (!batch) return;
    setError(null);
    try {
      await cancelApplyBatch(batch.id);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to cancel.");
    }
  }

  return { batch, running: batch?.status === "running", starting, error, start, cancel };
}
