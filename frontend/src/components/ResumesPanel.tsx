import { useCallback, useEffect, useRef, useState } from "react";
import type { ChangeEvent } from "react";
import {
  ApiError,
  deleteResume,
  getResumeText,
  listResumes,
  setDefaultResume,
  uploadResume,
} from "../api/client";
import type { ResumeResponse } from "../types/api";
import { absoluteTime } from "../utils/format";
import { ApplicantProfileForm } from "./ApplicantProfileForm";

interface ResumesPanelProps {
  /** Bumped by the panel whenever the list changes, so the run form can refresh its picker. */
  onResumesChanged?: () => void;
}

const ACCEPT = ".pdf,.txt,.md";

/**
 * Upload and manage the resumes the AI scan compares job descriptions against. Exactly one
 * resume is the default; that is the one a run scans with unless another is picked on the run
 * form.
 */
export function ResumesPanel({ onResumesChanged }: ResumesPanelProps) {
  const [resumes, setResumes] = useState<ResumeResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [name, setName] = useState("");
  const [preview, setPreview] = useState<{ id: number; text: string } | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setResumes(await listResumes());
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load resumes.");
      setResumes([]);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function handleFileChosen(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      await uploadResume(file, name);
      setName("");
      // Clear the input so re-picking the same file still fires a change event.
      if (fileInputRef.current) fileInputRef.current.value = "";
      await load();
      onResumesChanged?.();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to upload resume.");
      if (fileInputRef.current) fileInputRef.current.value = "";
    } finally {
      setBusy(false);
    }
  }

  async function handleSetDefault(id: number) {
    setBusy(true);
    setError(null);
    try {
      await setDefaultResume(id);
      await load();
      onResumesChanged?.();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to set the default resume.");
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(id: number) {
    setBusy(true);
    setError(null);
    try {
      await deleteResume(id);
      if (preview?.id === id) setPreview(null);
      await load();
      onResumesChanged?.();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete resume.");
    } finally {
      setBusy(false);
    }
  }

  async function handlePreview(id: number) {
    if (preview?.id === id) {
      setPreview(null);
      return;
    }
    setError(null);
    try {
      setPreview({ id, text: await getResumeText(id) });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load the extracted text.");
    }
  }

  return (
    <section className="resumes-panel">
      <h2>Resumes</h2>
      <p className="panel-hint">
        The AI scan compares each job description against one of these. PDF, plain text and
        Markdown are accepted — a PDF must contain selectable text, not a scanned image.
      </p>

      <form className="resume-upload" onSubmit={(e) => e.preventDefault()}>
        <div className="field-row">
          <label htmlFor="rp-name">Label (optional)</label>
          <input
            id="rp-name"
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g. Backend - senior"
            disabled={busy}
          />
        </div>
        <div className="field-row">
          <label htmlFor="rp-file">Resume file</label>
          <input
            id="rp-file"
            ref={fileInputRef}
            type="file"
            accept={ACCEPT}
            onChange={handleFileChosen}
            disabled={busy}
          />
        </div>
      </form>

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      {resumes == null ? (
        <p className="muted">Loading…</p>
      ) : resumes.length === 0 ? (
        <p className="muted">
          No resumes yet. Upload one to enable the AI match step — runs will still work without
          it, they just won't be scored.
        </p>
      ) : (
        <ul className="resume-list">
          {resumes.map((r) => (
            <li key={r.id} className={r.isDefault ? "resume-item default" : "resume-item"}>
              <div className="resume-main">
                <span className="resume-name">{r.name}</span>
                {r.isDefault && <span className="resume-badge">Default</span>}
                <span className="resume-meta">
                  {r.originalFilename} · {r.charCount.toLocaleString("en-US")} chars ·{" "}
                  {absoluteTime(r.uploadedAt)}
                </span>
              </div>
              <div className="resume-actions">
                <button type="button" onClick={() => handlePreview(r.id)}>
                  {preview?.id === r.id ? "Hide text" : "View text"}
                </button>
                {!r.isDefault && (
                  <button type="button" onClick={() => handleSetDefault(r.id)} disabled={busy}>
                    Make default
                  </button>
                )}
                <button type="button" onClick={() => handleDelete(r.id)} disabled={busy}>
                  Delete
                </button>
              </div>
              {preview?.id === r.id && (
                <pre className="resume-preview">{preview.text}</pre>
              )}
            </li>
          ))}
        </ul>
      )}

      <ApplicantProfileForm />
    </section>
  );
}
