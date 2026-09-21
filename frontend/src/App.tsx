import { useCallback, useEffect, useRef, useState } from "react";
import "./App.css";
import { ApiError, listRuns } from "./api/client";
import { RunControls } from "./components/RunControls";
import { RunProgress } from "./components/RunProgress";
import { JobsPanel } from "./components/JobsPanel";
import { FiltersPanel } from "./components/FiltersPanel";
import { CompanyVolumeReport } from "./components/CompanyVolumeReport";
import { DataPanel } from "./components/DataPanel";
import { SourcesPanel } from "./components/SourcesPanel";
import { ResumesPanel } from "./components/ResumesPanel";
import { RunsPanel } from "./components/RunsPanel";
import { UrlApplyPanel } from "./components/UrlApplyPanel";
import { useRunStream } from "./hooks/useRunStream";
import { isRunFinished, isRunInFlight } from "./utils/runStatus";
import type { RunResponse } from "./types/api";

/** The top-level sections of the dashboard. Each is a tab; only one is visible at a time. */
type AppTab = "search" | "results" | "apply" | "sources" | "resumes" | "filters" | "runs" | "data";

const APP_TABS: { id: AppTab; label: string }[] = [
  { id: "search", label: "Search" },
  { id: "results", label: "Results" },
  { id: "apply", label: "Apply" },
  { id: "sources", label: "Sources" },
  { id: "resumes", label: "Resumes" },
  { id: "filters", label: "Filters" },
  { id: "runs", label: "Runs" },
  { id: "data", label: "Data" },
];

function App() {
  const [currentRunId, setCurrentRunId] = useState<number | null>(null);
  const [lastKnownRun, setLastKnownRun] = useState<RunResponse | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  // Bumped when a resume is added/removed, so the run form's resume picker refetches.
  const [resumeToken, setResumeToken] = useState(0);
  const [activeTab, setActiveTab] = useState<AppTab>("search");
  const [resultCount, setResultCount] = useState<number | null>(null);
  // Tracks isRunFinished(), not raw status - see that function's doc comment for the
  // collect()-to-detail-fetch race a plain status comparison here used to fall for.
  const prevFinishedRef = useRef<boolean>(false);
  const activeTabRef = useRef<AppTab>("search");

  // Bumped when the current run is resumed, so the stream re-subscribes to the same id.
  const [streamAttempt, setStreamAttempt] = useState(0);
  const { run: streamedRun, streamError } = useRunStream(currentRunId, streamAttempt);

  // Mirror the active tab into a ref so the run-status effect below can read it without taking
  // it as a dependency (which would re-run that effect, and re-fire its tab switch, on every
  // tab change). Declared first so the ref is current before that effect runs.
  useEffect(() => {
    activeTabRef.current = activeTab;
  }, [activeTab]);

  // Load the most recent run on first mount: the Search tab shows its panel (with Retry, if it
  // was cut short) and the empty state can explain "no run yet" vs. "run returned nothing"
  // before the user starts anything this session.
  useEffect(() => {
    listRuns()
      .then((runs) => {
        if (runs.length > 0) {
          setLastKnownRun(runs[0]);
          if (isRunInFlight(runs[0].status)) {
            setCurrentRunId(runs[0].id);
          }
        }
      })
      .catch((err) => {
        setHistoryError(err instanceof ApiError ? err.message : "Failed to load run history.");
      });
  }, []);

  // Track the freshest run info (streamed takes priority while connected) and bump the jobs
  // refresh token whenever a run transitions into a terminal state.
  useEffect(() => {
    if (!streamedRun) return;
    setLastKnownRun(streamedRun);
    const wasFinished = prevFinishedRef.current;
    const finished = isRunFinished(streamedRun);
    prevFinishedRef.current = finished;
    // Only a genuinely finished run (isRunFinished, not just a non-in-flight status) means
    // "done". A status that merely looks terminal - the moment collect() ends and before the
    // next phase publishes its own in-flight status - used to refresh the results list and
    // switch tabs before the AI scan had even started, and then never refresh again once the
    // real finish arrived (prevFinishedRef was already "true" for the blip).
    if (!wasFinished && finished) {
      setRefreshToken((t) => t + 1);
      // A finished run's payoff is the result list, so land the user on it - but only if they
      // were still watching the run. If they'd wandered off to Filters or Data, yanking the
      // view out from under them would be worse than making them click once.
      if (activeTabRef.current === "search") {
        setActiveTab("results");
      }
    }
  }, [streamedRun]);

  function handleRunStarted(runId: number) {
    prevFinishedRef.current = false;
    setCurrentRunId(runId);
  }

  function handleRunResumed(runId: number) {
    // Same run id as before, so bumping the attempt is what re-opens the SSE subscription
    // (useRunStream keys its effect on both). The finish-transition tracking starts over too,
    // so the results refresh and tab switch fire again when the resumed run ends.
    prevFinishedRef.current = false;
    setCurrentRunId(runId);
    setStreamAttempt((a) => a + 1);
  }

  function handleCancelled() {
    // Cancellation is asynchronous on the backend; the SSE stream will report the terminal
    // status once the run actually stops, which will trigger the refresh via the effect above.
  }

  function handleDataCleared() {
    // Clearing deletes every sweep_run row too, so any run we were tracking no longer exists -
    // drop it rather than let the UI describe a run that's now gone.
    setCurrentRunId(null);
    setLastKnownRun(null);
    prevFinishedRef.current = false;
    setRefreshToken((t) => t + 1);
  }

  // Stable identity: JobsPanel reports the count from an effect, so a fresh function every
  // render would re-fire that effect on every render.
  const handleJobCountChange = useCallback((count: number) => setResultCount(count), []);
  // A finished Apply-tab batch has moved the jobs Claude submitted onto the Applied list.
  const handleUrlBatchFinished = useCallback(() => setRefreshToken((t) => t + 1), []);

  // The panel shows the most recent run even when it finished before this page load: a run
  // LinkedIn's cooldown cut short is only retryable from its panel, and the cooldown itself
  // (30 minutes) is longer than most people keep a tab open without reloading it. Hiding the
  // panel for anything not started in this session was what made Retry unreachable.
  const displayRun = streamedRun ?? lastKnownRun;
  const runInFlight = isRunInFlight(displayRun?.status);

  return (
    <div className="app-shell">
      <header className="app-header">
        <h1>LinkedIn Job Dashboard</h1>
        {runInFlight && (
          // The run keeps going while you browse other tabs, so carry a compact live indicator
          // in the header - otherwise leaving the Search tab looks like the run stopped.
          <span className="run-pill" role="status">
            <span className="run-pill-dot" aria-hidden="true" />
            Run in progress · {displayRun?.jobsNew ?? 0} new
          </span>
        )}
      </header>

      {historyError && (
        <p className="form-error" role="alert">
          {historyError}
        </p>
      )}

      <nav className="app-tab-bar" role="tablist" aria-label="Dashboard sections">
        {APP_TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={activeTab === t.id}
            className={activeTab === t.id ? "app-tab active" : "app-tab"}
            onClick={() => setActiveTab(t.id)}
          >
            {t.label}
            {t.id === "results" && resultCount != null && (
              <span className="app-tab-count">{resultCount}</span>
            )}
          </button>
        ))}
      </nav>

      {/*
        Every panel stays mounted and is toggled with `hidden` rather than unmounted. Switching
        tabs therefore keeps the jobs table's sort, min-salary filter and sub-tab, and doesn't
        refetch every list on each switch.
      */}
      <main className="app-main">
        <div className="tab-panel" hidden={activeTab !== "search"}>
          <div className="run-section">
            <RunControls
              disabled={runInFlight}
              onRunStarted={handleRunStarted}
              resumeToken={resumeToken}
            />
            {displayRun && (
              <RunProgress
                run={displayRun}
                streamError={streamError}
                onCancelled={handleCancelled}
                onResumed={handleRunResumed}
              />
            )}
          </div>
        </div>

        <div className="tab-panel tab-panel-wide" hidden={activeTab !== "results"}>
          <JobsPanel
            refreshToken={refreshToken}
            latestRun={lastKnownRun}
            onCountChange={handleJobCountChange}
          />
        </div>

        <div className="tab-panel" hidden={activeTab !== "apply"}>
          <UrlApplyPanel resumeToken={resumeToken} onFinished={handleUrlBatchFinished} />
        </div>

        <div className="tab-panel tab-panel-wide" hidden={activeTab !== "sources"}>
          <SourcesPanel />
        </div>

        <div className="tab-panel" hidden={activeTab !== "resumes"}>
          <ResumesPanel onResumesChanged={() => setResumeToken((t) => t + 1)} />
        </div>

        <div className="tab-panel" hidden={activeTab !== "filters"}>
          <FiltersPanel />
          <CompanyVolumeReport />
        </div>

        <div className="tab-panel tab-panel-wide" hidden={activeTab !== "runs"}>
          <RunsPanel refreshToken={refreshToken} />
        </div>

        <div className="tab-panel" hidden={activeTab !== "data"}>
          <DataPanel refreshToken={refreshToken} onCleared={handleDataCleared} />
        </div>
      </main>
    </div>
  );
}

export default App;
