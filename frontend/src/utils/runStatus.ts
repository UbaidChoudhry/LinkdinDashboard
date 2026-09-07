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
    message: "The run stopped after hitting its per-run request cap. Results may be incomplete.",
    kind: "warning",
  },
  budget_exhausted: {
    label: "Stopped: daily budget exhausted",
    message: "The run stopped because today's request budget has been spent. It will resume capacity tomorrow.",
    kind: "warning",
  },
  blocked: {
    label: "Blocked (cooldown)",
    message:
      "LinkedIn requests are paused after repeated failures. This is a cooldown, not an error to clear - wait and try again shortly.",
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
 * Statuses that mean the run is still working. `scanning` is deliberately included: the AI scan
 * is the last phase of a run, and the server keeps the SSE stream open through it.
 *
 * Treating anything that isn't `"running"` as finished is the bug this exists to prevent - it
 * made the browser hang up the moment the scan began, so the scan's progress never arrived and
 * the page had to be reloaded by hand to see any result.
 */
export const IN_FLIGHT_STATUSES: ReadonlySet<string> = new Set(["running", "scanning"]);

/** True while a run is still working (collecting or scanning). */
export function isRunInFlight(status: string | null | undefined): boolean {
  return status != null && IN_FLIGHT_STATUSES.has(status);
}
