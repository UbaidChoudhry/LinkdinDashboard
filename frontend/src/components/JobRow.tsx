import { useState } from "react";
import { ApiError, setJobStatus } from "../api/client";
import type { JobResponse, JobTab } from "../types/api";
import { relativeTime, absoluteTime, looselySameCompany } from "../utils/format";

/** Part 1 never populates salary, so this reliably renders "-" today; kept correct for part 2. */
function formatSalary(min: number | null, max: number | null): string {
  if (min == null && max == null) return "–";
  const fmt = (n: number) => `$${Math.round(n).toLocaleString()}`;
  if (min != null && max != null && min !== max) return `${fmt(min)}–${fmt(max)}`;
  return fmt((max ?? min) as number);
}

/** Human-readable label for the `salarySource` recorded by the backend enrichment step. */
function salarySourceLabel(source: string | null | undefined): string | null {
  switch (source) {
    case "lca":
      return "LCA disclosure data";
    case "adzuna":
      return "Adzuna estimate";
    case "h1bapi":
      return "H-1B data";
    default:
      return null;
  }
}

interface JobRowProps {
  job: JobResponse;
  tab: JobTab;
  onChanged: (updated: JobResponse) => void;
  onError: (message: string) => void;
  /** True when this row sits inside an expanded company group, which indents it. */
  grouped?: boolean;
}

export function JobRow({ job, tab, onChanged, onError, grouped = false }: JobRowProps) {
  const [busy, setBusy] = useState(false);
  const sourceLabel = salarySourceLabel(job.salarySource);

  // For LCA salaries, show the legal entity the match landed on. Flag it when the
  // matched entity doesn't loosely read as the same company as the posting.
  const lcaEmployer = job.salarySource === "lca" ? job.salarySourceDetail ?? null : null;
  const lcaIsFuzzy = lcaEmployer != null && !looselySameCompany(lcaEmployer, job.company);

  let salaryCellTitle = sourceLabel ?? undefined;
  if (lcaEmployer) {
    salaryCellTitle = lcaIsFuzzy
      ? `Salary matched from LCA disclosure data filed by "${lcaEmployer}" — the posting lists "${job.company}".`
      : `Salary from LCA disclosure data filed by "${lcaEmployer}".`;
  }

  async function apply(status: "applied" | "not_interested" | null) {
    setBusy(true);
    try {
      const updated = await setJobStatus(job.jobId, status);
      onChanged(updated);
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to update job status.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <tr className={grouped ? "grouped-job" : undefined}>
      <td className="col-title" title={job.title}>
        <a href={job.jobUrl} target="_blank" rel="noopener noreferrer">
          {job.title}
        </a>
        {job.source && job.source !== "linkedin" && (
          <span className="job-source-tag">{job.source}</span>
        )}
        {/* The AI verdict rides under the title rather than in its own column: the reason is a
            full sentence, and a column wide enough for it would squeeze everything else out. */}
        {job.aiRecommended != null && job.aiReason && (
          <span
            className={job.aiRecommended ? "ai-reason recommended" : "ai-reason not-recommended"}
            title={job.aiReason}
          >
            {job.aiRecommended ? "✓" : "✕"} {job.aiReason}
          </span>
        )}
      </td>
      <td className="col-company" title={job.company}>
        {job.company}
      </td>
      <td>{job.location}</td>
      <td title={absoluteTime(job.postedAt)}>{relativeTime(job.postedAt)}</td>
      <td className="col-salary" title={salaryCellTitle}>
        {formatSalary(job.salaryMin, job.salaryMax)}
        {lcaEmployer ? (
          <span
            className={lcaIsFuzzy ? "salary-source lca-employer fuzzy" : "salary-source lca-employer"}
          >
            {lcaIsFuzzy ? "≈ " : ""}LCA · {lcaEmployer}
          </span>
        ) : (
          sourceLabel && <span className="salary-source"> {sourceLabel}</span>
        )}
      </td>
      <td className="col-actions">
        {tab === "search" && (
          <>
            <button type="button" onClick={() => apply("applied")} disabled={busy}>
              Applied
            </button>
            <button type="button" onClick={() => apply("not_interested")} disabled={busy} className="secondary">
              Not interested
            </button>
          </>
        )}
        {(tab === "applied" || tab === "not_interested") && (
          <button type="button" onClick={() => apply(null)} disabled={busy} className="secondary">
            Move back to Search
          </button>
        )}
      </td>
    </tr>
  );
}
