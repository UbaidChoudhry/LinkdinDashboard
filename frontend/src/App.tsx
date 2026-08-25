import { useEffect, useRef, useState } from "react";
import "./App.css";
import { ApiError, listRuns } from "./api/client";
import { RunControls } from "./components/RunControls";
import { RunProgress } from "./components/RunProgress";
import { JobsPanel } from "./components/JobsPanel";
import { FiltersPanel } from "./components/FiltersPanel";
import { CompanyVolumeReport } from "./components/CompanyVolumeReport";
import { useRunStream } from "./hooks/useRunStream";
import type { RunResponse } from "./types/api";

function App() {
  const [currentRunId, setCurrentRunId] = useState<number | null>(null);
  const [lastKnownRun, setLastKnownRun] = useState<RunResponse | null>(null);
  const [historyError, setHistoryError] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const prevStatusRef = useRef<string | null>(null);

  const { run: streamedRun, streamError } = useRunStream(currentRunId);

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

  const displayRun = streamedRun ?? (currentRunId != null ? lastKnownRun : null);
  const runInFlight = displayRun?.status === "running";

  return (
    <div className="app-shell">
      <header className="app-header">
        <h1>LinkedIn Job Dashboard</h1>
      </header>

      {historyError && (
        <p className="form-error" role="alert">
          {historyError}
        </p>
      )}

      <main className="app-main">
        <div className="run-section">
          <RunControls disabled={runInFlight} onRunStarted={handleRunStarted} />
          {displayRun && <RunProgress run={displayRun} streamError={streamError} onCancelled={handleCancelled} />}
        </div>

        <JobsPanel refreshToken={refreshToken} latestRun={lastKnownRun} />

        <FiltersPanel />

        <CompanyVolumeReport />
      </main>
    </div>
  );
}

export default App;
