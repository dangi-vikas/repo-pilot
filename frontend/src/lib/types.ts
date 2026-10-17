/**
 * Types mirroring the RepoPilot API.
 *
 * These are hand-written to match the backend view records rather than generated.
 * The trade-off is deliberate: the API surface is small and stable, and hand-written
 * types let the UI attach meaning (for example, a discriminated union for job status)
 * that a mechanical generator would not produce.
 *
 * IMPORTANT — absent versus null
 * -----------------------------
 * The backend serialises with `spring.jackson.default-property-inclusion: non_null`,
 * so a property whose value is null is **omitted from the JSON entirely**. Reading it
 * in JavaScript therefore yields `undefined`, never `null`. Every field that the
 * backend view declares nullable is marked `?` here and tested with `hasValue()` from
 * `@/lib/format`, which treats both as "no value".
 *
 * Marking these fields optional is not pedantry: it is what makes TypeScript refuse a
 * `field.trim()` that would otherwise throw at runtime the moment a value is missing.
 *
 * Timestamps arrive as ISO-8601 strings (Java `Instant` serialised by Jackson).
 */

export type Uuid = string;
export type Instant = string;

/** Standard pagination envelope returned by every list endpoint. */
export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
}

/** Standard error envelope. `errorCode` is stable; `message` is for humans. */
export interface ApiErrorResponse {
  errorCode: string;
  message: string;
  status: number;
  timestamp: Instant;
  correlationId?: string | null;
  path: string;
}

// ---------------------------------------------------------------- workspaces

export interface WorkspaceSummary {
  id: Uuid;
  name: string;
  slug: string;
  description?: string | null;
  createdAt: Instant;
  repositoryCount: number;
}

// ---------------------------------------------------------------- repositories

export type RepositoryStatus =
  | "REGISTERED"
  | "INDEXING"
  | "READY"
  | "FAILED"
  | "ARCHIVED";

export type SourceType = "LOCAL_PATH" | "GIT_URL" | "UPLOAD";

export interface RepositorySummary {
  id: Uuid;
  workspaceId: Uuid;
  name: string;
  sourceType: SourceType;
  displayLocation: string;
  status: RepositoryStatus;
  sizeBytes?: number | null;
  analyzedFileCount: number;
  indexingError?: string | null;
  lastIndexedAt?: Instant | null;
  createdAt: Instant;
  updatedAt: Instant;
}

export interface RevisionLookup {
  id: Uuid;
  revision: string;
  branch?: string | null;
  fingerprint: string;
  fileCount: number;
  indexedAt: Instant;
}

// ---------------------------------------------------------------- jobs

export type JobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

export type JobType =
  | "REPOSITORY_INDEX"
  | "STATIC_ANALYSIS"
  | "JFR_ANALYSIS"
  | "HEAP_ANALYSIS"
  | "THREAD_DUMP_ANALYSIS"
  | "BENCHMARK_IMPORT"
  | "INVESTIGATION"
  | "RECOMMENDATION_GENERATE"
  | "RECOMMENDATION_VERIFY"
  | "EMBEDDING_INDEX"
  | "ARTIFACT_RETENTION";

export interface JobView {
  id: Uuid;
  jobType: JobType;
  status: JobStatus;
  workspaceId?: Uuid | null;
  repositoryId?: Uuid | null;
  priority: number;
  progress: number;
  stage?: string | null;
  message?: string | null;
  attempt: number;
  maxAttempts: number;
  errorCode?: string | null;
  errorMessage?: string | null;
  resultSummary?: string | null;
  analysisRunId?: Uuid | null;
  cancelRequested: boolean;
  createdAt?: Instant | null;
  startedAt?: Instant | null;
  finishedAt?: Instant | null;
  heartbeatAt?: Instant | null;
}

export interface JobCounters {
  queued: number;
  running: number;
  succeeded: number;
  failed: number;
  cancelled: number;
}

export interface CancellationOutcome {
  jobId: Uuid;
  statusWhenRequested: JobStatus;
  accepted: boolean;
}

// ---------------------------------------------------------------- code index

export type ParseStatus = "PARSED" | "PARSED_WITH_PROBLEMS" | "FAILED" | "SKIPPED" | "EXCLUDED" | "RECORDED";

export interface SourceFileView {
  id: Uuid;
  relativePath: string;
  language: string;
  sizeBytes: number;
  lineCount?: number | null;
  parseStatus: ParseStatus;
  parseError?: string | null;
  packageName?: string | null;
}

export interface SourceContentView {
  relativePath: string;
  language: string;
  lineCount: number;
  content: string;
  truncated: boolean;
}

export type SymbolKind =
  | "CLASS"
  | "INTERFACE"
  | "ENUM"
  | "RECORD"
  | "ANNOTATION"
  | "METHOD"
  | "CONSTRUCTOR"
  | "FIELD"
  | "ENUM_CONSTANT";

export type SymbolVisibility = "PUBLIC" | "PROTECTED" | "PACKAGE_PRIVATE" | "PRIVATE";

export interface SymbolView {
  id: Uuid;
  sourceFileId: Uuid;
  parentSymbolId?: Uuid | null;
  kind: SymbolKind;
  name: string;
  qualifiedName?: string | null;
  signature?: string | null;
  declaredType?: string | null;
  visibility?: SymbolVisibility | null;
  isStatic: boolean;
  isAbstract: boolean;
  startLine?: number | null;
  endLine?: number | null;
  annotationNames?: string | null;
  javadocSummary?: string | null;
  complexity?: number | null;
}

export type EdgeResolution = "RESOLVED" | "INFERRED" | "EXTERNAL" | "UNRESOLVED";

export interface EdgeView {
  id: Uuid;
  sourceSymbolId?: Uuid | null;
  targetSymbolId?: Uuid | null;
  edgeType: string;
  targetName: string;
  resolution: EdgeResolution;
  sourceLine?: number | null;
  detail?: string | null;
}

export type MappingType = string;

/**
 * A bounded slice of a revision's dependency graph.
 *
 * `items` is the first `limit` edges in a deterministic order. `truncated` is true when the
 * revision has more edges than were returned, so the UI can say "showing N of M" instead of
 * quietly presenting a slice as the whole graph.
 */
export interface GraphView {
  items: EdgeView[];
  totalEdges: number;
  limit: number;
  truncated: boolean;
}

export interface MappingView {
  id: Uuid;
  mappingType: MappingType;
  httpMethod?: string | null;
  path?: string | null;
  topic?: string | null;
  consumerGroup?: string | null;
  detail?: string | null;
  symbolId?: Uuid | null;
  startLine?: number | null;
}

export interface CoverageView {
  revisionId: Uuid;
  totalFiles: number;
  parsedFiles: number;
  problemFiles: number;
  failedFiles: number;
  recordedFiles: number;
  excludedFiles: number;
  enumeratedFiles: number;
  prunedDirectories: number;
  excludedByReason?: string | null;
  prunedByReason?: string | null;
  compiledClassesPresent: boolean;
  symbolCount: number;
  edgeCount: number;
  resolvedEdges: number;
  inferredEdges: number;
  externalEdges: number;
  mappingCount: number;
}

// ---------------------------------------------------------------- analysis

/**
 * How a claim was arrived at. The four values are the product's evidence discipline
 * expressed as a type: a static finding is read from source, a runtime measurement is
 * taken from a recorded run, and a hypothesis is a model's guess. Only an observation
 * and a measurement are facts.
 */
export type EvidenceCategory =
  | "OBSERVATION"
  | "STATIC_FINDING"
  | "RUNTIME_MEASUREMENT"
  | "AI_HYPOTHESIS";

export type FindingSeverity = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW" | "INFO";

/** How strongly the evidence supports the finding, independent of how bad it would be. */
export type FindingConfidence = "CERTAIN" | "HIGH" | "MEDIUM" | "LOW";

export type FindingCategory =
  | "CORRECTNESS"
  | "SECURITY"
  | "PERFORMANCE"
  | "CONCURRENCY"
  | "RESOURCE"
  | "MAINTAINABILITY"
  | "STYLE";

/**
 * Whether the finding's cited location was checked against the analysed text.
 *
 * `LOCATION_STALE` is a defect in the rule, not in the code: the rule reported a line
 * the file does not have. It is shown rather than hidden so the rule can be fixed.
 */
export type VerificationStatus =
  | "SOURCE_CONFIRMED"
  | "LOCATION_STALE"
  | "NO_LOCATION_CLAIMED";

export type AnalysisRunStatus = "RUNNING" | "SUCCEEDED" | "PARTIAL" | "FAILED" | "CANCELLED";

/**
 * A static-analysis finding.
 *
 * Optional fields are declared `?: T | null` because the backend omits nulls: an absent
 * key and a null value both mean "not recorded", and only `hasValue` may test them.
 * Line numbers are 1-based; a missing line is a file-level finding, never line zero.
 */
export interface FindingView {
  id: Uuid;
  runId?: Uuid | null;
  analyzerId: string;
  ruleId?: string | null;
  findingType: string;
  title: string;
  summary?: string | null;
  severity: FindingSeverity;
  confidence: FindingConfidence;
  category: FindingCategory;
  evidenceCategory: EvidenceCategory;
  verificationStatus: VerificationStatus;
  sourcePath?: string | null;
  startLine?: number | null;
  endLine?: number | null;
  impact?: string | null;
  recommendedAction?: string | null;
  limitations?: string | null;
  symbolId?: Uuid | null;
  createdAt?: string | null;
}

/**
 * The header and counts for one analysis run.
 *
 * Every field describing the run is absent when the repository has never been analysed.
 * That state has its own meaning — "nothing has been looked at" — and is deliberately
 * not an error, so the UI must render it differently from "the run found nothing".
 */
export interface FindingSummaryView {
  repositoryId: Uuid;
  runId?: Uuid | null;
  revisionId?: Uuid | null;
  runStatus?: AnalysisRunStatus | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  analyzerVersions?: string | null;
  runSummary?: string | null;
  total: number;
  bySeverity: Partial<Record<FindingSeverity, number>>;
  byCategory: Partial<Record<FindingCategory, number>>;
  byAnalyzer: Record<string, number>;
}

// ---------------------------------------------------------------- overview

export interface AiStatus {
  enabled: boolean;
  providerId?: string | null;
  reachable: boolean;
  usable: boolean;
  embeddingsEnabled: boolean;
  message?: string | null;
  availableProviderIds: string[];
}

export interface OverviewView {
  workspaceCount: number;
  repositoryCount: number;
  activeJobCount: number;
  queuedJobCount: number;
  runningJobCount: number;
  succeededJobCount: number;
  failedJobCount: number;
  cancelledJobCount: number;
  ai: AiStatus;
}

export interface AuthBootstrap {
  authMode: string;
  header: string;
  scheme: string;
  tokenGenerated: boolean;
  tokenLocationHint: string;
  instructions: string;
}

export interface HealthStatus {
  status: "UP" | "DOWN" | "OUT_OF_SERVICE" | "UNKNOWN";
  components?: Record<string, { status: string; details?: Record<string, unknown> }>;
}
