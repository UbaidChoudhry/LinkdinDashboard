import { useCallback, useEffect, useState } from "react";
import type { ChangeEvent, FormEvent } from "react";
import { ApiError, getProfile, saveProfile } from "../api/client";
import type { ApplicantProfile } from "../types/api";

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
      <h2>Applicant profile</h2>
      <p className="panel-hint">
        Used by Apply with Claude to fill application forms; the resume covers the rest.
      </p>

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
        <div className="field-row">
          <label htmlFor="ap-extra">Other answers</label>
          <textarea id="ap-extra" rows={5} value={profile.extraAnswers} onChange={field("extraAnswers")} />
          <p className="panel-hint">
            How Claude should answer questions the resume doesn't cover, e.g. notice period,
            relocation, preferred start date
          </p>
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
    </section>
  );
}
