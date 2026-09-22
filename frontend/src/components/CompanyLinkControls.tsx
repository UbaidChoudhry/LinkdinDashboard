import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, cancelCompanyLinkSearch, getCompanyLinkSearch, startCompanyLinkSearch } from "../api/client";
import type { CompanyLinkProgress } from "../types/api";
import { InfoTip } from "./InfoTip";

const POLL_MS = 3000;

/**
 * "Find company links": searches the employers' own careers sites for the latest run's
 * recommended LinkedIn jobs that have no apply link yet, and fills the Match column. The same
 * search runs by itself at the end of every run, so this also shows that one while it works.
 */
export function CompanyLinkControls({ onFinished }: { onFinished: () => void }) {
  const [progress, setProgress] = useState<CompanyLinkProgress | null>(null);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Only a search this page watched finish gets a summary line - not whatever ran last week.
  const [watched, setWatched] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPolling = useCallback(() => {
    if (pollRef.current != null) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const poll = useCallback(() => {
    stopPolling();
    setWatched(true);
    pollRef.current = setInterval(async () => {
      try {
        const latest = await getCompanyLinkSearch();
        setProgress(latest);
        if (latest && !latest.running) {
          stopPolling();
          onFinished();
        }
      } catch {
        // A transient poll failure isn't worth surfacing - the next tick retries.
      }
    }, POLL_MS);
  }, [onFinished, stopPolling]);

  // Pick up a search already running (a run's own phase, or one started before a reload).
  useEffect(() => {
    getCompanyLinkSearch()
      .then((current) => {
        setProgress(current);
        if (current?.running) poll();
      })
      .catch(() => {});
    return stopPolling;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleStart() {
    setStarting(true);
    setError(null);
    try {
      await startCompanyLinkSearch();
      setProgress(await getCompanyLinkSearch());
      poll();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to start the search.");
    } finally {
      setStarting(false);
    }
  }

  async function handleCancel() {
    try {
      await cancelCompanyLinkSearch();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to cancel.");
    }
  }

  const running = progress?.running ?? false;

  return (
    <span className="apply-controls">
      <button type="button" className="secondary" onClick={handleStart} disabled={running || starting}>
        {starting ? "Starting…" : "Find company links"}
      </button>
      <InfoTip label="About Find company links">
        Looks for each recommended LinkedIn job on the employer's own careers site with web search -
        nothing opens LinkedIn - and compares title, location, description and posting date. The
        Match column shows how sure it is; only confident matches become the link Apply with Claude
        uses. Jobs already searched are skipped. Runs by itself at the end of every run, too.
      </InfoTip>
      {running && progress && (
        <span className="apply-note">
          Searching company sites {progress.done}/{progress.total} · {progress.found} found · $
          {progress.costUsd.toFixed(2)}
          <button type="button" className="secondary" onClick={handleCancel}>
            Cancel
          </button>
        </span>
      )}
      {!running && watched && progress && (
        <span className="apply-note">
          {progress.error
            ? progress.error
            : `Found ${progress.found} of ${progress.total} (${progress.linked} confident enough to apply) · $${progress.costUsd.toFixed(2)}`}
        </span>
      )}
      {error && (
        <span className="form-error" role="alert">
          {error}
        </span>
      )}
    </span>
  );
}
