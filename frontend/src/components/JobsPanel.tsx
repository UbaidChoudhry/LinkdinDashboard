import { useCallback, useEffect, useState } from "react";
import { ApiError, listJobs } from "../api/client";
import type { JobResponse, JobTab, RunResponse } from "../types/api";
import { sortJobs } from "../utils/sort";
import { JobRow } from "./JobRow";

interface JobsPanelProps {
  /** Bumped whenever the caller wants JobsPanel to refetch (e.g. a run just finished). */
  refreshToken: number;
  /** The most recently known run (live or historical), used to explain an empty Search tab. */
  latestRun: RunResponse | null;
}

const TABS: { id: JobTab; label: string }[] = [
  { id: "search", label: "Search" },
  { id: "applied", label: "Applied" },
  { id: "not_interested", label: "Not interested" },
];

export function JobsPanel({ refreshToken, latestRun }: JobsPanelProps) {
  const [activeTab, setActiveTab] = useState<JobTab>("search");
  const [includePreviousRuns, setIncludePreviousRuns] = useState(false);
  const [jobs, setJobs] = useState<JobResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    setJobs(null);
    try {
      const includePrev = activeTab === "search" ? includePreviousRuns : true;
      const data = await listJobs(activeTab, includePrev);
      setJobs(sortJobs(data));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load jobs.");
      setJobs([]);
    }
  }, [activeTab, includePreviousRuns]);

  useEffect(() => {
    load();
  }, [load, refreshToken]);

  function handleChanged(updated: JobResponse) {
    setJobs((prev) => {
      if (!prev) return prev;
      // The job's new status no longer belongs in this tab's list (unless we're on the tab it
      // moved into and a refetch would show it anyway) - simplest correct behaviour is to drop
      // it from the current view immediately and let the row disappear.
      return prev.filter((j) => j.jobId !== updated.jobId);
    });
  }

  function emptyReason(): string {
    if (activeTab === "search") {
      if (!latestRun) {
        return "No run has been started yet. Start a run above to see results here.";
      }
      if (latestRun.status === "running") {
        return "The current run is still in progress - results will appear as they're found.";
      }
      if (latestRun.cardsSeen === 0) {
        return "The most recent run didn't see any job cards for this search.";
      }
      if (latestRun.jobsNew === 0) {
        return "The most recent run saw job cards, but every one of them was filtered out (exclude words, blocked companies, or already seen).";
      }
      return includePreviousRuns
        ? "No jobs are currently untriaged across any run."
        : "No untriaged jobs from the current run. Try including previous runs, or start a new run.";
    }
    if (activeTab === "applied") {
      return "No jobs marked as Applied yet.";
    }
    return "No jobs marked as Not interested yet.";
  }

  return (
    <section className="jobs-panel">
      <div className="tab-bar" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={activeTab === t.id}
            className={activeTab === t.id ? "tab active" : "tab"}
            onClick={() => setActiveTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {activeTab === "search" && (
        <div className="field-row checkbox-row">
          <input
            id="jp-include-previous"
            type="checkbox"
            checked={includePreviousRuns}
            onChange={(e) => setIncludePreviousRuns(e.target.checked)}
          />
          <label htmlFor="jp-include-previous">Include previous runs</label>
        </div>
      )}

      {actionError && (
        <p className="form-error" role="alert">
          {actionError}
        </p>
      )}

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      {jobs === null && !error && <p className="hint">Loading jobs...</p>}

      {jobs !== null && jobs.length === 0 && !error && <p className="hint">{emptyReason()}</p>}

      {jobs !== null && jobs.length > 0 && (
        <table className="job-table">
          <thead>
            <tr>
              <th>Title</th>
              <th>Company</th>
              <th>Location</th>
              <th>Posted</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {jobs.map((job) => (
              <JobRow key={job.jobId} job={job} tab={activeTab} onChanged={handleChanged} onError={setActionError} />
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
