import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryOptions,
} from "@tanstack/react-query";
import { api, isUnauthorized } from "@/lib/api";
import type {
  AuthBootstrap,
  CoverageView,
  EdgeView,
  FindingCategory,
  FindingSeverity,
  FindingSummaryView,
  FindingView,
  GraphView,
  HealthStatus,
  InvestigationDetail,
  InvestigationSubmission,
  InvestigationSummary,
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

/**
 * Typed React Query hooks over the API client.
 *
 * A query that needs a selection id is `enabled` only when the id is present, so
 * screens can be mounted before a repository is chosen without each one re-implementing
 * a guard. Errors that mean "the token is gone" are not retried and are surfaced by the
 * shell as a reconnect prompt.
 */

/** Common options: never retry auth failures, they need the operator to act. */
function guard<T>(options?: Partial<UseQueryOptions<T>>): Partial<UseQueryOptions<T>> {
  return {
    retry: (failureCount, error) => !isUnauthorized(error) && failureCount < 2,
    ...options,
  };
}

/** The public bootstrap document: auth mode and where the token lives. */
export function useBootstrap() {
  return useQuery<AuthBootstrap>({
    queryKey: ["auth-bootstrap"],
    queryFn: ({ signal }) => api.bootstrap(signal),
    staleTime: Infinity,
    retry: false,
  });
}

export function useOverview() {  return useQuery<OverviewView>({
    queryKey: ["overview"],
    queryFn: ({ signal }) => api.overview(signal),
    refetchInterval: 15_000,
    ...guard(),
  });
}

export function useWorkspaces() {
  return useQuery<WorkspaceSummary[]>({
    queryKey: ["workspaces"],
    queryFn: ({ signal }) => api.listWorkspaces(signal),
    staleTime: 30_000,
    ...guard(),
  });
}

export function useRepositories(workspaceId: Uuid | null) {
  return useQuery<PageResponse<RepositorySummary>>({
    queryKey: ["repositories", workspaceId],
    queryFn: ({ signal }) => api.listRepositories(workspaceId as Uuid, signal),
    enabled: workspaceId !== null,
    ...guard(),
  });
}

export function useRepository(repositoryId: Uuid | null) {
  return useQuery<RepositorySummary>({
    queryKey: ["repository", repositoryId],
    queryFn: ({ signal }) => api.getRepository(repositoryId as Uuid, signal),
    enabled: repositoryId !== null,
    ...guard(),
  });
}

export function useRevisions(repositoryId: Uuid | null) {
  return useQuery<RevisionLookup[]>({
    queryKey: ["revisions", repositoryId],
    queryFn: ({ signal }) => api.revisions(repositoryId as Uuid, signal),
    enabled: repositoryId !== null,
    ...guard(),
  });
}

export function useCoverage(repositoryId: Uuid | null, revisionId: Uuid | null) {
  return useQuery<CoverageView>({
    queryKey: ["coverage", repositoryId, revisionId],
    queryFn: ({ signal }) => api.coverage(repositoryId as Uuid, revisionId ?? undefined, signal),
    enabled: repositoryId !== null,
    ...guard(),
  });
}

export function useFiles(repositoryId: Uuid | null, revisionId: Uuid | null) {
  return useQuery<PageResponse<SourceFileView>>({
    queryKey: ["files", repositoryId, revisionId],
    queryFn: ({ signal }) =>
      api.files(repositoryId as Uuid, { size: 500, revisionId: revisionId ?? undefined }, signal),
    enabled: repositoryId !== null,
    ...guard(),
  });
}

export function useFileContent(
  repositoryId: Uuid | null,
  path: string | null,
  revisionId: Uuid | null,
) {
  return useQuery<SourceContentView>({
    queryKey: ["file-content", repositoryId, path, revisionId],
    queryFn: ({ signal }) =>
      api.fileContent(repositoryId as Uuid, path as string, revisionId ?? undefined, signal),
    enabled: repositoryId !== null && path !== null && path.length > 0,
    staleTime: 60_000,
    ...guard(),
  });
}

export function useSymbols(repositoryId: Uuid | null, query: string, revisionId: Uuid | null) {
  return useQuery<PageResponse<SymbolView>>({
    queryKey: ["symbols", repositoryId, query, revisionId],
    queryFn: ({ signal }) =>
      api.searchSymbols(
        repositoryId as Uuid,
        query,
        { size: 100, revisionId: revisionId ?? undefined },
        signal,
      ),
    enabled: repositoryId !== null,
    ...guard(),
  });
}

export function useFileSymbols(repositoryId: Uuid | null, sourceFileId: Uuid | null) {
  return useQuery<SymbolView[]>({
    queryKey: ["file-symbols", repositoryId, sourceFileId],
    queryFn: ({ signal }) => api.fileSymbols(repositoryId as Uuid, sourceFileId as Uuid, signal),
    enabled: repositoryId !== null && sourceFileId !== null,
    ...guard(),
  });
}

export function useSymbolReferences(repositoryId: Uuid | null, symbolId: Uuid | null) {
  return useQuery<EdgeView[]>({
    queryKey: ["symbol-references", repositoryId, symbolId],
    queryFn: ({ signal }) => api.symbolReferences(repositoryId as Uuid, symbolId as Uuid, signal),
    enabled: repositoryId !== null && symbolId !== null,
    ...guard(),
  });
}

export function useMappings(repositoryId: Uuid | null, revisionId: Uuid | null) {
  return useQuery<MappingView[]>({
    queryKey: ["mappings", repositoryId, revisionId],
    queryFn: ({ signal }) => api.mappings(repositoryId as Uuid, undefined, revisionId ?? undefined, signal),
    enabled: repositoryId !== null,
    ...guard(),
  });
}

export function useGraph(repositoryId: Uuid | null, revisionId: Uuid | null) {
  return useQuery<GraphView>({
    queryKey: ["graph", repositoryId, revisionId],
    queryFn: ({ signal }) => api.graph(repositoryId as Uuid, undefined, revisionId ?? undefined, signal),
    enabled: repositoryId !== null,
    ...guard(),
  });
}

// ---------------------------------------------------------------- analysis

/** Optional filters for a findings query. `runId` absent means "the current run". */
export interface FindingQuery {
  runId?: Uuid | undefined;
  severity?: FindingSeverity | undefined;
  category?: FindingCategory | undefined;
  analyzerId?: string | undefined;
  path?: string | undefined;
  text?: string | undefined;
  page?: number | undefined;
  size?: number | undefined;
}

/**
 * Counts and run header for a repository's findings.
 *
 * The zero state is `total: 0` with no `runId`. The UI must not collapse that into the
 * same thing as a run that found nothing, so the response keeps them distinguishable.
 */
export function useFindingSummary(repositoryId: Uuid | null, revisionId: Uuid | null) {
  return useQuery<FindingSummaryView>({
    queryKey: ["finding-summary", repositoryId, revisionId],
    queryFn: ({ signal }) =>
      api.findingSummary(repositoryId as Uuid, revisionId ?? undefined, signal),
    enabled: repositoryId !== null,
    ...guard(),
  });
}

/**
 * A page of findings.
 *
 * The query key spells out every filter rather than serialising an object: an unstable
 * key would re-request on every render, and a missing filter in the key would show one
 * filter's results under another's URL.
 */
export function useFindings(
  repositoryId: Uuid | null,
  filter: FindingQuery,
  options: { enabled?: boolean; refetchWhileActive?: boolean } = {},
) {
  return useQuery<PageResponse<FindingView>>({
    queryKey: [
      "findings",
      repositoryId,
      filter.severity ?? "ALL",
      filter.category ?? "ALL",
      filter.analyzerId ?? "ALL",
      filter.path ?? "ALL",
      filter.text ?? "",
      filter.page ?? 0,
    ],
    queryFn: ({ signal }) => api.findings(repositoryId as Uuid, filter, signal),
    enabled: repositoryId !== null && options.enabled !== false,
    // A run in flight changes the answers, so the list polls while one is. Otherwise it
    // is not polled at all: a stale list is honest, a list that refreshes for no reason
    // makes the reader wonder what changed.
    refetchInterval: options.refetchWhileActive === true ? 3_000 : false,
    ...guard(),
  });
}

export function useJobs(status: JobStatus | undefined) {
  return useQuery<PageResponse<JobView>>({
    queryKey: ["jobs", status ?? "ALL"],
    queryFn: ({ signal }) => api.listJobs({ status, size: 100 }, signal),
    refetchInterval: (query) => {
      const data = query.state.data;
      const active = data?.items.some((job) => job.status === "QUEUED" || job.status === "RUNNING");
      return active ? 2_000 : 15_000;
    },
    ...guard(),
  });
}

export function useJobCounters() {
  return useQuery<JobCounters>({
    queryKey: ["job-counters"],
    queryFn: ({ signal }) => api.jobCounters(signal),
    refetchInterval: 10_000,
    ...guard(),
  });
}

/**
 * Backend health for the shell's connection indicator.
 *
 * The readiness group is polled rather than the top-level health document so the UI
 * reflects what actually matters to an operator: database, disk space, worker pool
 * and the AI provider, not just "the process is alive".
 */
export function useReadiness() {
  return useQuery<HealthStatus>({
    queryKey: ["readiness"],
    queryFn: ({ signal }) => api.readiness(signal),
    refetchInterval: 20_000,
    // A readiness probe is what tells us the backend is alive; retrying it inside a
    // failed state only delays the honest "down" answer.
    retry: false,
  });
}

// ---------------------------------------------------------------- runtime evidence

/**
 * The run header for a repository's runtime evidence.
 *
 * Polls while a runtime job is in flight, so a freshly analysed recording appears without
 * a manual refresh. When nothing is running it is not polled: a stale measurement is
 * honest, a number that changes for no visible reason is not.
 */
export function useRuntimeSummary(
  repositoryId: Uuid | null,
  runId: Uuid | null,
  options: { refetchWhileActive?: boolean } = {},
) {
  return useQuery<RuntimeSummaryView>({
    queryKey: ["runtime-summary", repositoryId, runId ?? "LATEST"],
    queryFn: ({ signal }) =>
      api.runtimeSummary(repositoryId as Uuid, runId ?? undefined, signal),
    enabled: repositoryId !== null,
    refetchInterval: options.refetchWhileActive === true ? 3_000 : false,
    ...guard(),
  });
}

export function useRuntimeArtifacts(repositoryId: Uuid | null, options: { refetchWhileActive?: boolean } = {}) {
  return useQuery<PageResponse<RuntimeArtifactView>>({
    queryKey: ["runtime-artifacts", repositoryId],
    queryFn: ({ signal }) => api.runtimeArtifacts(repositoryId as Uuid, { size: 50 }, signal),
    enabled: repositoryId !== null,
    refetchInterval: options.refetchWhileActive === true ? 3_000 : false,
    ...guard(),
  });
}

/**
 * A page of measurements.
 *
 * The query key spells out each filter rather than serialising the object, so a change in
 * one filter cannot leave another filter's results displayed under the new heading.
 */
export function useRuntimeMeasurements(
  repositoryId: Uuid | null,
  filter: RuntimeMeasurementQuery,
  options: { enabled?: boolean; refetchWhileActive?: boolean } = {},
) {
  return useQuery<PageResponse<RuntimeMeasurementView>>({
    queryKey: [
      "runtime-measurements",
      repositoryId,
      filter.runId ?? "LATEST",
      filter.metric ?? "ALL",
      filter.kind ?? "ALL",
      filter.scope ?? "ALL",
    ],
    queryFn: ({ signal }) => api.runtimeMeasurements(repositoryId as Uuid, filter, signal),
    enabled: repositoryId !== null && options.enabled !== false,
    refetchInterval: options.refetchWhileActive === true ? 3_000 : false,
    ...guard(),
  });
}

/** Optional filters for a measurements query. */
export interface RuntimeMeasurementQuery {
  runId?: Uuid | undefined;
  metric?: string | undefined;
  kind?: MetricKind | undefined;
  scope?: string | undefined;
}

/** Uploads a recording or dump. The file is the body; the server hashes and stores it. */
export function useUploadArtifact(repositoryId: Uuid | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { file: File; artifactType?: RuntimeArtifactType | undefined }) =>
      api.uploadArtifact(repositoryId as Uuid, input.file, input.artifactType),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["runtime-artifacts", repositoryId] });
      // A re-upload of identical bytes is the same artefact, so the summary may already
      // describe it; invalidate rather than assume.
      void queryClient.invalidateQueries({ queryKey: ["runtime-summary", repositoryId] });
    },
  });
}

/** Queues an artefact's analysis as a durable job. */
export function useAnalyzeArtifact(repositoryId: Uuid | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (artifactId: Uuid) => api.analyzeArtifact(repositoryId as Uuid, artifactId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["jobs"] });
      void queryClient.invalidateQueries({ queryKey: ["job-counters"] });
      void queryClient.invalidateQueries({ queryKey: ["runtime-summary", repositoryId] });
      void queryClient.invalidateQueries({ queryKey: ["runtime-artifacts", repositoryId] });
    },
  });
}

/**
 * What a retention pass would remove.
 *
 * Keyed by the window, because a preview of a different window is a different answer and must not be
 * shown under the heading of the one on screen.
 */
export function useRetentionPlan(repositoryId: Uuid | null, days?: number) {
  return useQuery<RetentionPlan>({
    queryKey: ["retention-plan", repositoryId, days ?? "CONFIGURED"],
    queryFn: ({ signal }) => api.retentionPlan(repositoryId as Uuid, days, signal),
    enabled: repositoryId !== null,
    ...guard(),
  });
}

/**
 * Queues a purge of stored bytes older than a window.
 *
 * Every runtime query is invalidated, not only the plan: the artefact rows gain a `purgedAt`, the
 * files leave the list's "stored" count, and a job appears in the queue. The measurements do not
 * change, which is the point — but they are invalidated anyway so the screen is not left asserting
 * that nothing happened.
 */
export function usePurgeRetention(repositoryId: Uuid | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (olderThanDays: number) => api.purgeRetention(repositoryId as Uuid, olderThanDays),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["retention-plan", repositoryId] });
      void queryClient.invalidateQueries({ queryKey: ["runtime-artifacts", repositoryId] });
      void queryClient.invalidateQueries({ queryKey: ["runtime-summary", repositoryId] });
      void queryClient.invalidateQueries({ queryKey: ["jobs"] });
      void queryClient.invalidateQueries({ queryKey: ["job-counters"] });
    },
  });
}

// ---------------------------------------------------------------- mutations

export function useStartIndexing(repositoryId: Uuid | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.startIndexing(repositoryId as Uuid),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["jobs"] });
      void queryClient.invalidateQueries({ queryKey: ["job-counters"] });
      void queryClient.invalidateQueries({ queryKey: ["repository", repositoryId] });
    },
  });
}

export function useCancelJob() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (jobId: Uuid) => api.cancelJob(jobId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["jobs"] });
      void queryClient.invalidateQueries({ queryKey: ["job-counters"] });
    },
  });
}

/** Submits a static-analysis job for the repository's newest revision. */
export function useStartAnalysis(repositoryId: Uuid | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.analyze(repositoryId as Uuid),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["jobs"] });
      void queryClient.invalidateQueries({ queryKey: ["job-counters"] });
      void queryClient.invalidateQueries({ queryKey: ["finding-summary", repositoryId] });
      void queryClient.invalidateQueries({ queryKey: ["findings", repositoryId] });
    },
  });
}

export function useRegisterRepository(workspaceId: Uuid | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { name: string | null; path: string }) =>
      api.registerRepository(workspaceId as Uuid, input.name, input.path),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["repositories", workspaceId] });
      void queryClient.invalidateQueries({ queryKey: ["workspaces"] });
      void queryClient.invalidateQueries({ queryKey: ["overview"] });
    },
  });
}

export function useCreateWorkspace() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { name: string; description: string | null }) =>
      api.createWorkspace(input.name, input.description),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["workspaces"] });
      void queryClient.invalidateQueries({ queryKey: ["overview"] });
    },
  });
}

export function useDeleteRepository() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (repositoryId: Uuid) => api.deleteRepository(repositoryId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["repositories"] });
      void queryClient.invalidateQueries({ queryKey: ["overview"] });
    },
  });
}

/**
 * The investigation list for one repository.
 *
 * Polls only while something is in flight. The list is a record of questions and verdicts, so a
 * background refresh is honest work while an answer is being produced and pointless noise after.
 */
export function useInvestigations(
  repositoryId: Uuid | null,
  options: { refetchWhileActive?: boolean } = {},
) {
  return useQuery<PageResponse<InvestigationSummary>>({
    queryKey: ["investigations", repositoryId],
    queryFn: ({ signal }) => api.investigations(repositoryId as Uuid, signal),
    enabled: repositoryId !== null,
    refetchInterval: options.refetchWhileActive === true ? 2_500 : false,
    ...guard(),
  });
}

/** One investigation with its evidence, claims and diagnostics. */
export function useInvestigation(
  repositoryId: Uuid | null,
  investigationId: Uuid | null,
  options: { refetchWhileActive?: boolean } = {},
) {
  return useQuery<InvestigationDetail>({
    queryKey: ["investigation", repositoryId, investigationId],
    queryFn: ({ signal }) =>
      api.investigation(repositoryId as Uuid, investigationId as Uuid, signal),
    enabled: repositoryId !== null && investigationId !== null,
    refetchInterval: options.refetchWhileActive === true ? 2_500 : false,
    ...guard(),
  });
}

/** Asks a question. The answer arrives through the detail query once the job has run. */
export function useAskInvestigation(repositoryId: Uuid | null) {
  const queryClient = useQueryClient();
  return useMutation<InvestigationSubmission, unknown, { question: string; scope?: string }>({
    mutationFn: ({ question, scope }) =>
      api.askInvestigation(repositoryId as Uuid, question, scope),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["investigations", repositoryId] });
      void queryClient.invalidateQueries({ queryKey: ["jobs"] });
      void queryClient.invalidateQueries({ queryKey: ["job-counters"] });
    },
  });
}

/** Cancels an investigation that is queued or running. */
export function useCancelInvestigation(repositoryId: Uuid | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (investigationId: Uuid) =>
      api.cancelInvestigation(repositoryId as Uuid, investigationId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["investigations", repositoryId] });
      void queryClient.invalidateQueries({ queryKey: ["investigation"] });
      void queryClient.invalidateQueries({ queryKey: ["jobs"] });
    },
  });
}
