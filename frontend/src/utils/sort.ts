import type { JobResponse } from "../types/api";

/**
 * Mirrors the backend's placeholder sort (see JobSortOrder.java): jobs with a non-null
 * salaryMax sort first, descending by salaryMax; jobs without one sort after, descending by
 * postedAt. In part 1 salaryMax is always null, so every row falls into the second bucket -
 * that's expected. Kept correct here so it "just works" once salary data arrives.
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
