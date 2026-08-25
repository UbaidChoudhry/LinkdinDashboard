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
}

export type RunStatus =
  | "running"
  | "ok"
  | "capped"
  | "budget_exhausted"
  | "blocked"
  | "failed"
  | "cancelled";

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
}

export interface CreateRunRequest {
  keywords: string;
  hours?: number;
  location?: string;
  testMode?: boolean;
  pageCap?: number;
  useShards?: boolean;
  shards?: string[];
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
