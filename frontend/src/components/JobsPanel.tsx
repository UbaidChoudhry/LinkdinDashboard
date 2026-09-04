import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { ApiError, listJobs, scanMatches } from "../api/client";
import type { JobResponse, JobTab, MatchBucket, RunResponse } from "../types/api";
import { DEFAULT_SORT, filterByMinSalary, nextSortState, sortJobsBy, type SortState } from "../utils/sort";
import { groupByCompany } from "../utils/group";
import { JobRow } from "./JobRow";
import { CompanyGroupRow } from "./CompanyGroupRow";
import { InfoTip } from "./InfoTip";
import { PlainHeader, SortableHeader } from "./SortableHeader";

interface JobsPanelProps {
  /** Bumped whenever the caller wants JobsPanel to refetch (e.g. a run just finished). */
  refreshToken: number;
  /** The most recently known run (live or historical), used to explain an empty Untriaged tab. */
  latestRun: RunResponse | null;
  /** Reports how many rows are currently on screen, so the shell can badge the Results tab. */
  onCountChange?: (count: number) => void;
}

// The API tab id stays "search" - only the label changes. It sits inside the shell's own
// "Search" tab (which is the run form), so calling it "Search" here too read as Search > Search.
const TABS: { id: JobTab; label: string }[] = [
  { id: "search", label: "Untriaged" },
  { id: "applied", label: "Applied" },
  { id: "not_interested", label: "Not interested" },
];

/** 130000 -> "130,000". Empty string when there's no value. */
function groupDigits(value: number | null): string {
  return value == null ? "" : value.toLocaleString("en-US");
}

/**
 * Where the caret belongs in a freshly formatted string: after the same number of DIGITS it was
 * after before. Counting digits rather than characters is what stops an inserted comma shunting
 * the cursor - typing "1" into "30,000" must land the caret after the 1, not at the end.
 */
function caretAfterDigits(formatted: string, digitCount: number): number {
  let pos = 0;
  let seen = 0;
  while (pos < formatted.length && seen < digitCount) {
    if (formatted[pos] >= "0" && formatted[pos] <= "9") seen++;
    pos++;
  }
  return pos;
}

export function JobsPanel({ refreshToken, latestRun, onCountChange }: JobsPanelProps) {
  const [activeTab, setActiveTab] = useState<JobTab>("search");
  const [includePreviousRuns, setIncludePreviousRuns] = useState(false);
  const [jobs, setJobs] = useState<JobResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [sort, setSort] = useState<SortState>(DEFAULT_SORT);
  // Empty input = no filter. Stored as a number once the field parses.
  const [minSalary, setMinSalary] = useState<number | null>(null);
  const minSalaryInputRef = useRef<HTMLInputElement>(null);
  // Where to put the caret once React has rewritten the input's formatted value.
  const minSalaryCaretRef = useRef<number | null>(null);
  // Gates the min-salary filter without discarding the number, so it can be flicked off and
  // back on without retyping. On by default: typing a figure normally means you want it applied.
  const [minSalaryOn, setMinSalaryOn] = useState(true);
  const [groupByCompanyOn, setGroupByCompanyOn] = useState(false);
  // Which AI bucket to show. "all" keeps every row, including rows that were never scanned
  // (LinkedIn rows never can be - they have no description).
  const [bucket, setBucket] = useState<MatchBucket | "all">("all");
  const [scanning, setScanning] = useState(false);
  const [scanNote, setScanNote] = useState<string | null>(null);
  // Groups start collapsed - the point of grouping is to stop one prolific company flooding
  // the list, so expanding is opt-in per company.
  const [expandedGroups, setExpandedGroups] = useState<ReadonlySet<string>>(new Set());

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
    setMinSalaryOn(true);
    setExpandedGroups(new Set());
    setBucket("all");
  }, [activeTab]);

  // Null when the toggle is off - the typed value stays in the box, it just isn't applied.
  const effectiveMinSalary = minSalaryOn ? minSalary : null;

  const sortedJobs = useMemo(() => {
    if (!jobs) return jobs;
    const byBucket =
      bucket === "all"
        ? jobs
        : jobs.filter((j) =>
            bucket === "recommended" ? j.aiRecommended === true : j.aiRecommended === false,
          );
    return filterByMinSalary(sortJobsBy(byBucket, sort), effectiveMinSalary);
  }, [jobs, sort, effectiveMinSalary, bucket]);

  // Counts come from the unfiltered list so the bucket tabs keep showing totals even while a
  // bucket is selected.
  const bucketCounts = useMemo(() => {
    const recommended = jobs?.filter((j) => j.aiRecommended === true).length ?? 0;
    const notRecommended = jobs?.filter((j) => j.aiRecommended === false).length ?? 0;
    return { recommended, notRecommended, unscanned: (jobs?.length ?? 0) - recommended - notRecommended };
  }, [jobs]);

  async function handleRescan() {
    setScanning(true);
    setScanNote(null);
    try {
      const result = await scanMatches({});
      setScanNote(
        result.errorMessage
          ? result.errorMessage
          : `Scanned ${result.scanned} — ${result.recommended} recommended, ${result.notRecommended} not.`,
      );
      await load();
    } catch (err) {
      setScanNote(err instanceof ApiError ? err.message : "Scan failed.");
    } finally {
      setScanning(false);
    }
  }
  const handleSort = useCallback((column: Parameters<typeof nextSortState>[1]) => {
    setSort((current) => nextSortState(current, column));
  }, []);

  useEffect(() => {
    onCountChange?.(sortedJobs?.length ?? 0);
  }, [sortedJobs, onCountChange]);

  // Grouping runs on the already-sorted, already-filtered list, so the active sort still decides
  // both the order of the groups and the order within each one. See utils/group.ts.
  const groups = useMemo(
    () => (groupByCompanyOn && sortedJobs ? groupByCompany(sortedJobs) : null),
    [groupByCompanyOn, sortedJobs],
  );

  /**
   * Single entry point for every edit to the min-salary box: strip to digits, store the number,
   * and remember where the caret should land once the formatted value is re-rendered.
   */
  function commitMinSalary(rawValue: string, caret: number) {
    const digitsBeforeCaret = rawValue.slice(0, caret).replace(/\D/g, "").length;
    const digits = rawValue.replace(/\D/g, "");
    const next = digits === "" ? null : Number(digits);
    setMinSalary(next);
    minSalaryCaretRef.current = caretAfterDigits(groupDigits(next), digitsBeforeCaret);
  }

  function handleMinSalaryKeyDown(e: KeyboardEvent<HTMLInputElement>) {
    const el = e.currentTarget;
    if (e.key !== "Backspace" || el.selectionStart !== el.selectionEnd) {
      return;
    }
    const caret = el.selectionStart ?? 0;
    if (caret === 0 || el.value[caret - 1] !== ",") {
      return;
    }
    // Backspacing a separator would look like nothing happened - the comma is derived, so it
    // just gets re-inserted. Delete the digit in front of it instead, which is what was meant.
    e.preventDefault();
    commitMinSalary(el.value.slice(0, caret - 2) + el.value.slice(caret), caret - 2);
  }

  // Restore the caret after React rewrites the input's value with the grouped version.
  useLayoutEffect(() => {
    const el = minSalaryInputRef.current;
    const caret = minSalaryCaretRef.current;
    if (el && caret != null) {
      el.setSelectionRange(caret, caret);
      minSalaryCaretRef.current = null;
    }
  });

  const toggleGroup = useCallback((key: string) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev);
      if (!next.delete(key)) {
        next.add(key);
      }
      return next;
    });
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

      {/* The AI buckets. Shown only when something has actually been scanned, so a LinkedIn-only
          workflow (which can never be scanned) never sees a control that would do nothing. */}
      {(bucketCounts.recommended > 0 || bucketCounts.notRecommended > 0) && (
        <div className="bucket-bar" role="tablist" aria-label="AI match buckets">
          <button
            type="button"
            role="tab"
            aria-selected={bucket === "all"}
            className={bucket === "all" ? "bucket-tab active" : "bucket-tab"}
            onClick={() => setBucket("all")}
          >
            All <span className="bucket-count">{jobs?.length ?? 0}</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={bucket === "recommended"}
            className={bucket === "recommended" ? "bucket-tab active recommended" : "bucket-tab recommended"}
            onClick={() => setBucket("recommended")}
          >
            Recommended match <span className="bucket-count">{bucketCounts.recommended}</span>
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={bucket === "not_recommended"}
            className={bucket === "not_recommended" ? "bucket-tab active not-recommended" : "bucket-tab not-recommended"}
            onClick={() => setBucket("not_recommended")}
          >
            Not recommended <span className="bucket-count">{bucketCounts.notRecommended}</span>
          </button>
          {bucketCounts.unscanned > 0 && (
            <span className="bucket-unscanned">{bucketCounts.unscanned} not scanned</span>
          )}
          <button type="button" className="bucket-rescan" onClick={handleRescan} disabled={scanning}>
            {scanning ? "Scanning…" : "Re-scan"}
          </button>
        </div>
      )}

      {scanNote && <p className="scan-note">{scanNote}</p>}

      {/* One horizontal toolbar rather than three stacked rows - the vertical space it saves
          goes to the table, which is what the tab is actually for. */}
      <div className="table-controls">
        <div className={minSalaryOn ? "field-row min-salary-row" : "field-row min-salary-row off"}>
          {/*
            type="text", not "number": a number input requires its value to parse as a plain
            number, so it can never display the thousands separators - and setSelectionRange,
            which the caret restore depends on, is not allowed on number inputs either.
          */}
          <input
            id="jp-min-salary"
            ref={minSalaryInputRef}
            aria-label="Minimum salary"
            type="text"
            inputMode="numeric"
            autoComplete="off"
            placeholder="No minimum"
            value={groupDigits(minSalary)}
            onKeyDown={handleMinSalaryKeyDown}
            onChange={(e) => commitMinSalary(e.target.value, e.target.selectionStart ?? e.target.value.length)}
          />
          {/* The checkbox sits under the number and doubles as the field's label. */}
          <span className="checkbox-row min-salary-label">
            <input
              id="jp-min-salary-on"
              type="checkbox"
              checked={minSalaryOn}
              onChange={(e) => setMinSalaryOn(e.target.checked)}
            />
            <label htmlFor="jp-min-salary-on">Min salary</label>
          </span>
        </div>

        <div className="field-row checkbox-row">
          <input
            id="jp-group-company"
            type="checkbox"
            checked={groupByCompanyOn}
            onChange={(e) => setGroupByCompanyOn(e.target.checked)}
          />
          <label htmlFor="jp-group-company">Group by company</label>
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

        <div className="field-row checkbox-row">
          <InfoTip label="About sorting and salary sources">
            {sort.column === "default"
              ? "Sorted by salary, then most recent. Click a column to sort by it instead."
              : "Click a column to change sort, or click it again to reverse."}{" "}
            The min-salary box never hides jobs whose salary is unknown. Salaries from LCA disclosure
            data show the employer entity they were matched to; a leading ≈ marks an approximate match.
          </InfoTip>
        </div>
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
        effectiveMinSalary != null && (
          <p className="hint">
            No jobs at or above {formatUsd(effectiveMinSalary)}. Lower the minimum, or switch it off.
          </p>
        )}

      {sortedJobs !== null && sortedJobs.length > 0 && (
        <>
          <div className="job-table-scroll">
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
              {groups
                ? groups.map((group) =>
                    // A company with a single job is just that job - grouping never buries a
                    // one-off behind an expander.
                    group.jobs.length === 1 ? (
                      <JobRow
                        key={group.jobs[0].jobId}
                        job={group.jobs[0]}
                        tab={activeTab}
                        onChanged={handleChanged}
                        onError={setActionError}
                      />
                    ) : (
                      <Fragment key={group.key}>
                        <CompanyGroupRow
                          group={group}
                          expanded={expandedGroups.has(group.key)}
                          onToggle={toggleGroup}
                        />
                        {expandedGroups.has(group.key) &&
                          group.jobs.map((job) => (
                            <JobRow
                              key={job.jobId}
                              job={job}
                              tab={activeTab}
                              onChanged={handleChanged}
                              onError={setActionError}
                              grouped
                            />
                          ))}
                      </Fragment>
                    ),
                  )
                : sortedJobs.map((job) => (
                    <JobRow
                      key={job.jobId}
                      job={job}
                      tab={activeTab}
                      onChanged={handleChanged}
                      onError={setActionError}
                    />
                  ))}
            </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}
