// Types mirroring the backend DTOs in src/main/java/com/ubaid/jobdash/web/dto/*.java.
// Keep these in sync with the backend if the API changes.

export type FilterVerdict = "PASS" | "REJECT" | string;
export type UserStatus = "applied" | "not_interested";

export interface JobResponse {
  jobId: number;
  title: string;
  company: string;
  location: string;
  postedAt: string | null;
  firstSeenAt: string | null;
  lastSeenAt: string | null;
  lastSeenRunId: number;
  jobUrl: string;
  companyUrl: string | null;
  filterVerdict: string | null;
  rejectReason: string | null;
  userStatus: string | null;
  userStatusAt: string | null;
  detailStatus: string | null;
  applyUrl: string | null;
  applyDomain: string | null;
  /**
   * LinkedIn-only: "onsite" means the posting is LinkedIn Easy Apply; "offsite" means it links out
   * to an external application (which the run may or may not have matched to `applyUrl`/
   * `applyDomain` below). Null for non-LinkedIn rows and rows not yet classified.
   */
  applyKind?: string | null;
  /** Notes on how `applyUrl`/`applyDomain` were matched to the company's own ATS board, if at all. */
  applyMatchNote?: string | null;
  salaryMin: number | null;
  salaryMax: number | null;
  salarySource?: string | null;
  // For `salarySource === "lca"`, the LCA employer legal entity the salary was
  // actually matched from (e.g. "AMAZON.COM SERVICES LLC"). Null for non-LCA
  // sources and for rows imported before this field existed.
  salarySourceDetail?: string | null;
  /** Which job board this row came from: "linkedin" | "greenhouse" | "lever" | "workday". */
  source?: string | null;
  /**
   * AI match verdict against the resume the request was scoped to. Null means "not scanned":
   * the row had no description when its run's scan phase ran. For a LinkedIn row that means its
   * detail fragment was not fetched (outside the run's detail cap, or the fetch was blocked) -
   * see `detailStatus`.
   */
  aiRecommended?: boolean | null;
  aiReason?: string | null;
  /**
   * Claude's judgement of the free-form location string. Both null means "not classified yet",
   * which is NOT the same as "not in the US" - such rows stay visible and are retried next run.
   * `locationConfident === false` means Claude answered but the string does not actually say
   * where the job is (a "11 Locations" placeholder, a bare "San Jose"); those are flagged, not
   * hidden. A confidently non-US row never reaches the client at all.
   */
  locationUs?: boolean | null;
  locationConfident?: boolean | null;
  /** Status of the most recent Apply with Claude attempt on this job, if any. */
  applicationStatus?: ApplicationStatus | null;
  /** The model's notes on that attempt (summary / unanswered questions / error). */
  applicationNotes?: string | null;
}

export type RunStatus =
  | "running"
  | "fetching_details"
  | "scanning"
  | "matching"
  | "no_sources"
  | "ok"
  | "capped"
  | "budget_exhausted"
  | "blocked"
  | "failed"
  | "cancelled"
  | "interrupted";

export interface RunResponse {
  id: number;
  startedAt: string | null;
  finishedAt: string | null;
  status: RunStatus | string;
  keywords: string;
  location: string | null;
  hours: number;
  testMode: boolean;
  pageCap: number | null;
  shardsUsed: string | null;
  currentShard: string | null;
  pagesFetched: number;
  requestsMade: number;
  cardsSeen: number;
  jobsNew: number;
  saturated: boolean;
  /** Comma-separated sources this run pulled from, e.g. "greenhouse,lever". */
  sources?: string | null;
  /** Companies visited so far (ATS runs); null for LinkedIn runs. */
  companiesDone?: number | null;
  companiesTotal?: number | null;
  /** Job-detail fragments fetched so far (LinkedIn runs); 0 for ATS runs. */
  detailsDone?: number | null;
  /** Rows queued for detail fetching this run (LinkedIn runs); 0 for ATS runs. */
  detailsTotal?: number | null;
  /** Live AI-scan progress while status is "scanning"; null before the scan phase. */
  scan?: ScanProgress | null;
  /**
   * For a FINISHED run: how many of its passing LinkedIn rows still have no description because
   * the detail fetch never reached them (blocked by a cooldown, capped, out of budget). These are
   * what "Retry" on the run panel fetches before re-running the scan. Null while in flight.
   */
  unfetchedDescriptions?: number | null;
}

/** The LinkedIn cooldown (circuit breaker), from GET /api/runs/cooldown. Mirrors CooldownResponse.java. */
export interface CooldownResponse {
  active: boolean;
  until: string | null;
  remainingSeconds: number;
}

/** Live progress of the AI match scan. Mirrors ai/ScanProgress.java. */
export interface ScanProgress {
  batchesDone: number;
  batchesTotal: number;
  jobsScanned: number;
  jobsTotal: number;
  recommended: number;
  notRecommended: number;
  failedBatches: number;
  costUsd: number;
  startedAt: string | null;
}

export interface CreateRunRequest {
  keywords: string;
  hours?: number;
  location?: string;
  testMode?: boolean;
  pageCap?: number;
  useShards?: boolean;
  shards?: string[];
  /** Which sources to pull from. Omitted means LinkedIn, preserving the previous behaviour. */
  sources?: JobSourceName[];
  /** Resume to run the AI match against. Null/omitted uses the default resume. */
  resumeId?: number | null;
  /** Restrict ATS results to US locations. Omitted means true - it is opt-OUT. */
  usOnly?: boolean;
}

export interface RunIdResponse {
  runId: number;
}

export interface CompanyBlocklistResponse {
  company: string;
  reason: string | null;
  evidence: string | null;
  addedAt: string | null;
}

export interface CompanyVolumeResponse {
  company: string;
  postings: number;
  titles: number;
  locations: number;
}

export interface ErrorResponse {
  message: string;
}

export type JobTab = "search" | "applied" | "not_interested";

export interface DataStatsResponse {
  databaseSizeBytes: number;
  totalJobs: number;
  jobsPassed: number;
  jobsRejected: number;
  jobsUnevaluated: number;
  jobsApplied: number;
  jobsNotInterested: number;
  jobsUntriaged: number;
  totalRuns: number;
  requestLogEntries: number;
  requestsLast24h: number;
  excludeWordCount: number;
  blockedCompanyCount: number;
  oldestFirstSeenAt: string | null;
  newestLastSeenAt: string | null;
}

export interface ClearJobDataResponse {
  jobsCleared: number;
  runsCleared: number;
  databaseSizeBytesAfter: number;
}

/** Result of a bulk job action (status update or delete). Mirrors web/dto/JobBulkActionResponse.java. */
export interface JobBulkActionResponse {
  count: number;
}

// ---------------------------------------------------------------------------
// Job sources (LinkedIn + ATS boards) and AI resume matching
// ---------------------------------------------------------------------------

/** Every source a run can pull from. Any combination is valid in one run. */
export type JobSourceName = "linkedin" | "greenhouse" | "lever" | "workday";

export const ATS_SOURCES: JobSourceName[] = ["greenhouse", "lever", "workday"];

export const SOURCE_LABELS: Record<JobSourceName, string> = {
  linkedin: "LinkedIn",
  greenhouse: "Greenhouse",
  lever: "Lever",
  workday: "Workday",
};


/** A company in the ATS slug catalog. Mirrors web/dto/AtsCompanyResponse.java. */
export interface AtsCompanyResponse {
  id: number;
  ats: JobSourceName;
  slug: string;
  company: string;
  host: string | null;
  site: string | null;
  enabled: boolean;
  status: "unverified" | "active" | "dead" | string;
  consecutiveFailures: number;
  lastCheckedAt: string | null;
  lastOkAt: string | null;
  lastJobCount: number | null;
  addedAt: string | null;
}

/** Mirrors web/dto/SourcePageResponse.java. */
export interface AtsCompanyPage {
  items: AtsCompanyResponse[];
  total: number;
}

/** Mirrors web/dto/SourceSummaryResponse.java. */
export interface SourceSummaryResponse {
  byAts: Record<string, number>;
  byStatus: Record<string, number>;
}

/** An uploaded resume. Mirrors web/dto/ResumeResponse.java - `contentText` is deliberately absent. */
export interface ResumeResponse {
  id: number;
  name: string;
  originalFilename: string;
  charCount: number;
  isDefault: boolean;
  uploadedAt: string | null;
}

/** Which bucket the AI scan put a job in. Null when the job has not been scanned. */
export type MatchBucket = "recommended" | "not_recommended";

/** Summary of one AI scan. Mirrors ai/ResumeMatchService.ScanResult. */
export interface ScanResultResponse {
  scanned: number;
  recommended: number;
  notRecommended: number;
  skipped: number;
  failedBatches: number;
  totalCostUsd: number;
  errorMessage: string | null;
}

// ---------------------------------------------------------------------------
// Apply with Claude - applicant profile and apply batches
// ---------------------------------------------------------------------------

/** The single applicant profile row. Mirrors domain/ApplicantProfile.java. */
export interface ApplicantProfile {
  fullName: string;
  email: string;
  phone: string;
  location: string;
  linkedinUrl: string;
  portfolioUrl: string;
  workAuthorization: string;
  requiresSponsorship: boolean;
  salaryExpectation: string;
  extraAnswers: string;
  updatedAt: string | null;
}

/** Per-job outcome of an apply batch. Mirrors web/dto/JobApplicationResponse.java. */
export type ApplicationStatus = "queued" | "filling" | "submitted" | "needs_review" | "failed" | "skipped";

/** Mirrors web/dto/JobApplicationResponse.java. */
export interface JobApplicationResponse {
  id: number;
  jobId: number;
  title: string;
  company: string;
  status: ApplicationStatus;
  notes: string;
  costUsd: number;
  startedAt: string | null;
  finishedAt: string | null;
  /** Claude Code session id backing this job's attempt, if any. Lets the user continue that
   * exact session in a terminal with `claude --resume <sessionId> --chrome`. */
  sessionId: string | null;
  /** The most recent thing Claude did, e.g. `tool mcp__claude-in-chrome__find {...}` or
   * `tool_error ...`. Empty when there's nothing to show yet. */
  lastActivity: string;
  /** Whether a transcript is available at the log endpoint below. */
  hasLog: boolean;
}

/** Mirrors web/dto/ApplyBatchResponse.java. */
export interface ApplyBatchResponse {
  id: number;
  resumeId: number;
  submit: boolean;
  status: "running" | "ok" | "cancelled" | "failed" | "interrupted";
  total: number;
  done: number;
  submitted: number;
  needsReview: number;
  failed: number;
  skipped: number;
  costUsd: number;
  startedAt: string;
  finishedAt: string | null;
  jobs: JobApplicationResponse[];
  /** How many new profile questions this batch left pending for the user to answer. */
  newQuestions: number;
  /** Whether an end-of-run markdown report is available at GET .../report. */
  hasReport: boolean;
}

/**
 * A saved answer to a question Claude has hit while filling out an application (or one the user
 * added by hand). Replaces the old free-text ApplicantProfile.extraAnswers field: when Claude
 * meets a question it cannot answer it records it here as "pending" (with the company it came
 * from and how many times it's been asked); the user answers it once and every later application
 * reuses it. Mirrors web/dto/ProfileAnswerResponse.java.
 */
export interface ProfileAnswer {
  id: number;
  question: string;
  answer: string;
  status: "answered" | "pending";
  askedCount: number;
  lastJobId: number | null;
  lastCompany: string;
  createdAt: string;
  updatedAt: string;
}
