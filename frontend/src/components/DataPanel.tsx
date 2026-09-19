import { useCallback, useEffect, useState } from "react";
import { ApiError, clearJobData, getDataStats } from "../api/client";
import type { DataStatsResponse } from "../types/api";
import { absoluteTime, formatBytes, relativeTime } from "../utils/format";
import { InfoTip } from "./InfoTip";

interface DataPanelProps {
  /** Bumped whenever something elsewhere (a finished run, a status change) should be reflected here too. */
  refreshToken: number;
  /** Called after a successful clear so the caller can refresh anything else showing job data (e.g. the Jobs tabs). */
  onCleared: () => void;
}

type ClearState = "idle" | "confirming" | "clearing";

export function DataPanel({ refreshToken, onCleared }: DataPanelProps) {
  const [stats, setStats] = useState<DataStatsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [clearState, setClearState] = useState<ClearState>("idle");
  const [clearError, setClearError] = useState<string | null>(null);
  const [lastCleared, setLastCleared] = useState<{ jobs: number; runs: number } | null>(null);

  const load = useCallback(async () => {
    try {
      const data = await getDataStats();
      setStats(data);
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load database stats.");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load, refreshToken]);

  async function handleConfirmClear() {
    setClearState("clearing");
    setClearError(null);
    try {
      const result = await clearJobData();
      setLastCleared({ jobs: result.jobsCleared, runs: result.runsCleared });
      setClearState("idle");
      await load();
      onCleared();
    } catch (err) {
      setClearError(err instanceof ApiError ? err.message : "Failed to clear the database.");
      setClearState("confirming");
    }
  }

  if (error) {
    return (
      <section className="data-panel">
        <h2>Data</h2>
        <p className="form-error" role="alert">
          {error}
        </p>
      </section>
    );
  }

  if (!stats) {
    return (
      <section className="data-panel">
        <h2>Data</h2>
        <p className="hint">Loading database stats...</p>
      </section>
    );
  }

  const hasAnyJobData = stats.totalJobs > 0 || stats.totalRuns > 0;

  return (
    <section className="data-panel">
      <h2>Data</h2>

      <div className="data-stats-grid">
        <div className="data-stat">
          <span className="data-stat-value">{formatBytes(stats.databaseSizeBytes)}</span>
          <span className="data-stat-label">Database size on disk</span>
        </div>
        <div className="data-stat">
          <span className="data-stat-value">{stats.totalJobs.toLocaleString()}</span>
          <span className="data-stat-label">Jobs stored</span>
        </div>
        <div className="data-stat">
          <span className="data-stat-value">{stats.totalRuns.toLocaleString()}</span>
          <span className="data-stat-label">Runs recorded</span>
        </div>
        <div className="data-stat">
          <span className="data-stat-value">{stats.requestsLast24h.toLocaleString()}</span>
          <span className="data-stat-label">LinkedIn requests, last 24h</span>
        </div>
      </div>

      <div className="data-breakdown">
        <div>
          <h3>By filter verdict</h3>
          <dl>
            <dt>Passed</dt>
            <dd>{stats.jobsPassed.toLocaleString()}</dd>
            <dt>Rejected</dt>
            <dd>{stats.jobsRejected.toLocaleString()}</dd>
            <dt>Not yet evaluated</dt>
            <dd>{stats.jobsUnevaluated.toLocaleString()}</dd>
          </dl>
        </div>
        <div>
          <h3>By your status</h3>
          <dl>
            <dt>Applied</dt>
            <dd>{stats.jobsApplied.toLocaleString()}</dd>
            <dt>Not interested</dt>
            <dd>{stats.jobsNotInterested.toLocaleString()}</dd>
            <dt>Untriaged</dt>
            <dd>{stats.jobsUntriaged.toLocaleString()}</dd>
          </dl>
        </div>
        <div>
          <h3>Your saved lists</h3>
          <dl>
            <dt>Exclude words</dt>
            <dd>{stats.excludeWordCount.toLocaleString()}</dd>
            <dt>Blocked companies</dt>
            <dd>{stats.blockedCompanyCount.toLocaleString()}</dd>
          </dl>
        </div>
      </div>

      {stats.oldestFirstSeenAt && stats.newestLastSeenAt && (
        <p className="hint">
          Oldest job first seen {relativeTime(stats.oldestFirstSeenAt)} ({absoluteTime(stats.oldestFirstSeenAt)}),
          most recent activity {relativeTime(stats.newestLastSeenAt)}.
        </p>
      )}

      <div className="data-clear-section">
        <h3 className="section-heading">
          Clear job data
          <InfoTip label="About clearing job data">
            Permanently deletes every stored job and run - Search, Applied, and Not interested all
            go empty. Exclude words and blocked companies are kept. The request log
            ({stats.requestLogEntries.toLocaleString()} entries) backs the daily LinkedIn request
            budget and is never cleared, so the rate limiter cannot be reset this way.
          </InfoTip>
        </h3>

        {lastCleared && clearState === "idle" && (
          <p className="hint data-clear-success">
            Cleared {lastCleared.jobs.toLocaleString()} job{lastCleared.jobs === 1 ? "" : "s"} and{" "}
            {lastCleared.runs.toLocaleString()} run{lastCleared.runs === 1 ? "" : "s"}.
          </p>
        )}

        {clearError && (
          <p className="form-error" role="alert">
            {clearError}
          </p>
        )}

        {clearState === "idle" && (
          <button
            type="button"
            className="danger"
            disabled={!hasAnyJobData}
            onClick={() => setClearState("confirming")}
          >
            Clear job data...
          </button>
        )}

        {clearState !== "idle" && (
          <div className="data-clear-confirm" role="alertdialog" aria-label="Confirm clearing job data">
            <p>
              <strong>
                This will permanently delete {stats.totalJobs.toLocaleString()} job
                {stats.totalJobs === 1 ? "" : "s"} and {stats.totalRuns.toLocaleString()} run
                {stats.totalRuns === 1 ? "" : "s"}.
              </strong>{" "}
              Exclude words and blocked companies are kept. This cannot be undone.
            </p>
            <div className="data-clear-actions">
              <button
                type="button"
                className="danger"
                disabled={clearState === "clearing"}
                onClick={handleConfirmClear}
              >
                {clearState === "clearing" ? "Clearing..." : "Yes, delete everything"}
              </button>
              <button
                type="button"
                className="secondary"
                disabled={clearState === "clearing"}
                onClick={() => {
                  setClearState("idle");
                  setClearError(null);
                }}
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
    </section>
  );
}
