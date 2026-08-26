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

/** Any table column the user can click to sort by. "default" is the salary-bucket view above. */
export type SortColumn = "default" | "title" | "company" | "location" | "postedAt" | "salary";
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

function columnCompare(column: SortColumn, a: JobResponse, b: JobResponse): number {
  switch (column) {
    case "title":
      return compareStrings(a.title, b.title);
    case "company":
      return compareStrings(a.company, b.company);
    case "location":
      return compareStrings(a.location ?? "", b.location ?? "");
    case "postedAt":
      return compareNullableTime(a.postedAt, b.postedAt);
    case "salary":
      return compareNullableNumber(a.salaryMax, b.salaryMax);
    case "default":
      return compareJobs(a, b);
  }
}

export function sortJobsBy(jobs: JobResponse[], sort: SortState): JobResponse[] {
  if (sort.column === "default") {
    return sortJobs(jobs);
  }
  const sign = sort.direction === "asc" ? 1 : -1;
  return [...jobs].sort((a, b) => sign * columnCompare(sort.column, a, b));
}

/** Click behaviour shared by every sortable header: first click sorts, second click reverses. */
export function nextSortState(current: SortState, column: SortColumn): SortState {
  if (current.column === column) {
    return { column, direction: current.direction === "asc" ? "desc" : "asc" };
  }
  return { column, direction: naturalDirection(column) };
}
