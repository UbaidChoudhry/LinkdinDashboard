import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { ApiError, createRun, listResumes } from "../api/client";
import type { CreateRunRequest, JobSourceName, ResumeResponse } from "../types/api";
import { SourceSelect } from "./SourceSelect";

interface RunControlsProps {
  disabled: boolean;
  onRunStarted: (runId: number) => void;
  /** Bumped by the shell when the resume list changes, so the picker below refetches. */
  resumeToken?: number;
}

const DEFAULT_KEYWORDS = "Software Engineer";
const DEFAULT_HOURS = 24;
const DEFAULT_LOCATION = "United States";
const DEFAULT_SOURCES: JobSourceName[] = ["greenhouse", "lever", "workday"];

export function RunControls({ disabled, onRunStarted, resumeToken = 0 }: RunControlsProps) {
  const [sources, setSources] = useState<JobSourceName[]>(DEFAULT_SOURCES);
  const [resumes, setResumes] = useState<ResumeResponse[]>([]);
  const [resumeId, setResumeId] = useState<number | null>(null);
  const [keywords, setKeywords] = useState(DEFAULT_KEYWORDS);
  const [hours, setHours] = useState(DEFAULT_HOURS);
  const [location, setLocation] = useState(DEFAULT_LOCATION);
  const [testMode, setTestMode] = useState(false);
  const [pageCap, setPageCap] = useState<string>("");
  const [useShards, setUseShards] = useState(false);
  // ATS boards are worldwide; US-only is the useful default and must be opted out of.
  const [usOnly, setUsOnly] = useState(true);
  const [shardWarningAck, setShardWarningAck] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const linkedInOnly = sources.length === 1 && sources[0] === "linkedin";
  // Sharding, the page cap and test mode are all LinkedIn pagination concepts; ATS boards
  // return a company's whole board in one request and have none of them.
  const shardToggleBlocked = linkedInOnly && useShards && !shardWarningAck;

  useEffect(() => {
    listResumes()
      .then((list) => {
        setResumes(list);
        // Follow the default resume unless the user has explicitly picked another.
        setResumeId((current) => {
          if (current != null && list.some((r) => r.id === current)) return current;
          return list.find((r) => r.isDefault)?.id ?? list[0]?.id ?? null;
        });
      })
      .catch(() => {
        // A resume list failure must not block starting a run - the scan step is optional.
        setResumes([]);
      });
  }, [resumeToken]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    if (!keywords.trim()) {
      setError("Keywords are required.");
      return;
    }
    if (sources.length === 0) {
      setError("Pick at least one source.");
      return;
    }
    if (linkedInOnly && !useShards && !location.trim()) {
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
      useShards: linkedInOnly && useShards,
      sources,
    };
    if (!(linkedInOnly && useShards)) {
      body.location = location.trim();
    }
    if (!linkedInOnly && resumeId != null) {
      body.resumeId = resumeId;
    }
    if (!linkedInOnly) {
      body.usOnly = usOnly;
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
        <span className="field-label">Sources</span>
        <SourceSelect value={sources} onChange={setSources} disabled={disabled || submitting} />
      </div>

      {!linkedInOnly && (
        <div className="field-row">
          <label htmlFor="rc-resume">Resume for AI scan</label>
          <select
            id="rc-resume"
            value={resumeId ?? ""}
            onChange={(e) => setResumeId(e.target.value ? Number(e.target.value) : null)}
            disabled={disabled || submitting || resumes.length === 0}
          >
            {resumes.length === 0 ? (
              <option value="">No resume uploaded — scan will be skipped</option>
            ) : (
              resumes.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                  {r.isDefault ? " (default)" : ""}
                </option>
              ))
            )}
          </select>
        </div>
      )}

      {!linkedInOnly && (
        <div className="field-row checkbox-row">
          <input
            id="rc-usonly"
            type="checkbox"
            checked={usOnly}
            onChange={(e) => setUsOnly(e.target.checked)}
            disabled={disabled || submitting}
          />
          <label htmlFor="rc-usonly">United States only (company boards list jobs worldwide)</label>
        </div>
      )}

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
          disabled={disabled || submitting || (linkedInOnly && useShards)}
          placeholder={linkedInOnly && useShards ? "ignored while sharding is on" : undefined}
        />
      </div>

      {linkedInOnly && (
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
      )}

      {linkedInOnly && (
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
      )}

      {linkedInOnly && (
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
      )}

      {linkedInOnly && useShards && (
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
