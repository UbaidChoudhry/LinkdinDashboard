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
