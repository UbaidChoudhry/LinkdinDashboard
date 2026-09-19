import { useCallback, useEffect, useState } from "react";
import type { ChangeEvent, FormEvent } from "react";
import {
  addProfileAnswer,
  ApiError,
  deleteProfileAnswer,
  getProfile,
  listProfileAnswers,
  saveProfile,
  setProfileAnswer,
} from "../api/client";
import type { ApplicantProfile, ProfileAnswer } from "../types/api";
import { InfoTip } from "./InfoTip";

const EMPTY_PROFILE: Omit<ApplicantProfile, "updatedAt"> = {
  fullName: "",
  email: "",
  phone: "",
  location: "",
  linkedinUrl: "",
  portfolioUrl: "",
  workAuthorization: "",
  requiresSponsorship: false,
  salaryExpectation: "",
  extraAnswers: "",
};

type SaveState = "idle" | "saving" | "saved" | "error";

/**
 * Used by Apply with Claude to fill application forms; the resume covers the rest.
 */
export function ApplicantProfileForm() {
  const [profile, setProfile] = useState<Omit<ApplicantProfile, "updatedAt">>(EMPTY_PROFILE);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [saveError, setSaveError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const existing = await getProfile();
      if (existing) {
        const { updatedAt: _updatedAt, ...rest } = existing;
        setProfile(rest);
      }
    } catch (err) {
      setLoadError(err instanceof ApiError ? err.message : "Failed to load the applicant profile.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function field(key: keyof Omit<ApplicantProfile, "updatedAt" | "requiresSponsorship">) {
    return (e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
      setProfile((prev) => ({ ...prev, [key]: e.target.value }));
      setSaveState("idle");
    };
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setSaveState("saving");
    setSaveError(null);
    try {
      const saved = await saveProfile(profile);
      const { updatedAt: _updatedAt, ...rest } = saved;
      setProfile(rest);
      setSaveState("saved");
    } catch (err) {
      setSaveError(err instanceof ApiError ? err.message : "Failed to save the applicant profile.");
      setSaveState("error");
    }
  }

  if (loading) {
    return (
      <section className="applicant-profile-form">
        <h2>Applicant profile</h2>
        <p className="muted">Loading…</p>
      </section>
    );
  }

  return (
    <section className="applicant-profile-form">
      <h2 className="section-heading">
        Applicant profile
        <InfoTip label="About the applicant profile">
          Used by Apply with Claude to fill application forms; the resume covers the rest.
        </InfoTip>
      </h2>

      {loadError && (
        <p className="form-error" role="alert">
          {loadError}
        </p>
      )}

      <form onSubmit={handleSubmit}>
        <div className="field-row">
          <label htmlFor="ap-full-name">Full name</label>
          <input id="ap-full-name" type="text" value={profile.fullName} onChange={field("fullName")} />
        </div>
        <div className="field-row">
          <label htmlFor="ap-email">Email</label>
          <input id="ap-email" type="email" value={profile.email} onChange={field("email")} />
        </div>
        <div className="field-row">
          <label htmlFor="ap-phone">Phone</label>
          <input id="ap-phone" type="text" value={profile.phone} onChange={field("phone")} />
        </div>
        <div className="field-row">
          <label htmlFor="ap-location">Location</label>
          <input id="ap-location" type="text" value={profile.location} onChange={field("location")} />
        </div>
        <div className="field-row">
          <label htmlFor="ap-linkedin">LinkedIn URL</label>
          <input id="ap-linkedin" type="text" value={profile.linkedinUrl} onChange={field("linkedinUrl")} />
        </div>
        <div className="field-row">
          <label htmlFor="ap-portfolio">Portfolio URL</label>
          <input id="ap-portfolio" type="text" value={profile.portfolioUrl} onChange={field("portfolioUrl")} />
        </div>
        <div className="field-row">
          <label htmlFor="ap-work-auth">Work authorization</label>
          <textarea
            id="ap-work-auth"
            rows={2}
            value={profile.workAuthorization}
            onChange={field("workAuthorization")}
          />
        </div>
        <div className="field-row checkbox-row">
          <input
            id="ap-sponsorship"
            type="checkbox"
            checked={profile.requiresSponsorship}
            onChange={(e) => {
              setProfile((prev) => ({ ...prev, requiresSponsorship: e.target.checked }));
              setSaveState("idle");
            }}
          />
          <label htmlFor="ap-sponsorship">Requires visa sponsorship</label>
        </div>
        <div className="field-row">
          <label htmlFor="ap-salary">Salary expectation</label>
          <input
            id="ap-salary"
            type="text"
            value={profile.salaryExpectation}
            onChange={field("salaryExpectation")}
          />
        </div>
        {saveError && (
          <p className="form-error" role="alert">
            {saveError}
          </p>
        )}

        <button type="submit" disabled={saveState === "saving"}>
          {saveState === "saving" ? "Saving…" : saveState === "saved" ? "Saved" : "Save"}
        </button>
      </form>

      <ProfileAnswersTable />
    </section>
  );
}

type RowSaveState = "idle" | "saving" | "saved" | "error";

function answerStatusLabel(row: ProfileAnswer): string {
  if (row.status === "pending") {
    const last = row.lastCompany ? row.lastCompany : "unknown";
    return `Pending · asked ${row.askedCount}× · last: ${last}`;
  }
  return "Answered";
}

function AnswerRow({
  row,
  onSave,
  onDelete,
}: {
  row: ProfileAnswer;
  onSave: (id: number, answer: string) => Promise<void>;
  onDelete: (id: number) => void;
}) {
  const [draft, setDraft] = useState(row.answer);
  const [rowState, setRowState] = useState<RowSaveState>("idle");
  const [rowError, setRowError] = useState<string | null>(null);

  useEffect(() => {
    setDraft(row.answer);
    setRowState("idle");
  }, [row.answer]);

  async function commit() {
    if (draft === row.answer) return;
    setRowState("saving");
    setRowError(null);
    try {
      await onSave(row.id, draft);
      setRowState("saved");
    } catch (err) {
      setRowError(err instanceof ApiError ? err.message : "Failed to save the answer.");
      setRowState("error");
    }
  }

  return (
    <tr className={`qa-row ${row.status === "pending" ? "pending" : ""}`}>
      <td>{row.question}</td>
      <td>
        <input
          type="text"
          value={draft}
          onChange={(e) => {
            setDraft(e.target.value);
            setRowState("idle");
          }}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              (e.target as HTMLInputElement).blur();
            }
          }}
        />
        {rowState === "saving" && <span className="qa-row-status">Saving…</span>}
        {rowState === "saved" && <span className="qa-row-status">Saved</span>}
        {rowState === "error" && rowError && (
          <p className="form-error" role="alert">
            {rowError}
          </p>
        )}
      </td>
      <td>{answerStatusLabel(row)}</td>
      <td>
        <button type="button" className="secondary" onClick={() => onDelete(row.id)}>
          Delete
        </button>
      </td>
    </tr>
  );
}

function ProfileAnswersTable() {
  const [answers, setAnswers] = useState<ProfileAnswer[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [newQuestion, setNewQuestion] = useState("");
  const [newAnswer, setNewAnswer] = useState("");
  const [addState, setAddState] = useState<RowSaveState>("idle");
  const [addError, setAddError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      setAnswers(await listProfileAnswers());
    } catch (err) {
      setLoadError(err instanceof ApiError ? err.message : "Failed to load questions & answers.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function handleSaveAnswer(id: number, answer: string) {
    const updated = await setProfileAnswer(id, answer);
    setAnswers((prev) => prev.map((a) => (a.id === id ? updated : a)));
  }

  async function handleDelete(id: number) {
    setAnswers((prev) => prev.filter((a) => a.id !== id));
    try {
      await deleteProfileAnswer(id);
    } catch {
      // Reload to recover the true state if the delete didn't actually happen.
      load();
    }
  }

  async function handleAdd(e: FormEvent) {
    e.preventDefault();
    if (!newQuestion.trim()) return;
    setAddState("saving");
    setAddError(null);
    try {
      await addProfileAnswer(newQuestion.trim(), newAnswer);
      setNewQuestion("");
      setNewAnswer("");
      setAddState("idle");
      await load();
    } catch (err) {
      setAddError(err instanceof ApiError ? err.message : "Failed to add the question.");
      setAddState("error");
    }
  }

  return (
    <section className="qa-section">
      <h2 className="section-heading">
        Questions &amp; answers
        <InfoTip label="About questions and answers">
          Claude uses these verbatim when an application asks the same question, however it is
          worded. Questions it could not answer land here as Pending - answer them once.
        </InfoTip>
      </h2>

      {loadError && (
        <p className="form-error" role="alert">
          {loadError}
        </p>
      )}

      {loading ? (
        <p className="muted">Loading…</p>
      ) : answers.length === 0 ? (
        <p className="muted">No questions yet.</p>
      ) : (
        <div className="job-table-scroll">
          <table className="job-table qa-table">
            <thead>
              <tr>
                <th>Question</th>
                <th>Answer</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {answers.map((row) => (
                <AnswerRow key={row.id} row={row} onSave={handleSaveAnswer} onDelete={handleDelete} />
              ))}
            </tbody>
          </table>
        </div>
      )}

      <form className="qa-add-row" onSubmit={handleAdd}>
        <input
          type="text"
          placeholder="Question"
          value={newQuestion}
          onChange={(e) => setNewQuestion(e.target.value)}
        />
        <input
          type="text"
          placeholder="Answer"
          value={newAnswer}
          onChange={(e) => setNewAnswer(e.target.value)}
        />
        <button type="submit" disabled={addState === "saving" || !newQuestion.trim()}>
          {addState === "saving" ? "Adding…" : "Add question"}
        </button>
      </form>
      {addError && (
        <p className="form-error" role="alert">
          {addError}
        </p>
      )}
    </section>
  );
}
