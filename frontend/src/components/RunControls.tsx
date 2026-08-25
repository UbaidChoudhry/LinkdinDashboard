import { useState } from "react";
import type { FormEvent } from "react";
import { ApiError, createRun } from "../api/client";
import type { CreateRunRequest } from "../types/api";

interface RunControlsProps {
  disabled: boolean;
  onRunStarted: (runId: number) => void;
}

const DEFAULT_KEYWORDS = "Software Engineer";
const DEFAULT_HOURS = 24;
const DEFAULT_LOCATION = "United States";

export function RunControls({ disabled, onRunStarted }: RunControlsProps) {
  const [keywords, setKeywords] = useState(DEFAULT_KEYWORDS);
  const [hours, setHours] = useState(DEFAULT_HOURS);
  const [location, setLocation] = useState(DEFAULT_LOCATION);
  const [testMode, setTestMode] = useState(false);
  const [pageCap, setPageCap] = useState<string>("");
  const [useShards, setUseShards] = useState(false);
  const [shardWarningAck, setShardWarningAck] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const shardToggleBlocked = useShards && !shardWarningAck;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    if (!keywords.trim()) {
      setError("Keywords are required.");
      return;
    }
    if (!useShards && !location.trim()) {
      setError("Location is required unless sharding by metro is enabled.");
      return;
    }
    if (shardToggleBlocked) {
      setError("Confirm you understand the cost of sharding before starting a sharded run.");
      return;
    }

    const body: CreateRunRequest = {
      keywords: keywords.trim(),
      hours,
      testMode,
      useShards,
    };
    if (!useShards) {
      body.location = location.trim();
    }
    if (pageCap.trim()) {
      const parsed = Number(pageCap);
      if (!Number.isNaN(parsed)) {
        body.pageCap = parsed;
      }
    }

    setSubmitting(true);
    try {
      const res = await createRun(body);
      onRunStarted(res.runId);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to start run.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="run-controls" onSubmit={handleSubmit}>
      <h2>Start a run</h2>

      <div className="field-row">
        <label htmlFor="rc-keywords">Keywords</label>
        <input
          id="rc-keywords"
          type="text"
          value={keywords}
          onChange={(e) => setKeywords(e.target.value)}
          disabled={disabled || submitting}
          required
        />
      </div>

      <div className="field-row">
        <label htmlFor="rc-hours">Hours (recency window)</label>
        <input
          id="rc-hours"
          type="number"
          min={1}
          value={hours}
          onChange={(e) => setHours(Number(e.target.value))}
          disabled={disabled || submitting}
        />
      </div>

      <div className="field-row">
        <label htmlFor="rc-location">Location</label>
        <input
          id="rc-location"
          type="text"
          value={location}
          onChange={(e) => setLocation(e.target.value)}
          disabled={disabled || submitting || useShards}
          placeholder={useShards ? "ignored while sharding is on" : undefined}
        />
      </div>

      <div className="field-row">
        <label htmlFor="rc-pagecap">Page cap (optional)</label>
        <input
          id="rc-pagecap"
          type="number"
          min={1}
          value={pageCap}
          onChange={(e) => setPageCap(e.target.value)}
          disabled={disabled || submitting}
          placeholder="no cap"
        />
      </div>

      <div className="field-row checkbox-row">
        <input
          id="rc-testmode"
          type="checkbox"
          checked={testMode}
          onChange={(e) => setTestMode(e.target.checked)}
          disabled={disabled || submitting}
        />
        <label htmlFor="rc-testmode">Test mode (caps pages fetched, safe to run often)</label>
      </div>

      <div className="field-row checkbox-row">
        <input
          id="rc-shards"
          type="checkbox"
          checked={useShards}
          onChange={(e) => {
            setUseShards(e.target.checked);
            if (!e.target.checked) {
              setShardWarningAck(false);
            }
          }}
          disabled={disabled || submitting}
        />
        <label htmlFor="rc-shards">Shard by metro (opt-in, expensive - see warning below)</label>
      </div>

      {useShards && (
        <div className="shard-warning" role="alert">
          <strong>This is expensive.</strong> A full US-wide sweep is already about 100 requests
          and roughly 15 minutes. Sharding by metro multiplies that cost against a daily budget of
          only 200-300 requests - a sharded run can consume most or all of a day's budget in one
          go. Only enable this if you specifically need per-metro coverage.
          <div className="checkbox-row">
            <input
              id="rc-shard-ack"
              type="checkbox"
              checked={shardWarningAck}
              onChange={(e) => setShardWarningAck(e.target.checked)}
              disabled={disabled || submitting}
            />
            <label htmlFor="rc-shard-ack">I understand this will consume a large part of today's request budget.</label>
          </div>
        </div>
      )}

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      <button type="submit" disabled={disabled || submitting || shardToggleBlocked}>
        {submitting ? "Starting..." : "Start run"}
      </button>
    </form>
  );
}
