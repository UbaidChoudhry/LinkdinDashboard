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
import { useRunStream } from "./hooks/useRunStream";
import type { RunResponse } from "./types/api";

/** The top-level sections of the dashboard. Each is a tab; only one is visible at a time. */
type AppTab = "search" | "results" | "sources" | "resumes" | "filters" | "data";

const APP_TABS: { id: AppTab; label: string }[] = [
  { id: "search", label: "Search" },
  { id: "results", label: "Results" },
  { id: "sources", label: "Sources" },
  { id: "resumes", label: "Resumes" },
  { id: "filters", label: "Filters" },
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
  const prevStatusRef = useRef<string | null>(null);
  const activeTabRef = useRef<AppTab>("search");

  const { run: streamedRun, streamError } = useRunStream(currentRunId);

  // Mirror the active tab into a ref so the run-status effect below can read it without taking
  // it as a dependency (which would re-run that effect, and re-fire its tab switch, on every
  // tab change). Declared first so the ref is current before that effect runs.
  useEffect(() => {
    activeTabRef.current = activeTab;
  }, [activeTab]);

  // Load the most recent run on first mount, purely so the Search tab's empty state can explain
  // "no run yet" vs. "run returned nothing" even before the user starts anything this session.
  useEffect(() => {
    listRuns()
      .then((runs) => {
        if (runs.length > 0) {
          setLastKnownRun(runs[0]);
          if (runs[0].status === "running") {
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
    const prev = prevStatusRef.current;
    prevStatusRef.current = streamedRun.status;
    if (prev === "running" && streamedRun.status !== "running") {
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
    prevStatusRef.current = "running";
    setCurrentRunId(runId);
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
    prevStatusRef.current = null;
    setRefreshToken((t) => t + 1);
  }

  // Stable identity: JobsPanel reports the count from an effect, so a fresh function every
  // render would re-fire that effect on every render.
  const handleJobCountChange = useCallback((count: number) => setResultCount(count), []);

  const displayRun = streamedRun ?? (currentRunId != null ? lastKnownRun : null);
  const runInFlight = displayRun?.status === "running";

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
            {displayRun && <RunProgress run={displayRun} streamError={streamError} onCancelled={handleCancelled} />}
          </div>
        </div>

        <div className="tab-panel tab-panel-wide" hidden={activeTab !== "results"}>
          <JobsPanel
            refreshToken={refreshToken}
            latestRun={lastKnownRun}
            onCountChange={handleJobCountChange}
          />
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

        <div className="tab-panel" hidden={activeTab !== "data"}>
          <DataPanel refreshToken={refreshToken} onCleared={handleDataCleared} />
        </div>
      </main>
    </div>
  );
}

export default App;
