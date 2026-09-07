import { useEffect, useRef, useState } from "react";
import type { RunResponse } from "../types/api";
import { isRunInFlight } from "../utils/runStatus";

interface UseRunStreamResult {
  run: RunResponse | null;
  streamError: string | null;
}

/**
 * Opens an SSE connection to /api/runs/{id}/stream and keeps the latest RunResponse-shaped
 * progress event in state. Closes the EventSource on unmount and whenever runId changes, so
 * connections never leak across runs.
 */
export function useRunStream(runId: number | null): UseRunStreamResult {
  const [run, setRun] = useState<RunResponse | null>(null);
  const [streamError, setStreamError] = useState<string | null>(null);
  const sourceRef = useRef<EventSource | null>(null);

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
        // Close only on a genuinely terminal status. "scanning" is still in flight - closing
        // here was what made the AI scan's progress never reach the page.
        if (!isRunInFlight(data.status)) {
          source.close();
        }
      } catch {
        // ignore malformed event - the next tick will likely be fine
      }
    });

    source.onerror = () => {
      setStreamError("Lost connection to the run's live progress stream.");
      source.close();
    };

    return () => {
      source.close();
      sourceRef.current = null;
    };
  }, [runId]);

  return { run, streamError };
}
