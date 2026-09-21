import { useState } from "react";
import { ApiError, applicationLogUrl, getApplyReport } from "../api/client";
import type { ApplyBatchResponse, JobApplicationResponse } from "../types/api";

// Shared by the Results tab's "Apply with Claude" (ApplyControls) and the Apply tab
// (UrlApplyPanel): both start the same kind of batch and watch it the same way.

// Same labels JobRow uses for the per-row application badge, reused here for the details list.
const APPLICATION_STATUS_LABELS: Record<string, string> = {
  needs_review: "Needs review",
  submitted: "Submitted",
  failed: "Apply failed",
  skipped: "Apply manually",
  filling: "Applying…",
  queued: "Queued",
};

function summaryLine(batch: ApplyBatchResponse): string {
  return (
    `Done: ${batch.needsReview} need review, ${batch.submitted} submitted, ` +
    `${batch.skipped} skipped (LinkedIn: apply manually), ${batch.failed} failed · ` +
    `$${batch.costUsd.toFixed(2)}`
  );
}

/**
 * One line for a batch, meant to sit in a wrapping flex row: while it runs, progress plus every
 * job being filled right now (a batch fills several at once) with Claude's last action, and a
 * Cancel button; once it stops, the outcome summary.
 */
export function ApplyBatchStatus({ batch, onCancel }: { batch: ApplyBatchResponse; onCancel: () => void }) {
  if (batch.status === "running") {
    const filling = batch.jobs.filter((j) => j.status === "filling");
    const next = filling.length === 0 ? batch.jobs.find((j) => j.status === "queued") : undefined;
    return (
      <span className="apply-note apply-progress">
        <span>
          Applying {batch.done}/{batch.total}
          {filling.length > 1 ? ` · ${filling.length} at once` : ""}
          {next ? ` · ${next.title} — ${next.company}…` : filling.length === 0 ? "…" : ""}
          {filling.map((job) => (
            <span key={job.id} className="apply-filling-job">
              {job.title} — {job.company}…
              {job.lastActivity && <span className="apply-activity">Claude: {job.lastActivity}</span>}
            </span>
          ))}
        </span>
        <button type="button" className="secondary" onClick={onCancel}>
          Cancel
        </button>
      </span>
    );
  }
  return (
    <span className="apply-note">
      {summaryLine(batch)}
      {batch.newQuestions > 0 && (
        <>
          {" · "}
          <strong>{batch.newQuestions}</strong> new question
          {batch.newQuestions === 1 ? "" : "s"} → Resumes tab
        </>
      )}
    </span>
  );
}

/** Every job in the batch with its notes, transcript link and resume command, plus the end-of-run report. */
export function ApplyBatchDetails({ batch }: { batch: ApplyBatchResponse }) {
  return (
    <div className="apply-details">
      {batch.hasReport && <RunReport batchId={batch.id} />}
      {batch.jobs.map((job) => (
        <DetailsRow key={job.id} job={job} batchId={batch.id} />
      ))}
    </div>
  );
}

function CopyResumeButton({ sessionId }: { sessionId: string }) {
  const [copied, setCopied] = useState(false);

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(`claude --resume ${sessionId} --chrome`);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access can be denied/unavailable - nothing else to do here.
    }
  }

  return (
    <button type="button" className="secondary" onClick={handleCopy}>
      {copied ? "Copied" : "Copy"}
    </button>
  );
}

function DetailsRow({ job, batchId }: { job: JobApplicationResponse; batchId: number }) {
  const showActivity = (job.status === "filling" || job.status === "failed") && job.lastActivity;
  return (
    <div className="apply-details-row">
      <span className={`application-badge ${job.status}`}>
        {APPLICATION_STATUS_LABELS[job.status] ?? job.status}
      </span>{" "}
      <strong>
        {job.title} — {job.company}
      </strong>
      {job.notes && <div>{job.notes}</div>}
      {showActivity && <div className="apply-activity">Claude: {job.lastActivity}</div>}
      {job.hasLog && (
        <div>
          <a href={applicationLogUrl(batchId, job.id)} target="_blank" rel="noreferrer">
            View log
          </a>
        </div>
      )}
      {job.sessionId && (
        <div>
          Resume in terminal: <code>claude --resume {job.sessionId} --chrome</code>{" "}
          <CopyResumeButton sessionId={job.sessionId} />
        </div>
      )}
    </div>
  );
}

function RunReport({ batchId }: { batchId: number }) {
  const [visible, setVisible] = useState(false);
  const [report, setReport] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleShow() {
    setVisible(true);
    if (report != null) return;
    setLoading(true);
    setError(null);
    try {
      setReport(await getApplyReport(batchId));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load the run report.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="apply-report-section">
      {!visible ? (
        <button type="button" className="secondary" onClick={handleShow}>
          Show run report
        </button>
      ) : (
        <button type="button" className="secondary" onClick={() => setVisible(false)}>
          Hide
        </button>
      )}
      {visible && loading && <p className="muted">Loading…</p>}
      {visible && error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {visible && report != null && <pre className="apply-report">{report}</pre>}
    </div>
  );
}
