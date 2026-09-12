import { useEffect, useRef, useState } from "react";
import type { RunResponse } from "../types/api";
import { isRunFinished } from "../utils/runStatus";

interface UseRunStreamResult {
  run: RunResponse | null;
  streamError: string | null;
}

/**
 * Opens an SSE connection to /api/runs/{id}/stream and keeps the latest RunResponse-shaped
 * progress event in state. Closes the EventSource on unmount and whenever runId changes, so
 * connections never leak across runs.
 */
export function useRunStream(runId: number | null, attempt = 0): UseRunStreamResult {
  const [run, setRun] = useState<RunResponse | null>(null);
  const [streamError, setStreamError] = useState<string | null>(null);
  const sourceRef = useRef<EventSource | null>(null);

  // `attempt` is bumped by the shell when a run is RESUMED: the id is the same, but the
  // EventSource closed itself when the run first finished, so a fresh subscription is needed.
  useEffect(() => {
    setRun(null);
    setStreamError(null);

    if (runId == null) {
      return;
    }

    const source = new EventSource(`/api/runs/${runId}/stream`);
    sourceRef.current = source;

    source.addEventListener("progress", (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data) as RunResponse;
        setRun(data);
        // A successful event means we're connected (again, if onerror below had fired) - drop
        // any stale "lost connection" note rather than leaving it up after a reconnect.
        setStreamError(null);
        // Close only once the run has genuinely finished (isRunFinished - status alone isn't
        // enough, see its doc comment for the phase-boundary race this avoids). Closing on a
        // status that merely looked terminal was what made the AI scan's progress never reach
        // the page: the stream died while the run kept going server-side. If this fires after a
        // reconnect below, that's correct too: the first event on the new connection carries the
        // run's current (by now genuinely final) state, so the client still closes cleanly.
        if (isRunFinished(data)) {
          source.close();
        }
      } catch {
        // ignore malformed event - the next tick will likely be fine
      }
    });

    source.onerror = () => {
      // Do NOT close here. A transient drop (or the server's own SSE_TIMEOUT_MS lapsing on a
      // long-running scan, which completes the emitter from RunController's side) makes the
      // browser fire this and, by default, automatically reconnect to the same URL a few seconds
      // later - the endpoint is a plain idempotent GET, so that just resumes the poll. Calling
      // source.close() here (as this used to) sets readyState to CLOSED and cancels that
      // built-in reconnect, permanently killing live progress until a manual page reload - the
      // exact "have to refresh to see the scan" symptom. Just surface a soft note; the progress
      // handler above clears it once events resume, and closes the connection for real once (and
      // only once) the run has actually finished.
      setStreamError("Lost connection to the run's live progress stream - retrying...");
    };

    return () => {
      source.close();
      sourceRef.current = null;
    };
  }, [runId, attempt]);

  return { run, streamError };
}
