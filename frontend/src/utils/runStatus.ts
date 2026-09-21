import type { RunResponse } from "../types/api";

export type RunStatusKind = "success" | "warning" | "error" | "info" | "neutral";

export interface RunStatusInfo {
  label: string;
  message: string;
  kind: RunStatusKind;
}

/** Human explanations for every terminal (and the in-flight) run status the backend can report. */
export const RUN_STATUS_INFO: Record<string, RunStatusInfo> = {
  running: {
    label: "Running",
    message: "The sweep is in progress.",
    kind: "info",
  },
  fetching_details: {
    label: "Fetching job descriptions",
    message:
      "Search pages are done; LinkedIn's detail page is now being read for each new posting, one paced request at a time, so the AI scan has a description to compare.",
    kind: "info",
  },
  scanning: {
    label: "Scanning with AI",
    message: "All sources have been fetched; Claude is now comparing each job description against your resume.",
    kind: "info",
  },
  ok: {
    label: "Completed",
    message: "The run finished normally.",
    kind: "success",
  },
  no_sources: {
    label: "Stopped: no companies enabled",
    message:
      "No enabled companies matched the selected sources. Enable some in the Sources tab, or import a catalog with ./import-slugs.sh",
    kind: "warning",
  },
  capped: {
    label: "Stopped: request cap",
    message:
      "The run stopped after hitting its per-run request cap. Results may be incomplete; jobs collected without a description can be finished with Retry below.",
    kind: "warning",
  },
  budget_exhausted: {
    label: "Stopped: daily budget exhausted",
    message:
      "The run stopped because today's request budget has been spent. It will resume capacity tomorrow - Retry below then fetches what this run left unread.",
    kind: "warning",
  },
  blocked: {
    label: "Blocked (cooldown)",
    message:
      "LinkedIn answered with a rate limit, so LinkedIn requests are paused for a cooldown. This is not an error to clear: the jobs already collected are kept, and once the countdown below ends, Retry fetches their descriptions and runs the AI scan.",
    kind: "warning",
  },
  failed: {
    label: "Failed: no relevant results",
    message: "The run finished, but the query didn't match anything useful.",
    kind: "error",
  },
  cancelled: {
    label: "Cancelled",
    message: "The run was cancelled before it finished.",
    kind: "neutral",
  },
  interrupted: {
    label: "Interrupted",
    message:
      "The app stopped while this run was in flight, so it was closed out on the next start. Anything it had already collected was saved - just start a new run.",
    kind: "neutral",
  },
};

export function runStatusInfo(status: string | null | undefined): RunStatusInfo {
  if (!status) {
    return { label: "Unknown", message: "No status available.", kind: "neutral" };
  }
  return (
    RUN_STATUS_INFO[status] ?? {
      label: status,
      message: `Run ended with status "${status}".`,
      kind: "neutral",
    }
  );
}

/**
 * Statuses that mean the run is still working. `fetching_details` and `scanning` are
 * deliberately included: they are the phases after collection, and the server keeps the SSE
 * stream open through them (RunController's IN_FLIGHT_STATUSES is the mirror of this set).
 *
 * Treating anything that isn't `"running"` as finished is the bug this exists to prevent - it
 * made the browser hang up the moment the scan began, so the scan's progress never arrived and
 * the page had to be reloaded by hand to see any result. Every in-flight status the backend can
 * publish must be listed here or the SSE stream hangs up early. See HANDOFF.md §9.
 */
export const IN_FLIGHT_STATUSES: ReadonlySet<string> = new Set([
  "running",
  "fetching_details",
  "scanning",
]);

/** True while a run is still working (collecting or scanning). */
export function isRunInFlight(status: string | null | undefined): boolean {
  return status != null && IN_FLIGHT_STATUSES.has(status);
}

/**
 * True only once a run has genuinely finished - not merely reached a status outside
 * `IN_FLIGHT_STATUSES`, but had a finish timestamp actually persisted for it.
 *
 * This guards a real phase-boundary race, not a hypothetical one: `SweepService.collect()`
 * publishes the *LinkedIn collection phase's own* outcome (e.g. `"ok"`) into the shared
 * in-memory progress registry the instant collection ends - before `DetailFetchService` (or,
 * for a run with no detail phase, the scan) runs its own setup and publishes an in-flight
 * status a moment later. `RunResponse.finishedAt` is set exactly once, by
 * `RunOrchestrator.finishRun()`, strictly after every phase - including the AI scan - has
 * actually completed, and the backend persists it to the row before republishing the matching
 * terminal status into the registry, so pairing the two here is safe.
 *
 * The SSE stream is polled every 750ms independently of these transitions, so a poll can land
 * squarely inside that collect()-to-detail-fetch gap and hand the client a status that *looks*
 * terminal while the run is still very much in progress. Treating that blip as "the run is
 * done" is what closed the browser's EventSource for good (bug: AI scan progress never showed
 * without a manual reload) and auto-switched the shell to the Results tab before the scan had
 * even started. Both call sites that used to decide "is this run over?" off `status` alone now
 * go through this instead. See HANDOFF §9's "Three fixes from real use" for the family of bugs
 * this belongs to.
 */
export function isRunFinished(run: Pick<RunResponse, "status" | "finishedAt">): boolean {
  return !isRunInFlight(run.status) && run.finishedAt != null;
}
