import { getToken } from "@/lib/session";
import type {
  AuthBootstrap,
  CancellationOutcome,
  CoverageView,
  EdgeView,
  GraphView,
  HealthStatus,
  JobCounters,
  JobStatus,
  JobView,
  MappingView,
  OverviewView,
  PageResponse,
  RepositorySummary,
  RevisionLookup,
  SourceContentView,
  SourceFileView,
  SymbolView,
  Uuid,
  WorkspaceSummary,
} from "@/lib/types";

/** An error carrying the server's stable error code, so callers branch on codes, not prose. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId: string | null;

  constructor(status: number, code: string, message: string, correlationId: string | null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
  }

  /** True when the request was rejected for a missing or invalid token. */
  get isAuthFailure(): boolean {
    return this.status === 401 || this.status === 403;
  }
}

interface RequestOptions {
  method?: string | undefined;
  body?: unknown;
  /** Skip attaching the bearer token (only for the public bootstrap/health endpoints). */
  anonymous?: boolean | undefined;
  signal?: AbortSignal | undefined;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers({ Accept: "application/json" });
  if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  if (!options.anonymous) {
    const token = getToken();
    if (token) {
      headers.set("Authorization", `Bearer ${token}`);
    }
  }

  const init: RequestInit = { method: options.method ?? "GET", headers };
  if (options.body !== undefined) {
    init.body = JSON.stringify(options.body);
  }
  if (options.signal) {
    init.signal = options.signal;
  }

  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (cause) {
    // A network failure is not an API error; surface it as such so the UI can say
    // "the backend is not reachable" rather than "the server returned an error".
    throw new ApiError(0, "NETWORK_ERROR", "The RepoPilot backend is not reachable.", null);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const contentType = response.headers.get("Content-Type") ?? "";
  const isJson = contentType.includes("application/json");
  const payload: unknown = text.length > 0 && isJson ? safeParse(text) : text;

  if (!response.ok) {
    const body = (typeof payload === "object" && payload !== null ? payload : {}) as Record<
      string,
      unknown
    >;
    const code = typeof body["errorCode"] === "string" ? body["errorCode"] : `HTTP_${response.status}`;
    const message =
      typeof body["message"] === "string"
        ? body["message"]
        : `Request failed with status ${response.status}.`;
    const correlationId =
      typeof body["correlationId"] === "string" ? body["correlationId"] : null;
    throw new ApiError(response.status, code, message, correlationId);
  }

  return (payload as T) ?? (undefined as T);
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      search.set(key, String(value));
    }
  }
  const encoded = search.toString();
  return encoded.length > 0 ? `?${encoded}` : "";
}

const API = "/api/v1";

export const api = {
  // ---- auth & health (public) ----
  bootstrap: (signal?: AbortSignal) =>
    request<AuthBootstrap>(`${API}/auth/bootstrap`, { anonymous: true, signal }),

  readiness: (signal?: AbortSignal) =>
    request<HealthStatus>("/actuator/health/readiness", { anonymous: true, signal }),

  // ---- overview ----
  overview: (signal?: AbortSignal) => request<OverviewView>(`${API}/overview`, { signal }),

  // ---- workspaces ----
  listWorkspaces: (signal?: AbortSignal) =>
    request<WorkspaceSummary[]>(`${API}/workspaces`, { signal }),

  createWorkspace: (name: string, description: string | null, signal?: AbortSignal) =>
    request<WorkspaceSummary>(`${API}/workspaces`, {
      method: "POST",
      body: { name, description },
      signal,
    }),

  // ---- repositories ----
  listRepositories: (workspaceId: Uuid, signal?: AbortSignal) =>
    request<PageResponse<RepositorySummary>>(
      `${API}/workspaces/${workspaceId}/repositories${query({ size: 200 })}`,
      { signal },
    ),

  registerRepository: (workspaceId: Uuid, name: string | null, path: string, signal?: AbortSignal) =>
    request<RepositorySummary>(`${API}/workspaces/${workspaceId}/repositories`, {
      method: "POST",
      body: { name, path },
      signal,
    }),

  getRepository: (repositoryId: Uuid, signal?: AbortSignal) =>
    request<RepositorySummary>(`${API}/repositories/${repositoryId}`, { signal }),

  deleteRepository: (repositoryId: Uuid, signal?: AbortSignal) =>
    request<void>(`${API}/repositories/${repositoryId}`, { method: "DELETE", signal }),

  startIndexing: (repositoryId: Uuid, signal?: AbortSignal) =>
    request<JobView>(`${API}/repositories/${repositoryId}/index`, {
      method: "POST",
      body: {},
      signal,
    }),

  revisions: (repositoryId: Uuid, signal?: AbortSignal) =>
    request<RevisionLookup[]>(`${API}/repositories/${repositoryId}/revisions`, { signal }),

  coverage: (repositoryId: Uuid, revisionId?: Uuid, signal?: AbortSignal) =>
    request<CoverageView>(
      `${API}/repositories/${repositoryId}/coverage${query({ revisionId })}`,
      { signal },
    ),

  // ---- code explorer ----
  files: (
    repositoryId: Uuid,
    options: { page?: number | undefined; size?: number | undefined; revisionId?: Uuid | undefined } = {},
    signal?: AbortSignal,
  ) =>
    request<PageResponse<SourceFileView>>(
      `${API}/repositories/${repositoryId}/files${query({
        page: options.page,
        size: options.size ?? 200,
        revisionId: options.revisionId,
      })}`,
      { signal },
    ),

  fileContent: (repositoryId: Uuid, path: string, revisionId?: Uuid, signal?: AbortSignal) =>
    request<SourceContentView>(
      `${API}/repositories/${repositoryId}/files/content${query({ path, revisionId })}`,
      { signal },
    ),

  fileSymbols: (repositoryId: Uuid, sourceFileId: Uuid, signal?: AbortSignal) =>
    request<SymbolView[]>(
      `${API}/repositories/${repositoryId}/files/${sourceFileId}/symbols`,
      { signal },
    ),

  searchSymbols: (
    repositoryId: Uuid,
    q: string,
    options: { page?: number | undefined; size?: number | undefined; revisionId?: Uuid | undefined } = {},
    signal?: AbortSignal,
  ) =>
    request<PageResponse<SymbolView>>(
      `${API}/repositories/${repositoryId}/symbols${query({
        q,
        page: options.page,
        size: options.size ?? 50,
        revisionId: options.revisionId,
      })}`,
      { signal },
    ),

  symbolReferences: (repositoryId: Uuid, symbolId: Uuid, signal?: AbortSignal) =>
    request<EdgeView[]>(
      `${API}/repositories/${repositoryId}/symbols/${symbolId}/references`,
      { signal },
    ),

  mappings: (repositoryId: Uuid, type?: string, revisionId?: Uuid, signal?: AbortSignal) =>
    request<MappingView[]>(
      `${API}/repositories/${repositoryId}/mappings${query({ type, revisionId })}`,
      { signal },
    ),

  graph: (repositoryId: Uuid, types?: string, revisionId?: Uuid, signal?: AbortSignal) =>
    request<GraphView>(
      `${API}/repositories/${repositoryId}/graph${query({ types, revisionId })}`,
      { signal },
    ),

  // ---- jobs ----
  listJobs: (
    options: { status?: JobStatus | undefined; page?: number | undefined; size?: number | undefined } = {},
    signal?: AbortSignal,
  ) =>
    request<PageResponse<JobView>>(
      `${API}/jobs${query({ status: options.status, page: options.page, size: options.size ?? 50 })}`,
      { signal },
    ),

  getJob: (jobId: Uuid, signal?: AbortSignal) => request<JobView>(`${API}/jobs/${jobId}`, { signal }),

  jobCounters: (signal?: AbortSignal) => request<JobCounters>(`${API}/jobs/counters`, { signal }),

  cancelJob: (jobId: Uuid, signal?: AbortSignal) =>
    request<CancellationOutcome>(`${API}/jobs/${jobId}/cancel`, { method: "POST", signal }),
};

/** True when an error means the token is missing or no longer valid. */
export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.isAuthFailure;
}
