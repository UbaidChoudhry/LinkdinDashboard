import { useEffect, useMemo, useState, type FormEvent } from "react";
import { listResumes, startUrlApplications } from "../api/client";
import type { ResumeResponse } from "../types/api";
import { useApplyBatch } from "../hooks/useApplyBatch";
import { ApplyBatchDetails, ApplyBatchStatus } from "./ApplyBatchView";
import { InfoTip } from "./InfoTip";

interface UrlApplyPanelProps {
  /** Bumped by the shell when the resume list changes, so the picker below refetches. */
  resumeToken: number;
  /** Called once a batch finishes - submitted jobs have moved to the Applied tab. */
  onFinished: () => void;
}

/** Matches the backend's cap, ApplyOrchestrator.MAX_CONCURRENCY. */
const MAX_AT_ONCE = 5;

/** The distinct http(s) URLs in the textarea, and the non-blank lines that aren't one. */
function parseUrls(text: string): { urls: string[]; invalid: string[] } {
  const urls: string[] = [];
  const invalid: string[] = [];
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (!line) continue;
    let ok = false;
    try {
      const url = new URL(line);
      ok = url.protocol === "http:" || url.protocol === "https:";
    } catch {
      ok = false;
    }
    if (!ok) invalid.push(line);
    else if (!urls.includes(line)) urls.push(line);
  }
  return { urls, invalid };
}

/**
 * The Apply tab: paste job posting URLs (Greenhouse, Lever, Workday, a company's own careers
 * page...) and Claude applies to each, several at once, exactly as "Apply with Claude" does for
 * the Results tab's Recommended bucket.
 */
export function UrlApplyPanel({ resumeToken, onFinished }: UrlApplyPanelProps) {
  const [text, setText] = useState("");
  const [resumes, setResumes] = useState<ResumeResponse[]>([]);
  const [resumeId, setResumeId] = useState<number | null>(null);
  const [submit, setSubmit] = useState(false);
  const [atOnce, setAtOnce] = useState(3);
  const { batch, running, starting, error, start, cancel } = useApplyBatch(onFinished);
  const { urls, invalid } = useMemo(() => parseUrls(text), [text]);

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
      .catch(() => setResumes([]));
  }, [resumeToken]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const started = await start(() => startUrlApplications({ urls, resumeId, submit, concurrency: atOnce }));
    // Clearing the box once the batch is running keeps a second click from applying twice.
    if (started) setText("");
  }

  const busy = running || starting;

  return (
    <div className="url-apply">
      <form className="run-controls url-apply-form" onSubmit={handleSubmit}>
        <h2>Apply to job URLs</h2>
        <p className="hint">
          Paste job posting links, one per line. Claude opens each one, fills the application from
          your resume and applicant profile, and - unless Submit is on - stops on the review step
          with the tab left open for you.
        </p>

        <div className="field-row">
          <label htmlFor="ua-urls">Job posting URLs</label>
          <textarea
            id="ua-urls"
            rows={10}
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder={"https://job-boards.greenhouse.io/acme/jobs/1234567\nhttps://acme.wd5.myworkdayjobs.com/en-US/Careers/job/…"}
            spellCheck={false}
            disabled={busy}
          />
          <span className="muted">
            {urls.length} URL{urls.length === 1 ? "" : "s"}
            {invalid.length > 0 && (
              <span className="form-error">
                {" "}
                · {invalid.length} line{invalid.length === 1 ? " isn't a URL" : "s aren't URLs"}: {invalid[0]}
              </span>
            )}
          </span>
        </div>

        <div className="url-apply-options">
          <div className="field-row">
            <label htmlFor="ua-resume">Resume</label>
            <select
              id="ua-resume"
              value={resumeId ?? ""}
              onChange={(e) => setResumeId(e.target.value ? Number(e.target.value) : null)}
              disabled={busy || resumes.length === 0}
            >
              {resumes.length === 0 ? (
                <option value="">No resume uploaded - add one in the Resumes tab</option>
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

          <div className="field-row">
            <label htmlFor="ua-at-once">At a time</label>
            <select id="ua-at-once" value={atOnce} onChange={(e) => setAtOnce(Number(e.target.value))} disabled={busy}>
              {Array.from({ length: MAX_AT_ONCE }, (_, i) => i + 1).map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
          </div>

          <div className="field-row checkbox-row">
            <input id="ua-submit" type="checkbox" checked={submit} onChange={(e) => setSubmit(e.target.checked)} disabled={busy} />
            <label htmlFor="ua-submit">Submit applications</label>
          </div>
        </div>

        <div className="url-apply-actions">
          <button type="submit" disabled={busy || urls.length === 0 || invalid.length > 0 || resumeId == null}>
            {starting ? "Starting…" : `Apply with Claude (${urls.length})`}
          </button>
          <InfoTip label="About applying by URL">
            Each application is its own Claude session in its own Chrome tab group; "At a time" is how
            many run side by side. Two jobs on the same Workday company always run one after the
            other. Workday needs a sign-in: keep Bitwarden unlocked, with a login saved for that
            company's Workday site and "Show autofill suggestions on form fields" turned on in
            Bitwarden's Autofill settings - Claude picks the login from Bitwarden's menu and never
            types a password.
          </InfoTip>
        </div>

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
      </form>

      {batch && (
        <section className="url-apply-batch">
          <h2>{running ? "Applying" : "Last batch"}</h2>
          <div className="apply-controls">
            <ApplyBatchStatus batch={batch} onCancel={cancel} />
          </div>
          <ApplyBatchDetails batch={batch} />
        </section>
      )}
    </div>
  );
}
