import type {
  AtsCompanyPage,
  AtsCompanyResponse,
  ClearJobDataResponse,
  CompanyBlocklistResponse,
  CompanyVolumeResponse,
  CreateRunRequest,
  DataStatsResponse,
  ErrorResponse,
  JobResponse,
  JobTab,
  JobSourceName,
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

export function listRuns(): Promise<RunResponse[]> {
  return request<RunResponse[]>("/api/runs");
}

export function getRun(id: number): Promise<RunResponse> {
  return request<RunResponse>(`/api/runs/${id}`);
}

export function cancelRun(id: number): Promise<void> {
  return request<void>(`/api/runs/${id}/cancel`, { method: "POST" });
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
