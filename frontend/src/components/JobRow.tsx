import { useState } from "react";
import { ApiError, setJobStatus } from "../api/client";
import type { JobResponse, JobTab } from "../types/api";
import { relativeTime, absoluteTime } from "../utils/format";

interface JobRowProps {
  job: JobResponse;
  tab: JobTab;
  onChanged: (updated: JobResponse) => void;
  onError: (message: string) => void;
}

export function JobRow({ job, tab, onChanged, onError }: JobRowProps) {
  const [busy, setBusy] = useState(false);

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
