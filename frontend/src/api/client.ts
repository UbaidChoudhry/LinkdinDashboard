import type {
  ApplicantProfile,
  ApplyBatchResponse,
  AtsCompanyPage,
  AtsCompanyResponse,
  ClearJobDataResponse,
  CompanyBlocklistResponse,
  CompanyVolumeResponse,
  CooldownResponse,
  CreateRunRequest,
  DataStatsResponse,
  ErrorResponse,
  JobBulkActionResponse,
  JobResponse,
  JobTab,
  JobSourceName,
  ProfileAnswer,
  ResumeResponse,
  RunIdResponse,
  RunResponse,
  ScanResultResponse,
  SourceSummaryResponse,
} from "../types/api";

/** Thrown for any non-2xx API response. Always carries a user-facing message. */
export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

async function request<T>(path: string, init?: RequestInit, rawText = false): Promise<T> {
  let res: Response;
  // FormData must be sent WITHOUT an explicit Content-Type: the browser generates a multipart
  // boundary and puts it in that header itself, and setting the header by hand strips the
  // boundary, leaving the server unable to parse the upload.
  const isFormData = typeof FormData !== "undefined" && init?.body instanceof FormData;
  try {
    res = await fetch(path, {
      headers: init?.body && !isFormData ? { "Content-Type": "application/json" } : undefined,
      ...init,
    });
  } catch {
    throw new ApiError(0, "Could not reach the server. Check your connection and try again.");
  }

  if (!res.ok) {
    let message = `Request failed with status ${res.status}.`;
    try {
      const body = (await res.json()) as ErrorResponse;
      if (body?.message) {
        message = body.message;
      }
    } catch {
      // body wasn't JSON - fall back to the generic message above
    }
    throw new ApiError(res.status, message);
  }

  if (res.status === 204 || res.status === 202) {
    return undefined as T;
  }

  const text = await res.text();
  if (!text) {
    return undefined as T;
  }
  // GET /api/resumes/{id}/text returns text/plain, not JSON - parsing it would throw.
  return (rawText ? text : JSON.parse(text)) as T;
}

export function createRun(body: CreateRunRequest): Promise<RunIdResponse> {
  return request<RunIdResponse>("/api/runs", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/** Newest-first. `limit` mirrors the backend's optional query param (default 20 server-side). */
export function listRuns(limit?: number): Promise<RunResponse[]> {
  const params = limit != null ? `?${new URLSearchParams({ limit: String(limit) }).toString()}` : "";
  return request<RunResponse[]>(`/api/runs${params}`);
}

export function getRun(id: number): Promise<RunResponse> {
  return request<RunResponse>(`/api/runs/${id}`);
}

export function cancelRun(id: number): Promise<void> {
  return request<void>(`/api/runs/${id}/cancel`, { method: "POST" });
}

/** Re-opens a finished run to fetch the descriptions it never read and re-run the AI scan. */
export function resumeRun(id: number): Promise<RunIdResponse> {
  return request<RunIdResponse>(`/api/runs/${id}/resume`, { method: "POST" });
}

export function getCooldown(): Promise<CooldownResponse> {
  return request<CooldownResponse>("/api/runs/cooldown");
}

export function listJobs(
  tab: JobTab,
  includePreviousRuns: boolean,
  resumeId?: number | null,
): Promise<JobResponse[]> {
  const params = new URLSearchParams({ tab, includePreviousRuns: String(includePreviousRuns) });
  // Scopes the AI verdicts on each row to one resume; omitted means the default resume.
  if (resumeId != null) params.set("resumeId", String(resumeId));
  return request<JobResponse[]>(`/api/jobs?${params.toString()}`);
}

/** Runs (or re-runs) the AI match scan. Returns the scan summary. */
export function scanMatches(body: {
  runId?: number | null;
  resumeId?: number | null;
  jobIds?: number[] | null;
}): Promise<ScanResultResponse> {
  return request<ScanResultResponse>("/api/matches/scan", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function setJobStatus(id: number, status: "applied" | "not_interested" | null): Promise<JobResponse> {
  return request<JobResponse>(`/api/jobs/${id}/status`, {
    method: "POST",
    body: JSON.stringify({ status }),
  });
}

/** Bulk counterpart to {@link setJobStatus}: applies one status to every id in one call. */
export function bulkSetJobStatus(
  jobIds: number[],
  status: "applied" | "not_interested" | null,
): Promise<JobBulkActionResponse> {
  return request<JobBulkActionResponse>("/api/jobs/bulk-status", {
    method: "POST",
    body: JSON.stringify({ jobIds, status }),
  });
}

/** Permanently deletes every job in `jobIds`. Cannot be undone. */
export function bulkDeleteJobs(jobIds: number[]): Promise<JobBulkActionResponse> {
  return request<JobBulkActionResponse>("/api/jobs/bulk-delete", {
    method: "POST",
    body: JSON.stringify({ jobIds }),
  });
}

export function listExcludeWords(): Promise<string[]> {
  return request<string[]>("/api/filters/words");
}

export function addExcludeWord(word: string): Promise<string[]> {
  return request<string[]>("/api/filters/words", {
    method: "POST",
    body: JSON.stringify({ word }),
  });
}

export function deleteExcludeWord(word: string): Promise<string[]> {
  return request<string[]>(`/api/filters/words/${encodeURIComponent(word)}`, {
    method: "DELETE",
  });
}

export function listBlockedCompanies(): Promise<CompanyBlocklistResponse[]> {
  return request<CompanyBlocklistResponse[]>("/api/filters/companies");
}

export function addBlockedCompany(
  company: string,
  reason?: string,
  evidence?: string,
): Promise<CompanyBlocklistResponse[]> {
  return request<CompanyBlocklistResponse[]>("/api/filters/companies", {
    method: "POST",
    body: JSON.stringify({ company, reason, evidence }),
  });
}

export function deleteBlockedCompany(company: string): Promise<CompanyBlocklistResponse[]> {
  return request<CompanyBlocklistResponse[]>(`/api/filters/companies/${encodeURIComponent(company)}`, {
    method: "DELETE",
  });
}

export function companyVolumeReport(days: number, threshold: number): Promise<CompanyVolumeResponse[]> {
  const params = new URLSearchParams({ days: String(days), threshold: String(threshold) });
  return request<CompanyVolumeResponse[]>(`/api/reports/company-volume?${params.toString()}`);
}

export function getDataStats(): Promise<DataStatsResponse> {
  return request<DataStatsResponse>("/api/data/stats");
}

export function clearJobData(): Promise<ClearJobDataResponse> {
  return request<ClearJobDataResponse>("/api/data/clear", { method: "POST" });
}

// --- Resumes -------------------------------------------------------------
// Upload goes through FormData rather than request(), which always sends JSON: the browser
// must set its own multipart boundary, so Content-Type is deliberately left unset here.

export async function uploadResume(file: File, name?: string): Promise<ResumeResponse> {
  const form = new FormData();
  form.append("file", file);
  if (name?.trim()) {
    form.append("name", name.trim());
  }
  return request<ResumeResponse>("/api/resumes", { method: "POST", body: form });
}

export function listResumes(): Promise<ResumeResponse[]> {
  return request<ResumeResponse[]>("/api/resumes");
}

export function setDefaultResume(id: number): Promise<void> {
  return request<void>(`/api/resumes/${id}/default`, { method: "POST" });
}

export function deleteResume(id: number): Promise<void> {
  return request<void>(`/api/resumes/${id}`, { method: "DELETE" });
}

export function getResumeText(id: number): Promise<string> {
  return request<string>(`/api/resumes/${id}/text`, undefined, true);
}

// --- ATS company sources -------------------------------------------------

export function listSources(params: {
  ats?: JobSourceName | "";
  search?: string;
  enabledOnly?: boolean;
  limit?: number;
  offset?: number;
}): Promise<AtsCompanyPage> {
  const q = new URLSearchParams();
  if (params.ats) q.set("ats", params.ats);
  if (params.search?.trim()) q.set("search", params.search.trim());
  if (params.enabledOnly) q.set("enabledOnly", "true");
  q.set("limit", String(params.limit ?? 50));
  q.set("offset", String(params.offset ?? 0));
  return request<AtsCompanyPage>(`/api/sources?${q.toString()}`);
}

export function getSourceSummary(): Promise<SourceSummaryResponse> {
  return request<SourceSummaryResponse>("/api/sources/summary");
}

export function setSourceEnabled(id: number, enabled: boolean): Promise<AtsCompanyResponse> {
  return request<AtsCompanyResponse>(`/api/sources/${id}/enabled`, {
    method: "POST",
    body: JSON.stringify({ enabled }),
  });
}

export function addSource(body: {
  ats: JobSourceName;
  slug?: string;
  company?: string;
  host?: string;
  site?: string;
  careersUrl?: string;
}): Promise<AtsCompanyResponse> {
  return request<AtsCompanyResponse>("/api/sources", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function deleteSource(id: number): Promise<void> {
  return request<void>(`/api/sources/${id}`, { method: "DELETE" });
}

// --- Apply with Claude -----------------------------------------------------
// GET /api/profile and GET /api/applications/current both return 204 (no body) when there is
// nothing saved/running yet - request() maps that to `undefined`, so these wrap it as `null`.

export async function getProfile(): Promise<ApplicantProfile | null> {
  const profile = await request<ApplicantProfile | undefined>("/api/profile");
  return profile ?? null;
}

export function saveProfile(profile: Omit<ApplicantProfile, "updatedAt">): Promise<ApplicantProfile> {
  return request<ApplicantProfile>("/api/profile", {
    method: "PUT",
    body: JSON.stringify(profile),
  });
}

export function startApplications(body: {
  jobIds: number[];
  resumeId?: number | null;
  submit: boolean;
}): Promise<{ batchId: number }> {
  return request<{ batchId: number }>("/api/applications", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function getApplyBatch(id: number): Promise<ApplyBatchResponse> {
  return request<ApplyBatchResponse>(`/api/applications/${id}`);
}

export async function getCurrentApplyBatch(): Promise<ApplyBatchResponse | null> {
  const batch = await request<ApplyBatchResponse | undefined>("/api/applications/current");
  return batch ?? null;
}

export function cancelApplyBatch(id: number): Promise<void> {
  return request<void>(`/api/applications/${id}/cancel`, { method: "POST" });
}

/** URL for the plain-text transcript of one job's apply attempt (404 when there is none). The
 * UI opens this directly in a new tab rather than fetching it. */
export function applicationLogUrl(batchId: number, applicationId: number): string {
  return `/api/applications/${batchId}/jobs/${applicationId}/log`;
}

/** The end-of-run markdown report for a batch (404 when it has none). */
export function getApplyReport(batchId: number): Promise<string> {
  return request<string>(`/api/applications/${batchId}/report`, undefined, true);
}

// --- Profile answers (question/answer table replacing ApplicantProfile.extraAnswers) ------

/** Pending rows first. */
export function listProfileAnswers(): Promise<ProfileAnswer[]> {
  return request<ProfileAnswer[]>("/api/profile/answers");
}

/** 201 for a new question, 200 if it already existed and was updated. */
export function addProfileAnswer(question: string, answer: string): Promise<ProfileAnswer> {
  return request<ProfileAnswer>("/api/profile/answers", {
    method: "POST",
    body: JSON.stringify({ question, answer }),
  });
}

/** A blank answer flips the row back to "pending". */
export function setProfileAnswer(id: number, answer: string): Promise<ProfileAnswer> {
  return request<ProfileAnswer>(`/api/profile/answers/${id}`, {
    method: "PUT",
    body: JSON.stringify({ answer }),
  });
}

export function deleteProfileAnswer(id: number): Promise<void> {
  return request<void>(`/api/profile/answers/${id}`, { method: "DELETE" });
}
