import { getToken } from "@/lib/session";
import type {
  AuthBootstrap,
  CancellationOutcome,
  CoverageView,
  EdgeView,
  FindingCategory,
  FindingSeverity,
  FindingSummaryView,
  FindingView,
  GraphView,
  HealthStatus,
  JobCounters,
  JobStatus,
  JobView,
  MappingView,
  MetricKind,
  OverviewView,
  PageResponse,
  RepositorySummary,
  RetentionPlan,
  RevisionLookup,
  RuntimeArtifactType,
  RuntimeArtifactView,
  RuntimeMeasurementView,
  RuntimeSummaryView,
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
  /**
   * A multipart body. Set instead of `body`: the browser must choose the
   * `Content-Type` itself so the multipart boundary in the header matches the one in
   * the payload, and the file must not be serialised as JSON.
   */
  form?: FormData | undefined;
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
  if (options.form !== undefined) {
    init.body = options.form;
  } else if (options.body !== undefined) {
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

  // ---- static analysis ----

  /**
   * Submits a static-analysis job.
   *
   * Sends an empty JSON body: the endpoint accepts a body (an optional idempotency key)
   * and rejects a request with no content type.
   */
  analyze: (repositoryId: Uuid, signal?: AbortSignal) =>
    request<JobView>(`${API}/repositories/${repositoryId}/analyze`, {
      method: "POST",
      body: {},
      signal,
    }),

  /** Counts and run header for a repository's findings. */
  findingSummary: (repositoryId: Uuid, revisionId?: Uuid, signal?: AbortSignal) =>
    request<FindingSummaryView>(
      `${API}/repositories/${repositoryId}/findings/summary${query({ revisionId })}`,
      { signal },
    ),

  /** A page of findings from one analysis run, which the server resolves when `runId` is absent. */
  findings: (
    repositoryId: Uuid,
    options: {
      runId?: Uuid | undefined;
      severity?: FindingSeverity | undefined;
      category?: FindingCategory | undefined;
      analyzerId?: string | undefined;
      path?: string | undefined;
      text?: string | undefined;
      page?: number | undefined;
      size?: number | undefined;
    } = {},
    signal?: AbortSignal,
  ) =>
    request<PageResponse<FindingView>>(
      `${API}/repositories/${repositoryId}/findings${query({
        runId: options.runId,
        severity: options.severity,
        category: options.category,
        analyzerId: options.analyzerId,
        path: options.path,
        q: options.text,
        page: options.page,
        size: options.size,
      })}`,
      { signal },
    ),

  // ---- runtime evidence ----

  /**
   * Uploads a recording or dump.
   *
   * Sent as multipart and never as JSON: the file is the payload, and the backend
   * streams the part to disk so a recording larger than the browser tab's memory can
   * still be uploaded. The filename travels as a label only — the server stores the
   * bytes under their content hash.
   */
  uploadArtifact: (
    repositoryId: Uuid,
    file: File,
    artifactType?: RuntimeArtifactType | undefined,
    signal?: AbortSignal,
  ) => {
    const form = new FormData();
    form.append("file", file);
    return request<RuntimeArtifactView>(
      `${API}/repositories/${repositoryId}/runtime/artifacts${query({ artifactType })}`,
      { method: "POST", form, signal },
    );
  },

  /** Artefacts uploaded for a repository, newest first. */
  runtimeArtifacts: (
    repositoryId: Uuid,
    options: { page?: number | undefined; size?: number | undefined } = {},
    signal?: AbortSignal,
  ) =>
    request<PageResponse<RuntimeArtifactView>>(
      `${API}/repositories/${repositoryId}/runtime/artifacts${query({
        page: options.page,
        size: options.size ?? 50,
      })}`,
      { signal },
    ),

  runtimeArtifact: (repositoryId: Uuid, artifactId: Uuid, signal?: AbortSignal) =>
    request<RuntimeArtifactView>(
      `${API}/repositories/${repositoryId}/runtime/artifacts/${artifactId}`,
      { signal },
    ),

  /**
   * Queues the analysis of an uploaded artefact.
   *
   * Sends an empty JSON body for the same reason `analyze` does: the endpoint accepts a
   * body (an optional idempotency key) and rejects a request with no content type.
   */
  analyzeArtifact: (repositoryId: Uuid, artifactId: Uuid, signal?: AbortSignal) =>
    request<JobView>(
      `${API}/repositories/${repositoryId}/runtime/artifacts/${artifactId}/analyze`,
      { method: "POST", body: {}, signal },
    ),

  /** A page of measurements from one run. `runId` absent means the newest runtime run. */
  runtimeMeasurements: (
    repositoryId: Uuid,
    options: {
      runId?: Uuid | undefined;
      metric?: string | undefined;
      kind?: MetricKind | undefined;
      scope?: string | undefined;
      page?: number | undefined;
      size?: number | undefined;
    } = {},
    signal?: AbortSignal,
  ) =>
    request<PageResponse<RuntimeMeasurementView>>(
      `${API}/repositories/${repositoryId}/runtime/measurements${query({
        runId: options.runId,
        metric: options.metric,
        kind: options.kind,
        scope: options.scope,
        page: options.page,
        size: options.size ?? 200,
      })}`,
      { signal },
    ),

  /** The run header and breakdown for a repository's runtime evidence. */
  runtimeSummary: (repositoryId: Uuid, runId?: Uuid, signal?: AbortSignal) =>
    request<RuntimeSummaryView>(
      `${API}/repositories/${repositoryId}/runtime/summary${query({ runId })}`,
      { signal },
    ),

  /**
   * What a retention pass would remove. A read, not a job: the plan is computed against the current
   * clock every time it is asked for, so there is nothing to schedule and nothing that can go stale.
   *
   * `days` previews a window other than the configured one. Absent means the configured window, and
   * zero means pruning is off, which reports the repository and names no candidate.
   */
  retentionPlan: (repositoryId: Uuid, days?: number, signal?: AbortSignal) =>
    request<RetentionPlan>(
      `${API}/repositories/${repositoryId}/runtime/retention${query({ days })}`,
      { signal },
    ),

  /**
   * Queues a purge of the stored bytes of artefacts older than `olderThanDays`.
   *
   * A job rather than an inline delete, so the pass is visible in the job list with the window it ran
   * under. The measurements are not touched by it.
   */
  purgeRetention: (repositoryId: Uuid, olderThanDays: number, signal?: AbortSignal) =>
    request<JobView>(
      `${API}/repositories/${repositoryId}/runtime/retention/purge`,
      { method: "POST", body: { olderThanDays }, signal },
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
