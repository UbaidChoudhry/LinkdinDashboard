import { useEffect, useState } from "react";
import { ApiError, cancelRun, getCooldown, resumeRun } from "../api/client";
import type { CooldownResponse, RunResponse, ScanProgress } from "../types/api";
import { isRunFinished, isRunInFlight, runStatusInfo } from "../utils/runStatus";

interface RunProgressProps {
  run: RunResponse;
  streamError: string | null;
  onCancelled: () => void;
  /** Called once the backend has accepted a resume of this run, so the shell re-subscribes to it. */
  onResumed: (runId: number) => void;
}

/** Terminal statuses that mean "LinkedIn stopped this run early, and a Retry can finish it". */
const RETRYABLE_STATUSES: ReadonlySet<string> = new Set(["blocked", "capped", "budget_exhausted"]);

/** How often to re-ask the backend about the cooldown while a Retry is waiting on it. */
const COOLDOWN_POLL_MS = 15_000;

export function RunProgress({ run, streamError, onCancelled, onResumed }: RunProgressProps) {
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  const info = runStatusInfo(run.status);
  // Cancel must stay available during the scan phase too, not just while collecting.
  const isRunning = isRunInFlight(run.status);
  const finished = isRunFinished(run);
  const withLinkedIn = (run.sources ?? "linkedin").split(",").includes("linkedin");
  const unfetched = run.unfetchedDescriptions ?? 0;
  // Retry is offered when the run was cut short by LinkedIn, or when it simply left descriptions
  // unread - either way there is work a resume can do that a brand-new run would not.
  const canRetry = finished && (unfetched > 0 || RETRYABLE_STATUSES.has(run.status));

  async function handleCancel() {
    setCancelError(null);
    setCancelling(true);
    try {
      await cancelRun(run.id);
      onCancelled();
    } catch (err) {
      setCancelError(err instanceof ApiError ? err.message : "Failed to cancel run.");
    } finally {
      setCancelling(false);
    }
  }

  return (
    <section className="run-progress" aria-live="polite">
      <h2>Run #{run.id}</h2>

      <div className={`status-banner status-${info.kind}`}>
        <span className="status-label">{info.label}</span>
        <span className="status-message">{info.message}</span>
      </div>

      {run.saturated && (
        <div className="saturation-warning" role="alert">
          <strong>Results were truncated.</strong> This run hit LinkedIn's 1000-result cap for the
          search. Some jobs that matched were never visible to this run and are missing from the
          results - this is a data-loss condition, not a minor limitation.
        </div>
      )}

      <dl className="progress-stats">
        <div>
          <dt>Pages fetched</dt>
          <dd>{run.pagesFetched}</dd>
        </div>
        <div>
          <dt>Requests made</dt>
          <dd>{run.requestsMade}</dd>
        </div>
        <div>
          <dt>Cards seen</dt>
          <dd>{run.cardsSeen}</dd>
        </div>
        <div>
          <dt>New jobs</dt>
          <dd>{run.jobsNew}</dd>
        </div>
        <div>
          <dt>Current shard</dt>
          <dd>{run.currentShard ?? "-"}</dd>
        </div>
        {(run.companiesTotal ?? 0) > 0 && (
          <div>
            <dt>Companies visited</dt>
            <dd>
              {run.companiesDone ?? 0}/{run.companiesTotal}
            </dd>
          </div>
        )}
        {(run.detailsTotal ?? 0) > 0 && (
          <div>
            <dt>Descriptions fetched</dt>
            <dd>
              {run.detailsDone ?? 0}/{run.detailsTotal}
            </dd>
          </div>
        )}
      </dl>

      {run.scan && <ScanPanel scan={run.scan} live={run.status === "scanning" || run.status === "matching"} />}

      {streamError && (
        <p className="form-error" role="alert">
          {streamError}
        </p>
      )}
      {cancelError && (
        <p className="form-error" role="alert">
          {cancelError}
        </p>
      )}

      {isRunning && (
        <button type="button" onClick={handleCancel} disabled={cancelling} className="danger">
          {cancelling ? "Cancelling..." : "Cancel run"}
        </button>
      )}

      {canRetry && (
        <RetryPanel runId={run.id} unfetched={unfetched} watchCooldown={withLinkedIn} onResumed={onResumed} />
      )}
    </section>
  );
}

/**
 * The "Retry" a stopped LinkedIn run was missing. A cooldown is not something the user can
 * clear, so the only useful thing to show is a countdown - and then a button that does the one
 * thing a fresh run would not: finish THIS run's jobs. Resuming fetches the descriptions the run
 * collected but never read and re-runs the AI scan; it does not repeat the search.
 */
function RetryPanel({
  runId,
  unfetched,
  watchCooldown,
  onResumed,
}: {
  runId: number;
  unfetched: number;
  watchCooldown: boolean;
  onResumed: (runId: number) => void;
}) {
  const [cooldown, setCooldown] = useState<CooldownResponse | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [resuming, setResuming] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Ask the backend for the cooldown, and keep asking while it is active: the breaker can
  // re-trip or be extended server-side, and a stale "you can retry now" is worse than none.
  useEffect(() => {
    if (!watchCooldown) return;
    let cancelled = false;
    async function poll() {
      try {
        const c = await getCooldown();
        if (!cancelled) {
          setCooldown(c);
          setRemaining(c.remainingSeconds);
        }
      } catch {
        // Leave the last known state; the button still works and the server re-checks anyway.
      }
    }
    void poll();
    const id = setInterval(() => void poll(), COOLDOWN_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [watchCooldown, runId]);

  // Tick the countdown locally between polls so it reads as a clock, not a stuck number.
  useEffect(() => {
    if (!cooldown?.active) return;
    const id = setInterval(() => setRemaining((s) => Math.max(0, s - 1)), 1000);
    return () => clearInterval(id);
  }, [cooldown]);

  const waiting = watchCooldown && (cooldown?.active ?? false) && remaining > 0;

  async function handleResume() {
    setError(null);
    setResuming(true);
    try {
      await resumeRun(runId);
      onResumed(runId);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to resume run.");
      // A 503 here means the cooldown is still on server-side; refresh the countdown right away.
      if (watchCooldown) {
        getCooldown()
          .then((c) => {
            setCooldown(c);
            setRemaining(c.remainingSeconds);
          })
          .catch(() => {});
      }
    } finally {
      setResuming(false);
    }
  }

  const label = resuming
    ? "Resuming..."
    : waiting
      ? `Retry available in ${formatCountdown(remaining)}`
      : unfetched > 0
        ? `Retry: fetch ${unfetched} description${unfetched === 1 ? "" : "s"} and re-scan`
        : "Retry: re-run the AI scan";

  return (
    <div className="retry-panel">
      <p className="retry-copy">
        {unfetched > 0 ? (
          <>
            <strong>{unfetched}</strong> job{unfetched === 1 ? "" : "s"} from this run{" "}
            {unfetched === 1 ? "has" : "have"} no description yet, so the AI scan could not score{" "}
            {unfetched === 1 ? "it" : "them"}. Retry reads {unfetched === 1 ? "it" : "them"} from
            LinkedIn and re-runs the scan; it does not repeat the search.
          </>
        ) : (
          <>Retry re-runs the AI scan over this run's jobs; it does not repeat the search.</>
        )}
        {waiting && (
          <>
            {" "}
            LinkedIn's cooldown ends at {formatClock(cooldown?.until ?? null)}.
          </>
        )}
      </p>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      <button type="button" onClick={handleResume} disabled={resuming || waiting} className="retry-button">
        {label}
      </button>
    </div>
  );
}

/** m:ss for a countdown, h:mm:ss once it is long enough to need it. */
function formatCountdown(totalSeconds: number): string {
  const s = Math.max(0, Math.floor(totalSeconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${sec}` : `${m}:${sec}`;
}

function formatClock(iso: string | null): string {
  if (!iso) return "-";
  return new Date(iso).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

/** Formats a duration in whole seconds as m:ss. */
function elapsed(fromIso: string | null): string {
  if (!fromIso) return "-";
  const seconds = Math.max(0, Math.floor((Date.now() - new Date(fromIso).getTime()) / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

/**
 * Live numbers for the AI scan. The scan is the one phase with no per-item feedback of its own -
 * a batch takes seconds and several run at once - so without this the UI sits on "Scanning with
 * AI" and looks hung.
 */
function ScanPanel({ scan, live }: { scan: ScanProgress; live: boolean }) {
  // The elapsed clock has to advance between server updates, which only arrive when a batch
  // finishes; without a local tick it would freeze for seconds at a time and read as a stall.
  const [, setTick] = useState(0);
  useEffect(() => {
    if (!live) return;
    const id = setInterval(() => setTick((t) => t + 1), 1000);
    return () => clearInterval(id);
  }, [live]);

  const pct = scan.jobsTotal > 0 ? Math.round((scan.jobsScanned / scan.jobsTotal) * 100) : 0;

  return (
    <div className="scan-panel">
      <div className="scan-head">
        <strong>AI scan</strong>
        <span className="scan-counts">
          batch {scan.batchesDone}/{scan.batchesTotal} · {scan.jobsScanned}/{scan.jobsTotal} jobs
        </span>
        {live && <span className="scan-elapsed">{elapsed(scan.startedAt)}</span>}
      </div>

      <div className="scan-bar" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}>
        <div className="scan-bar-fill" style={{ width: `${pct}%` }} />
      </div>

      <dl className="scan-stats">
        <div>
          <dt>Recommended</dt>
          <dd className="scan-rec">{scan.recommended}</dd>
        </div>
        <div>
          <dt>Not recommended</dt>
          <dd>{scan.notRecommended}</dd>
        </div>
        <div>
          <dt>Failed batches</dt>
          <dd className={scan.failedBatches > 0 ? "scan-fail" : undefined}>{scan.failedBatches}</dd>
        </div>
        <div>
          <dt>Cost</dt>
          <dd>${scan.costUsd.toFixed(4)}</dd>
        </div>
      </dl>

      <p className="scan-hint">
        Live detail: <code>tail -f logs/ai-scan.log</code>
      </p>
    </div>
  );
}
