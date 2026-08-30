import { useState } from "react";
import { ApiError, setJobStatus } from "../api/client";
import type { JobResponse, JobTab } from "../types/api";
import { relativeTime, absoluteTime } from "../utils/format";

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
}

export function JobRow({ job, tab, onChanged, onError }: JobRowProps) {
  const [busy, setBusy] = useState(false);
  const sourceLabel = salarySourceLabel(job.salarySource);

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
    <tr>
      <td className="col-title">
        <a href={job.jobUrl} target="_blank" rel="noopener noreferrer">
          {job.title}
        </a>
      </td>
      <td>{job.company}</td>
      <td>{job.location}</td>
      <td title={absoluteTime(job.postedAt)}>{relativeTime(job.postedAt)}</td>
      <td className="col-salary" title={sourceLabel ?? undefined}>
        {formatSalary(job.salaryMin, job.salaryMax)}
        {sourceLabel && <span className="salary-source"> {sourceLabel}</span>}
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
