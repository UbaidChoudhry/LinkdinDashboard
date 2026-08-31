import { useCallback, useEffect, useMemo, useState } from "react";
import { ApiError, listJobs } from "../api/client";
import type { JobResponse, JobTab, RunResponse } from "../types/api";
import { DEFAULT_SORT, filterByMinSalary, nextSortState, sortJobsBy, type SortState } from "../utils/sort";
import { JobRow } from "./JobRow";
import { PlainHeader, SortableHeader } from "./SortableHeader";

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
  const [sort, setSort] = useState<SortState>(DEFAULT_SORT);
  // Empty input = no filter. Stored as a number once the field parses.
  const [minSalary, setMinSalary] = useState<number | null>(null);

  const load = useCallback(async () => {
    setError(null);
    setJobs(null);
    try {
      const includePrev = activeTab === "search" ? includePreviousRuns : true;
      const data = await listJobs(activeTab, includePrev);
      setJobs(data);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load jobs.");
      setJobs([]);
    }
  }, [activeTab, includePreviousRuns]);

  useEffect(() => {
    load();
  }, [load, refreshToken]);

  // Switching tabs resets to the default (salary-bucket) view rather than carrying a sort
  // choice from one list over to an unrelated one.
  useEffect(() => {
    setSort(DEFAULT_SORT);
    setMinSalary(null);
  }, [activeTab]);

  const sortedJobs = useMemo(() => {
    if (!jobs) return jobs;
    return filterByMinSalary(sortJobsBy(jobs, sort), minSalary);
  }, [jobs, sort, minSalary]);
  const handleSort = useCallback((column: Parameters<typeof nextSortState>[1]) => {
    setSort((current) => nextSortState(current, column));
  }, []);

  function handleChanged(updated: JobResponse) {
    setJobs((prev) => {
      if (!prev) return prev;
      // The job's new status no longer belongs in this tab's list (unless we're on the tab it
      // moved into and a refetch would show it anyway) - simplest correct behaviour is to drop
      // it from the current view immediately and let the row disappear.
      return prev.filter((j) => j.jobId !== updated.jobId);
    });
  }

  // Same money formatting as JobRow's formatSalary, for the "filter hid everything" hint.
  function formatUsd(n: number): string {
    return `$${Math.round(n).toLocaleString()}`;
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

      <div className="field-row min-salary-row">
        <label htmlFor="jp-min-salary">Min salary</label>
        <input
          id="jp-min-salary"
          type="number"
          min="0"
          step="10000"
          inputMode="numeric"
          placeholder="No minimum"
          value={minSalary ?? ""}
          onChange={(e) => {
            const v = Number(e.target.value);
            setMinSalary(e.target.value.trim() === "" || Number.isNaN(v) ? null : v);
          }}
        />
      </div>

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

      {jobs !== null &&
        jobs.length > 0 &&
        sortedJobs !== null &&
        sortedJobs.length === 0 &&
        !error &&
        minSalary != null && (
          <p className="hint">
            No jobs at or above {formatUsd(minSalary)}. Lower the minimum or clear it.
          </p>
        )}

      {sortedJobs !== null && sortedJobs.length > 0 && (
        <>
          <p className="hint sort-hint">
            {sort.column === "default"
              ? "Sorted by salary, then most recent. Click a column to sort by it instead."
              : "Click a column to change sort, or click it again to reverse."}{" "}
            The min-salary box never hides jobs whose salary is unknown. Salaries from LCA disclosure
            data show the employer entity they were matched to; a leading ≈ marks an approximate match.
          </p>
          <table className="job-table">
            <thead>
              <tr>
                <SortableHeader column="title" label="Title" sort={sort} onSort={handleSort} />
                <SortableHeader column="company" label="Company" sort={sort} onSort={handleSort} />
                <SortableHeader column="location" label="Location" sort={sort} onSort={handleSort} />
                <SortableHeader column="postedAt" label="Posted" sort={sort} onSort={handleSort} />
                <SortableHeader column="salary" label="Salary" sort={sort} onSort={handleSort} />
                <PlainHeader label="Actions" />
              </tr>
            </thead>
            <tbody>
              {sortedJobs.map((job) => (
                <JobRow key={job.jobId} job={job} tab={activeTab} onChanged={handleChanged} onError={setActionError} />
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
