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
   * AI match verdict against the resume the request was scoped to. Null means "not scanned" -
   * which is the permanent state for LinkedIn rows, since they carry no description to compare.
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
}

export type RunStatus =
  | "running"
  | "scanning"
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
  /** Live AI-scan progress while status is "scanning"; null before the scan phase. */
  scan?: ScanProgress | null;
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

// ---------------------------------------------------------------------------
// Job sources (LinkedIn + ATS boards) and AI resume matching
// ---------------------------------------------------------------------------

/** Every source a run can pull from. LinkedIn is mutually exclusive with the rest. */
export type JobSourceName = "linkedin" | "greenhouse" | "lever" | "workday";

export const ATS_SOURCES: JobSourceName[] = ["greenhouse", "lever", "workday"];

export const SOURCE_LABELS: Record<JobSourceName, string> = {
  linkedin: "LinkedIn",
  greenhouse: "Greenhouse",
  lever: "Lever",
  workday: "Workday",
};

/**
 * LinkedIn's guest search returns no job description, so there is nothing for the AI scan to
 * compare a resume against - which is why it cannot be combined with the ATS sources.
 */
export function isLinkedInExclusive(sources: JobSourceName[]): boolean {
  return sources.includes("linkedin") && sources.length > 1;
}

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
