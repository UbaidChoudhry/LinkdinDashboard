import { useState } from "react";
import { ApiError, setJobStatus } from "../api/client";
import type { JobResponse, JobTab } from "../types/api";
import { relativeTime, absoluteTime, looselySameCompany } from "../utils/format";

/** Part 1 never populates salary, so this reliably renders "-" today; kept correct for part 2. */
function formatSalary(min: number | null, max: number | null): string {
  if (min == null && max == null) return "–";
  const fmt = (n: number) => `$${Math.round(n).toLocaleString()}`;
  if (min != null && max != null && min !== max) return `${fmt(min)}–${fmt(max)}`;
  return fmt((max ?? min) as number);
}

const APPLY_DOMAIN_LABELS: Record<string, string> = {
  greenhouse: "Greenhouse",
  lever: "Lever",
  workday: "Workday",
};

const APPLICATION_STATUS_LABELS: Record<string, string> = {
  needs_review: "Needs review",
  submitted: "Submitted",
  failed: "Apply failed",
  skipped: "Apply manually",
  filling: "Applying…",
  queued: "Queued",
};

/**
 * The Match column: how sure the company-link finder is that the posting it found on the
 * employer's own site is this LinkedIn job. The badge links to that posting; only a match the
 * backend was confident enough to make the apply link (applyUrl) is used by Apply with Claude.
 */
function MatchCell({ job }: { job: JobResponse }) {
  if (job.source !== "linkedin") return <span className="muted">—</span>;
  const confidence = job.companyLinkConfidence;
  if (confidence == null) {
    return job.applyDomain ? (
      <span className="match-badge high" title="Read from LinkedIn's own Apply button">
        exact
      </span>
    ) : (
      <span className="muted" title="Not searched yet - use Find company links">
        —
      </span>
    );
  }
  const compared = job.companyLinkMatchedOn?.length ? `Agreed on: ${job.companyLinkMatchedOn.join(", ")}. ` : "";
  if (!job.companyLinkUrl) {
    return (
      <span className="match-badge none" title={`No posting found on the employer's site. ${job.companyLinkNote ?? ""}`}>
        none
      </span>
    );
  }
  const used = job.applyUrl === job.companyLinkUrl;
  const tier = confidence >= 80 ? "high" : confidence >= 60 ? "medium" : "low";
  const title =
    compared +
    (job.companyLinkNote ?? "") +
    (used
      ? "\nApply with Claude uses this link."
      : "\nToo uncertain to apply to automatically - open it, and if it's the right job paste it into the Apply tab.");
  return (
    <a
      className={used ? `match-badge ${tier}` : `match-badge ${tier} unused`}
      href={job.companyLinkUrl}
      target="_blank"
      rel="noreferrer"
      title={title}
    >
      {confidence}%
    </a>
  );
}

/** Human-readable label for the `salarySource` recorded by the backend enrichment step. */
function salarySourceLabel(source: string | null | undefined): string | null {
  switch (source) {
    case "lca":
      return "LCA disclosure data";
    case "adzuna":
      return "Adzuna estimate";
    case "h1bapi":
      return "H-1B data";
    case "posting":
      return "From job posting";
    default:
      return null;
  }
}

interface JobRowProps {
  job: JobResponse;
  tab: JobTab;
  onChanged: (updated: JobResponse) => void;
  onError: (message: string) => void;
  /** True when this row sits inside an expanded company group, which indents it. */
  grouped?: boolean;
  /** Whether this row's checkbox is checked, for the multi-select bulk actions. */
  selected: boolean;
  onToggleSelect: (jobId: number) => void;
}

export function JobRow({ job, tab, onChanged, onError, grouped = false, selected, onToggleSelect }: JobRowProps) {
  const [busy, setBusy] = useState(false);
  const sourceLabel = salarySourceLabel(job.salarySource);

  // For LCA salaries, show the legal entity the match landed on. Flag it when the
  // matched entity doesn't loosely read as the same company as the posting.
  const lcaEmployer = job.salarySource === "lca" ? job.salarySourceDetail ?? null : null;
  const lcaIsFuzzy = lcaEmployer != null && !looselySameCompany(lcaEmployer, job.company);

  let salaryCellTitle = sourceLabel ?? undefined;
  if (lcaEmployer) {
    salaryCellTitle = lcaIsFuzzy
      ? `Salary matched from LCA disclosure data filed by "${lcaEmployer}" — the posting lists "${job.company}".`
      : `Salary from LCA disclosure data filed by "${lcaEmployer}".`;
  }

  async function apply(status: "applied" | "not_interested" | null) {
    setBusy(true);
    try {
      const updated = await setJobStatus(job.jobId, status);
      onChanged(updated);
    } catch (err) {
      onError(err instanceof ApiError ? err.message : "Failed to update job status.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <tr className={grouped ? "grouped-job" : undefined}>
      <td className="col-select">
        <input
          type="checkbox"
          aria-label={`Select ${job.title}`}
          checked={selected}
          onChange={() => onToggleSelect(job.jobId)}
        />
      </td>
      <td className="col-title" title={job.title}>
        <a href={job.jobUrl} target="_blank" rel="noopener noreferrer">
          {job.title}
        </a>
        {job.source && (
          <span className="job-source-tag">{job.source}</span>
        )}
        {job.source === "linkedin" && job.applyDomain && (
          <a
            className="apply-target-link"
            href={job.applyUrl ?? undefined}
            target="_blank"
            rel="noreferrer"
            title={job.applyMatchNote ?? ""}
          >
            ↗ Apply on {APPLY_DOMAIN_LABELS[job.applyDomain] ?? job.applyDomain}
          </a>
        )}
        {job.source === "linkedin" && !job.applyDomain && job.applyKind === "onsite" && (
          <span className="apply-kind-tag" title="LinkedIn Easy Apply - no external apply link; apply manually">
            Easy Apply
          </span>
        )}
        {/* The AI verdict rides under the title rather than in its own column: the reason is a
            full sentence, and a column wide enough for it would squeeze everything else out. */}
        {job.aiRecommended != null && job.aiReason && (
          <span
            className={job.aiRecommended ? "ai-reason recommended" : "ai-reason not-recommended"}
            title={job.aiReason}
          >
            {job.aiRecommended ? "✓" : "✕"} {job.aiReason}
          </span>
        )}
        {job.applicationStatus && (
          <span
            className={`application-badge ${job.applicationStatus.replace(/_/g, "-")}`}
            title={job.applicationNotes ?? ""}
          >
            {APPLICATION_STATUS_LABELS[job.applicationStatus] ?? job.applicationStatus}
          </span>
        )}
      </td>
      <td className="col-company" title={job.company}>
        {job.company}
      </td>
      <td>
        {job.location}
        {/* Only flagged when Claude answered but could not place the string. A null verdict means
            "not classified yet" and gets no badge - that would mark almost every older row. */}
        {job.locationConfident === false && (
          <span
            className="location-uncertain"
            title={`Claude could not confidently place "${job.location}". It guessed ${
              job.locationUs ? "United States" : "outside the United States"
            }.`}
          >
            {" "}
            ⚠ uncertain
          </span>
        )}
      </td>
      <td title={absoluteTime(job.postedAt)}>{relativeTime(job.postedAt)}</td>
      <td className="col-salary" title={salaryCellTitle}>
        {formatSalary(job.salaryMin, job.salaryMax)}
        {lcaEmployer ? (
          <span
            className={lcaIsFuzzy ? "salary-source lca-employer fuzzy" : "salary-source lca-employer"}
          >
            {lcaIsFuzzy ? "≈ " : ""}LCA · {lcaEmployer}
          </span>
        ) : (
          sourceLabel && <span className="salary-source"> {sourceLabel}</span>
        )}
      </td>
      <td className="col-match">
        <MatchCell job={job} />
      </td>
      <td className="col-actions">
        {tab === "search" && (
          <>
            <button type="button" onClick={() => apply("applied")} disabled={busy}>
              Applied
            </button>
            <button type="button" onClick={() => apply("not_interested")} disabled={busy} className="secondary">
              Not interested
            </button>
          </>
        )}
        {(tab === "applied" || tab === "not_interested") && (
          <button type="button" onClick={() => apply(null)} disabled={busy} className="secondary">
            Move back to Search
          </button>
        )}
      </td>
    </tr>
  );
}
