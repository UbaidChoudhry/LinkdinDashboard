import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { ApiError, bulkDeleteJobs, bulkSetJobStatus, listJobs, scanMatches } from "../api/client";
import type { JobResponse, JobTab, RunResponse } from "../types/api";
import { DEFAULT_SORT, filterByMinSalary, nextSortState, sortJobsBy, type SortState } from "../utils/sort";
import { groupByCompany, type CompanyGroup } from "../utils/group";
import { isRunInFlight } from "../utils/runStatus";
import { ApplyControls } from "./ApplyControls";
import { JobRow } from "./JobRow";
import { CompanyGroupRow } from "./CompanyGroupRow";
import { PlainHeader, SortableHeader } from "./SortableHeader";
import { InfoTip } from "./InfoTip";

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
  // (a row with no description - e.g. a LinkedIn row whose detail fetch hasn't happened yet).
  type Bucket = "all" | "recommended" | "not_recommended" | "not_scanned";
  const [bucket, setBucket] = useState<Bucket>("all");
  const [scanning, setScanning] = useState(false);
  const [scanNote, setScanNote] = useState<string | null>(null);
  // Groups start collapsed - the point of grouping is to stop one prolific company flooding
  // the list, so expanding is opt-in per company.
  const [expandedGroups, setExpandedGroups] = useState<ReadonlySet<string>>(new Set());
  // Multi-select for the bulk "Not interested" / "Delete" actions below the table. Cleared
  // whenever the underlying list changes (tab switch, reload) so it never points at rows that
  // are no longer on screen.
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<number>>(new Set());
  const [bulkBusy, setBulkBusy] = useState(false);
  const [deleteConfirming, setDeleteConfirming] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    setJobs(null);
    setSelectedIds(new Set());
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
    setSelectedIds(new Set());
    setDeleteConfirming(false);
  }, [activeTab]);

  // Null when the toggle is off - the typed value stays in the box, it just isn't applied.
  const effectiveMinSalary = minSalaryOn ? minSalary : null;

  const sortedJobs = useMemo(() => {
    if (!jobs) return jobs;
    const byBucket =
      bucket === "all"
        ? jobs
        : jobs.filter((j) => {
            if (bucket === "recommended") return j.aiRecommended === true;
            if (bucket === "not_recommended") return j.aiRecommended === false;
            return j.aiRecommended == null;
          });
    return filterByMinSalary(sortJobsBy(byBucket, sort), effectiveMinSalary);
  }, [jobs, sort, effectiveMinSalary, bucket]);

  // Counts come from the unfiltered list so the bucket tabs keep showing totals even while a
  // bucket is selected.
  const bucketCounts = useMemo(() => {
    const recommended = jobs?.filter((j) => j.aiRecommended === true).length ?? 0;
    const notRecommended = jobs?.filter((j) => j.aiRecommended === false).length ?? 0;
    const unscanned = jobs?.filter((j) => j.aiRecommended == null).length ?? 0;
    return { recommended, notRecommended, unscanned };
  }, [jobs]);

  // Apply with Claude only ever acts on the Untriaged tab's recommended, non-LinkedIn rows -
  // LinkedIn postings are listed as "apply manually" instead of being sent to the CLI.
  const recommendedJobs = useMemo(
    () => (activeTab === "search" ? jobs?.filter((j) => j.aiRecommended === true) ?? [] : []),
    [activeTab, jobs],
  );
  const eligibleJobIds = useMemo(
    () =>
      recommendedJobs.filter((j) => j.source !== "linkedin" || j.applyDomain != null).map((j) => j.jobId),
    [recommendedJobs],
  );
  const linkedinCount = useMemo(
    () => recommendedJobs.filter((j) => j.source === "linkedin" && j.applyDomain == null).length,
    [recommendedJobs],
  );

  async function handleRescan() {
    setScanning(true);
    setScanNote(null);
    try {
      const result = await scanMatches({});
      // The scan only reads rows that HAVE a description and skips verdicts already cached, so
      // "Scanned 0" on a list full of "Not scanned" rows is not a failure - it means those rows
      // have nothing to scan yet. Say so, and point at the action that actually fixes it.
      const unreadable =
        jobs?.filter((j) => j.aiRecommended == null && j.source === "linkedin" && j.detailStatus !== "ok").length ?? 0;
      let note = result.errorMessage
        ? result.errorMessage
        : `Scanned ${result.scanned} — ${result.recommended} recommended, ${result.notRecommended} not.`;
      if (!result.errorMessage && result.scanned === 0 && unreadable > 0) {
        note =
          `Nothing new to scan: ${unreadable} LinkedIn job${unreadable === 1 ? "" : "s"} shown here ` +
          "have no description yet (the run's description fetch was blocked or cut short), and the AI " +
          "cannot score a job without one. Use Retry on the run panel in the Search tab to fetch them and re-scan.";
      }
      setScanNote(note);
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
    setSelectedIds((prev) => {
      if (!prev.has(updated.jobId)) return prev;
      const next = new Set(prev);
      next.delete(updated.jobId);
      return next;
    });
  }

  // ---- multi-select ---------------------------------------------------------------------

  // Selection is tracked against sortedJobs (the visible, filtered/sorted set), never the raw
  // grouping - "select all" and the bulk actions always act on real job ids, independent of
  // whether "Group by company" happens to be collapsing some of them right now.
  const selectableIds = useMemo(() => sortedJobs?.map((j) => j.jobId) ?? [], [sortedJobs]);
  const allSelected = selectableIds.length > 0 && selectableIds.every((id) => selectedIds.has(id));
  const someSelected = selectedIds.size > 0 && !allSelected;
  const selectAllRef = useRef<HTMLInputElement>(null);

  useLayoutEffect(() => {
    if (selectAllRef.current) {
      selectAllRef.current.indeterminate = someSelected;
    }
  }, [someSelected]);

  // The bar stays on screen at 0 selected (greyed out) rather than disappearing, so this is
  // derived rather than stored: if the selection is cleared out from under an open delete
  // confirmation, it reads as closed rather than showing "Delete 0 jobs permanently?".
  const confirmingDelete = deleteConfirming && selectedIds.size > 0;

  const toggleSelect = useCallback((jobId: number) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (!next.delete(jobId)) {
        next.add(jobId);
      }
      return next;
    });
  }, []);

  function toggleSelectAll() {
    setSelectedIds(allSelected ? new Set() : new Set(selectableIds));
  }

  const toggleSelectGroup = useCallback((group: CompanyGroup, select: boolean) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      for (const job of group.jobs) {
        if (select) {
          next.add(job.jobId);
        } else {
          next.delete(job.jobId);
        }
      }
      return next;
    });
  }, []);

  async function handleBulkNotInterested() {
    setBulkBusy(true);
    setActionError(null);
    try {
      await bulkSetJobStatus([...selectedIds], "not_interested");
      await load();
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : "Failed to update the selected jobs.");
    } finally {
      setBulkBusy(false);
    }
  }

  async function handleBulkDelete() {
    setBulkBusy(true);
    setActionError(null);
    try {
      await bulkDeleteJobs([...selectedIds]);
      setDeleteConfirming(false);
      await load();
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : "Failed to delete the selected jobs.");
    } finally {
      setBulkBusy(false);
    }
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
      if (isRunInFlight(latestRun.status)) {
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
            <button
              type="button"
              role="tab"
              aria-selected={bucket === "not_scanned"}
              className={bucket === "not_scanned" ? "bucket-tab active" : "bucket-tab"}
              onClick={() => setBucket("not_scanned")}
            >
              Not scanned <span className="bucket-count">{bucketCounts.unscanned}</span>
            </button>
          )}
          <button type="button" className="bucket-rescan" onClick={handleRescan} disabled={scanning}>
            {scanning ? "Scanning…" : "Re-scan"}
          </button>
          <ApplyControls eligibleJobIds={eligibleJobIds} linkedinCount={linkedinCount} onFinished={load} />
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

      {/* Bulk actions for the multi-select checkboxes in the table below. Always shown (rather
          than popping in on first selection) so it doesn't shift the layout underneath it -
          greyed out via disabled buttons when nothing is selected. Delete is permanent, so it
          gets the same two-step confirm as the Data tab's "Clear job data". */}
      <div
        className={selectedIds.size === 0 ? "selection-bar empty" : "selection-bar"}
        role="toolbar"
        aria-label="Bulk actions for selected jobs"
      >
        <span className="selection-count">{selectedIds.size} selected</span>
        <button
          type="button"
          className="secondary"
          onClick={() => setSelectedIds(new Set())}
          disabled={bulkBusy || selectedIds.size === 0}
        >
          Clear selection
        </button>
        <button type="button" onClick={handleBulkNotInterested} disabled={bulkBusy || selectedIds.size === 0}>
          Mark as Not interested
        </button>
        {!confirmingDelete ? (
          <button
            type="button"
            className="danger"
            onClick={() => setDeleteConfirming(true)}
            disabled={bulkBusy || selectedIds.size === 0}
          >
            Delete selected...
          </button>
        ) : (
          <span className="selection-delete-confirm">
            Delete {selectedIds.size} job{selectedIds.size === 1 ? "" : "s"} permanently?
            <button type="button" className="danger" onClick={handleBulkDelete} disabled={bulkBusy}>
              {bulkBusy ? "Deleting…" : "Yes, delete"}
            </button>
            <button
              type="button"
              className="secondary"
              onClick={() => setDeleteConfirming(false)}
              disabled={bulkBusy}
            >
              Cancel
            </button>
          </span>
        )}
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
                <th className="col-select">
                  <input
                    ref={selectAllRef}
                    type="checkbox"
                    aria-label="Select all"
                    checked={allSelected}
                    onChange={toggleSelectAll}
                  />
                </th>
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
                        selected={selectedIds.has(group.jobs[0].jobId)}
                        onToggleSelect={toggleSelect}
                      />
                    ) : (
                      <Fragment key={group.key}>
                        <CompanyGroupRow
                          group={group}
                          expanded={expandedGroups.has(group.key)}
                          onToggle={toggleGroup}
                          selectedCount={group.jobs.filter((j) => selectedIds.has(j.jobId)).length}
                          onToggleSelectGroup={toggleSelectGroup}
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
                              selected={selectedIds.has(job.jobId)}
                              onToggleSelect={toggleSelect}
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
                      selected={selectedIds.has(job.jobId)}
                      onToggleSelect={toggleSelect}
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
