import { Fragment, useCallback, useEffect, useRef, useState } from "react";
import {
  ApiError,
  applicationLogUrl,
  cancelApplyBatch,
  getApplyBatch,
  getApplyReport,
  getCurrentApplyBatch,
  startApplications,
} from "../api/client";
import type { ApplyBatchResponse, JobApplicationResponse } from "../types/api";

interface ApplyControlsProps {
  /** Job ids eligible for this run: recommended, untriaged, non-LinkedIn rows on screen. */
  eligibleJobIds: number[];
  /** How many recommended rows on screen are LinkedIn (skipped, apply manually). */
  linkedinCount: number;
  /** Called once a batch finishes, so the caller can reload its job list. */
  onFinished: () => void;
}

const POLL_MS = 2000;

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
      {job.status === "needs_review" && <div className="apply-note">Tab left open for you to finish.</div>}
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

export function ApplyControls({ eligibleJobIds, linkedinCount, onFinished }: ApplyControlsProps) {
  const [submitChecked, setSubmitChecked] = useState(false);
  const [batch, setBatch] = useState<ApplyBatchResponse | null>(null);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showDetails, setShowDetails] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // Guards against onFinished() firing twice for the same batch (e.g. a poll tick landing right
  // after cancel/finish has already been handled).
  const finishedBatchIdRef = useRef<number | null>(null);

  const stopPolling = useCallback(() => {
    if (pollRef.current != null) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
  }, []);

  const handleFinished = useCallback(
    (finished: ApplyBatchResponse) => {
      stopPolling();
      setBatch(finished);
      if (finishedBatchIdRef.current !== finished.id) {
        finishedBatchIdRef.current = finished.id;
        onFinished();
      }
    },
    [onFinished, stopPolling],
  );

  const poll = useCallback(
    (batchId: number) => {
      stopPolling();
      pollRef.current = setInterval(async () => {
        try {
          const latest = await getApplyBatch(batchId);
          setBatch(latest);
          if (latest.status !== "running") {
            handleFinished(latest);
          }
        } catch {
          // A transient poll failure isn't worth surfacing as an error - the next tick retries.
        }
      }, POLL_MS);
    },
    [handleFinished, stopPolling],
  );

  // Re-attach to a batch that's still running after a page reload.
  useEffect(() => {
    (async () => {
      try {
        const current = await getCurrentApplyBatch();
        if (current) {
          setBatch(current);
          if (current.status === "running") {
            poll(current.id);
          }
        }
      } catch {
        // No current batch, or the endpoint isn't reachable yet - nothing to re-attach to.
      }
    })();
    return stopPolling;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleApply() {
    setStarting(true);
    setError(null);
    try {
      const { batchId } = await startApplications({ jobIds: eligibleJobIds, submit: submitChecked });
      finishedBatchIdRef.current = null;
      const fresh = await getApplyBatch(batchId);
      setBatch(fresh);
      if (fresh.status === "running") {
        poll(batchId);
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to start applying.");
    } finally {
      setStarting(false);
    }
  }

  async function handleCancel() {
    if (!batch) return;
    setError(null);
    try {
      await cancelApplyBatch(batch.id);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to cancel.");
    }
  }

  const running = batch?.status === "running";
  const currentJob = running ? batch?.jobs.find((j) => j.status === "filling" || j.status === "queued") : undefined;
  const fillingJob = running ? batch?.jobs.find((j) => j.status === "filling") : undefined;

  return (
    <Fragment>
      <div className="apply-controls">
        <button type="button" onClick={handleApply} disabled={eligibleJobIds.length === 0 || running || starting}>
          {starting ? "Starting…" : `Apply with Claude (${eligibleJobIds.length})`}
        </button>

        <span className="checkbox-row">
          <input
            id="apply-submit"
            type="checkbox"
            checked={submitChecked}
            onChange={(e) => setSubmitChecked(e.target.checked)}
            disabled={running}
          />
          <label htmlFor="apply-submit">Submit applications</label>
        </span>
        <span className="apply-note">
          Unchecked: Claude fills each form and stops before Submit so you can review
        </span>

        {linkedinCount > 0 && (
          <span className="apply-note">
            {linkedinCount} LinkedIn job{linkedinCount === 1 ? "" : "s"} will be skipped — apply to
            those manually.
          </span>
        )}

        {running && batch && (
          <span className="apply-note apply-progress">
            <span>
              Applying {batch.done}/{batch.total}
              {currentJob ? ` · ${currentJob.title} — ${currentJob.company}…` : "…"}
              {fillingJob?.lastActivity && (
                <span className="apply-activity">Claude: {fillingJob.lastActivity}</span>
              )}
            </span>
            <button type="button" className="secondary" onClick={handleCancel}>
              Cancel
            </button>
          </span>
        )}

        {!running && batch && batch.status !== "running" && (
          <span className="apply-note">
            {summaryLine(batch)}
            {batch.newQuestions > 0 && (
              <>
                {" · "}
                <strong>{batch.newQuestions}</strong> new question
                {batch.newQuestions === 1 ? "" : "s"} need your answer → Resumes tab, Applicant
                profile
              </>
            )}
          </span>
        )}

        {batch && (
          <button type="button" className="secondary" onClick={() => setShowDetails((v) => !v)}>
            {showDetails ? "Details ▴" : "Details ▾"}
          </button>
        )}

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
      </div>

      {batch && showDetails && (
        <div className="apply-details">
          {batch.hasReport && <RunReport batchId={batch.id} />}
          <p className="apply-note">
            Stuck? Open the log, or resume the session in a terminal and ask Claude what blocked
            it — the browser tabs it used are gone once the session ends, but it can reopen them.
          </p>
          {batch.jobs.map((job) => (
            <DetailsRow key={job.id} job={job} batchId={batch.id} />
          ))}
        </div>
      )}
    </Fragment>
  );
}
