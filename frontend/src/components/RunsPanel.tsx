import { useCallback, useEffect, useState } from "react";
import { ApiError, listRuns } from "../api/client";
import type { RunResponse } from "../types/api";
import { SOURCE_LABELS, type JobSourceName } from "../types/api";
import { absoluteTime, relativeTime } from "../utils/format";
import { runStatusInfo } from "../utils/runStatus";

interface RunsPanelProps {
  /** Bumped whenever the shell wants this panel to refetch (e.g. a run just finished). */
  refreshToken: number;
}

/** How many runs to pull for the comparison table - more than the shell's own "last run" fetch. */
const HISTORY_LIMIT = 50;

/** "greenhouse,lever" -> "Greenhouse, Lever". Falls back to the raw token for an unknown source. */
function sourcesLabel(sources: string | null | undefined): string {
  if (!sources) return "LinkedIn";
  return sources
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => SOURCE_LABELS[s as JobSourceName] ?? s)
    .join(", ");
}

/** Wall-clock duration between start and finish, or "in progress" while the run is still going. */
function duration(run: RunResponse): string {
  if (!run.startedAt) return "-";
  if (!run.finishedAt) return "in progress";
  const startMs = new Date(run.startedAt).getTime();
  const endMs = new Date(run.finishedAt).getTime();
  if (Number.isNaN(startMs) || Number.isNaN(endMs)) return "-";
  const totalSec = Math.max(0, Math.round((endMs - startMs) / 1000));
  const minutes = Math.floor(totalSec / 60);
  const seconds = totalSec % 60;
  if (minutes === 0) return `${seconds}s`;
  return `${minutes}m ${seconds}s`;
}

/** "12/45", or "-" when the run never had this counter (e.g. companies for a LinkedIn-only run). */
function fraction(done: number | null | undefined, total: number | null | undefined): string {
  if (total == null || total === 0) return "-";
  return `${done ?? 0}/${total}`;
}

export function RunsPanel({ refreshToken }: RunsPanelProps) {
  const [runs, setRuns] = useState<RunResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const data = await listRuns(HISTORY_LIMIT);
      setRuns(data);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load run history.");
      setRuns([]);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load, refreshToken]);

  return (
    <section className="runs-panel">
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      {runs === null && !error && <p className="hint">Loading runs...</p>}

      {runs !== null && runs.length === 0 && !error && <p className="hint">No runs yet.</p>}

      {runs !== null && runs.length > 0 && (
        <div className="job-table-scroll">
          <table className="job-table runs-table">
            <thead>
              <tr>
                <th>Started</th>
                <th>Duration</th>
                <th>Sources</th>
                <th>Status</th>
                <th>Cards seen</th>
                <th>New jobs</th>
                <th>Companies</th>
                <th>Details fetched</th>
                <th>Scan scored</th>
                <th>Recommended</th>
                <th>Not recommended</th>
                <th>Cost</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((run) => {
                const info = runStatusInfo(run.status);
                const scan = run.scan;
                return (
                  <tr key={run.id}>
                    <td title={absoluteTime(run.startedAt)}>{relativeTime(run.startedAt)}</td>
                    <td>{duration(run)}</td>
                    <td>{sourcesLabel(run.sources)}</td>
                    <td>
                      <span className={`status-pill status-${info.kind}`}>{info.label}</span>
                    </td>
                    <td>{run.cardsSeen}</td>
                    <td>{run.jobsNew}</td>
                    <td>{fraction(run.companiesDone, run.companiesTotal)}</td>
                    <td>{fraction(run.detailsDone, run.detailsTotal)}</td>
                    <td>{scan ? `${scan.jobsScanned}/${scan.jobsTotal}` : "-"}</td>
                    <td>{scan ? scan.recommended : "-"}</td>
                    <td>{scan ? scan.notRecommended : "-"}</td>
                    <td>{scan ? `$${scan.costUsd.toFixed(4)}` : "-"}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
