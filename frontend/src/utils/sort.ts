import type { JobResponse } from "../types/api";

/**
 * Mirrors the backend's placeholder sort (see JobSortOrder.java): jobs with a non-null
 * salaryMax sort first, descending by salaryMax; jobs without one sort after, descending by
 * postedAt. In part 1 salaryMax is always null, so every row falls into the second bucket -
 * that's expected. Kept correct here so it "just works" once salary data arrives.
 *
 * This is the default view (column = "default") and is unaffected by direction toggling -
 * it's a fixed, deliberately designed order, not a plain ascending/descending column.
 */
export function compareJobs(a: JobResponse, b: JobResponse): number {
  const aHasSalary = a.salaryMax != null;
  const bHasSalary = b.salaryMax != null;

  if (aHasSalary && bHasSalary) {
    return (b.salaryMax as number) - (a.salaryMax as number);
  }
  if (aHasSalary !== bHasSalary) {
    return aHasSalary ? -1 : 1;
  }

  const aPosted = a.postedAt ? new Date(a.postedAt).getTime() : 0;
  const bPosted = b.postedAt ? new Date(b.postedAt).getTime() : 0;
  return bPosted - aPosted;
}

export function sortJobs(jobs: JobResponse[]): JobResponse[] {
  return [...jobs].sort(compareJobs);
}

/**
 * Hides a job only when it has a *known* salary below `min` - i.e. drop it when
 * `salaryMax != null && salaryMax < min`. Jobs with an unknown salary (salaryMax == null)
 * are always kept; the comparator already sinks them to the bottom of the list. `min == null`
 * (empty input) means no filtering. Applied after sorting so the order is preserved.
 */
export function filterByMinSalary(jobs: JobResponse[], min: number | null): JobResponse[] {
  if (min == null) return jobs;
  return jobs.filter((j) => j.salaryMax == null || j.salaryMax >= min);
}

/**
 * The Results tab's Company and Location boxes: a case-insensitive substring match on each. A
 * blank box matches everything; with both filled, a job must match both. Order is preserved.
 */
export function filterBySearch(jobs: JobResponse[], company: string, location: string): JobResponse[] {
  const companyQuery = company.trim().toLowerCase();
  const locationQuery = location.trim().toLowerCase();
  if (companyQuery === "" && locationQuery === "") return jobs;
  return jobs.filter(
    (j) =>
      j.company.toLowerCase().includes(companyQuery) &&
      (j.location ?? "").toLowerCase().includes(locationQuery),
  );
}

/** The Remote dropdown: every row, remote only, not remote only, or rows not decided yet. */
export type RemoteFilter = "all" | "yes" | "no" | "unknown";

export function filterByRemote(jobs: JobResponse[], filter: RemoteFilter): JobResponse[] {
  if (filter === "all") return jobs;
  return jobs.filter((j) => (filter === "unknown" ? j.remote == null : j.remote === (filter === "yes")));
}

/** Any table column the user can click to sort by. "default" is the salary-bucket view above. */
export type SortColumn =
  | "default"
  | "title"
  | "company"
  | "location"
  | "remote"
  | "postedAt"
  | "salary"
  | "match";
export type SortDirection = "asc" | "desc";

export interface SortState {
  column: SortColumn;
  direction: SortDirection;
}

export const DEFAULT_SORT: SortState = { column: "default", direction: "desc" };

/** Natural default direction when a column is first clicked - newest/highest first. */
export function naturalDirection(column: SortColumn): SortDirection {
  return column === "title" || column === "company" || column === "location" ? "asc" : "desc";
}

function compareStrings(a: string, b: string): number {
  return a.localeCompare(b, undefined, { sensitivity: "base" });
}

function compareNullableTime(a: string | null, b: string | null): number {
  // Rows with no timestamp sort last regardless of direction - "unknown" isn't "oldest".
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  return new Date(a).getTime() - new Date(b).getTime();
}

function compareNullableNumber(a: number | null, b: number | null): number {
  // Rows with no salary sort last regardless of direction, same reasoning as above.
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  return a - b;
}

/**
 * The Match column's sort value: the company-link finder's confidence; 100 for a link read
 * straight off LinkedIn's Apply button (before that step was removed); null - last - when never
 * searched.
 */
function matchConfidence(job: JobResponse): number | null {
  if (job.companyLinkConfidence != null) return job.companyLinkConfidence;
  return job.source === "linkedin" && job.applyDomain ? 100 : null;
}

function columnCompare(column: SortColumn, a: JobResponse, b: JobResponse): number {
  switch (column) {
    case "title":
      return compareStrings(a.title, b.title);
    case "company":
      return compareStrings(a.company, b.company);
    case "location":
      return compareStrings(a.location ?? "", b.location ?? "");
    case "remote":
      // Yes above No; undecided rows last either way, like a missing salary.
      return compareNullableNumber(a.remote == null ? null : Number(a.remote), b.remote == null ? null : Number(b.remote));
    case "postedAt":
      return compareNullableTime(a.postedAt, b.postedAt);
    case "salary":
      return compareNullableNumber(a.salaryMax, b.salaryMax);
    case "match":
      return compareNullableNumber(matchConfidence(a), matchConfidence(b));
    case "default":
      return compareJobs(a, b);
  }
}

/** Whether a row has no value in a sortable column - such rows sort last in both directions. */
function isMissing(column: SortColumn, job: JobResponse): boolean {
  switch (column) {
    case "postedAt":
      return job.postedAt == null;
    case "salary":
      return job.salaryMax == null;
    case "match":
      return matchConfidence(job) == null;
    case "remote":
      return job.remote == null;
    default:
      return false;
  }
}

export function sortJobsBy(jobs: JobResponse[], sort: SortState): JobResponse[] {
  if (sort.column === "default") {
    return sortJobs(jobs);
  }
  const sign = sort.direction === "asc" ? 1 : -1;
  // Missing values are settled before the sign is applied: flipping the whole comparison would
  // flip them to the top too, so the first (descending) click on Salary led with every unknown.
  return [...jobs].sort((a, b) => {
    const aMissing = isMissing(sort.column, a);
    if (aMissing !== isMissing(sort.column, b)) return aMissing ? 1 : -1;
    return sign * columnCompare(sort.column, a, b);
  });
}

/** Click behaviour shared by every sortable header: first click sorts, second click reverses. */
export function nextSortState(current: SortState, column: SortColumn): SortState {
  if (current.column === column) {
    return { column, direction: current.direction === "asc" ? "desc" : "asc" };
  }
  return { column, direction: naturalDirection(column) };
}
