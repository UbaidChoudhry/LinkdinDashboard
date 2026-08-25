import type {
  CompanyBlocklistResponse,
  CompanyVolumeResponse,
  CreateRunRequest,
  ErrorResponse,
  JobResponse,
  JobTab,
  RunIdResponse,
  RunResponse,
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

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      headers: init?.body ? { "Content-Type": "application/json" } : undefined,
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
  return JSON.parse(text) as T;
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

export function listJobs(tab: JobTab, includePreviousRuns: boolean): Promise<JobResponse[]> {
  const params = new URLSearchParams({ tab, includePreviousRuns: String(includePreviousRuns) });
  return request<JobResponse[]>(`/api/jobs?${params.toString()}`);
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
