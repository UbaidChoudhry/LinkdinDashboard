import type { JobResponse } from "../types/api";

/** One company's jobs, as shown by the "Group by company" toggle in the results table. */
export interface CompanyGroup {
  /** Case-insensitive grouping key ("acme corp"). */
  key: string;
  /** Display name — the casing of the first job seen for this company. */
  company: string;
  jobs: JobResponse[];
}

/**
 * Buckets an ALREADY-SORTED job list by company, preserving input order both between groups
 * (a group takes the position of its first job) and within each group.
 *
 * That ordering rule is the whole trick: it means grouping doesn't need to know anything about
 * the active sort. Sorting by salary desc puts the company with the highest-paying job first;
 * sorting by company asc puts them alphabetically; and inside every group the jobs stay in
 * whatever order the sort produced. Pass sorted-and-filtered jobs in, and the current sort keeps
 * working unchanged.
 *
 * Grouping is case-insensitive so "ACME Corp" and "Acme Corp" don't split into two groups, but
 * matching is otherwise exact — "Meta" and "Meta Platforms" are different companies, the same
 * rule the company blocklist follows.
 */
export function groupByCompany(jobs: JobResponse[]): CompanyGroup[] {
  const groups = new Map<string, CompanyGroup>();
  for (const job of jobs) {
    const company = job.company ?? "";
    const key = company.trim().toLowerCase();
    const existing = groups.get(key);
    if (existing) {
      existing.jobs.push(job);
    } else {
      groups.set(key, { key, company, jobs: [job] });
    }
  }
  return [...groups.values()];
}

/** The highest known salary in a group, or null when no job in it has one. */
export function groupTopSalary(group: CompanyGroup): number | null {
  let top: number | null = null;
  for (const job of group.jobs) {
    if (job.salaryMax != null && (top == null || job.salaryMax > top)) {
      top = job.salaryMax;
    }
  }
  return top;
}

/** The most recent postedAt in a group, or null when none of its jobs carry one. */
export function groupNewestPostedAt(group: CompanyGroup): string | null {
  let newest: string | null = null;
  for (const job of group.jobs) {
    if (job.postedAt && (newest == null || job.postedAt > newest)) {
      newest = job.postedAt;
    }
  }
  return newest;
}

/** Distinct non-empty locations in a group — a high count is the relay-poster smell. */
export function groupLocations(group: CompanyGroup): string[] {
  const seen = new Set<string>();
  for (const job of group.jobs) {
    const loc = (job.location ?? "").trim();
    if (loc) seen.add(loc);
  }
  return [...seen];
}
