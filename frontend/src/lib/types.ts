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

// ---------------------------------------------------------------- runtime evidence

/**
 * What kind of file a runtime artefact is.
 *
 * All four can be read by this build, each by a reader that only repeats what the file states.
 * `supported` and `unsupportedReason` stay on the artefact because they are how the API answers for a
 * format a build does not read — the shape of the refusal, not a promise that one exists today.
 */
export type RuntimeArtifactType =
  | "JFR_RECORDING"
  | "HEAP_DUMP"
  | "THREAD_DUMP"
  | "BENCHMARK_RESULT";

/**
 * What kind of quantity a measurement is.
 *
 * This is not decoration: a `GAUGE` sampled once and a `COUNTER` summed over an
 * interval mean different things, and a client that adds them together is wrong.
 */
export type MetricKind = "COUNTER" | "DURATION" | "GAUGE" | "SIZE";

/**
 * How the samples behind one value were reduced.
 *
 * `AS_REPORTED` is the odd one out and is not a reduction: it means the number was computed by
 * something else — a profiler's own aggregation, or the harness that ran a benchmark — and RepoPilot
 * is repeating it rather than deriving it. There is deliberately no percentile here. A p99 cannot be
 * derived from a stored max, so the backend does not pretend to have one, and neither does this type.
 * A percentile a benchmark file states is stored as a quoted `AS_REPORTED` row, under the harness's
 * own level.
 */
export type MeasurementAggregation = "COUNT" | "SUM" | "MEAN" | "MAX" | "AS_REPORTED";

/** An uploaded runtime artefact, whether or not it can be read yet. */
export interface RuntimeArtifactView {
  id: Uuid;
  repositoryId: Uuid;
  artifactType: RuntimeArtifactType;
  originalFilename: string;
  contentHash: string;
  sizeBytes: number;
  /** The run that read it, if any has. Absent means "stored, never analysed". */
  analysisRunId?: Uuid | null;
  createdAt: Instant;
  supported: boolean;
  unsupportedReason?: string | null;
  /**
   * True only for artefacts that contain the contents of process memory — a heap dump is the
   * whole heap, values and all. A thread dump is deliberately false: it names threads and frames,
   * which describes your code, but it is not a copy of the heap. The two imply different handling
   * and the warning shown for one would be false for the other.
   */
  containsProcessMemory: boolean;
  measurementCount: number;
  /**
   * When the stored bytes were removed by retention, or absent while the file is there.
   *
   * Only the file goes: the artefact row, the run and every measurement stay, which is why a purged
   * artefact still shows its numbers and still refuses to be analysed again. Uploading the same bytes
   * clears this.
   */
  purgedAt?: Instant | null;
}

/**
 * What a retention pass would remove, computed against the clock on every read.
 *
 * There is no dry-run job because the plan is the dry run: `candidates` is what a purge would delete
 * right now, and `measurementsKept` is the number it would not.
 */
export interface RetentionPlan {
  repositoryId: Uuid;
  /** The window the plan was computed for, in days. Zero means pruning is switched off. */
  retentionDays: number;
  automatic: boolean;
  cutoff: Instant;
  /** Files still on disk inside the window. */
  storedArtefacts: number;
  storedBytes: number;
  /** Files whose bytes were already removed. Their rows and measurements are still here. */
  purgedArtefacts: number;
  purgedBytes: number;
  /** The oldest file still on disk, which is what "nothing is due yet" is measured against. */
  oldestStoredAt?: Instant | null;
  candidates: RetentionCandidate[];
  /** Bytes a purge would reclaim now: the sum over `candidates`. */
  removableBytes: number;
  /** Measurements that would survive the purge, because they are not the bytes. */
  measurementsKept: number;
}

/** One file a purge would remove, and what would survive it. */
export interface RetentionCandidate {
  artifactId: Uuid;
  originalFilename: string;
  artifactType: RuntimeArtifactType;
  sizeBytes: number;
  createdAt: Instant;
  analysisRunId?: Uuid | null;
  /** Measurements recorded from this file, which purging does not touch. */
  measurementCount: number;
}

/**
 * One measured quantity.
 *
 * The unit, the reduction and the sample count travel with the value, so a renderer
 * cannot show a number without the context needed to read it.
 */
export interface RuntimeMeasurementView {
  id: Uuid;
  analysisRunId?: Uuid | null;
  artifactId?: Uuid | null;
  metricKey: string;
  metricKind: MetricKind;
  aggregation: MeasurementAggregation;
  value: number;
  unit: string;
  sampleCount: number;
  scope?: string | null;
  /** Metric-specific caveats and shares, as JSON. Shown, never treated as decoration. */
  attributesJson?: string | null;
  createdAt: Instant;
}

/**
 * The header for a repository's runtime evidence.
 *
 * `runId` absent means no recording has been analysed — a different answer from a run
 * that measured nothing, and the two must not be collapsed in the UI.
 */
export interface RuntimeSummaryView {
  repositoryId: Uuid;
  runId?: Uuid | null;
  artifactId?: Uuid | null;
  artifactName?: string | null;
  artifactType?: RuntimeArtifactType | null;
  artifactHash?: string | null;
  artifactSizeBytes?: number | null;
  status?: AnalysisRunStatus | null;
  startedAt?: Instant | null;
  finishedAt?: Instant | null;
  analyzerVersions?: string | null;
  summary?: string | null;
  measurementCount: number;
  byKind: Partial<Record<MetricKind, number>>;
  byMetricFamily: Record<string, number>;
  /** The recording's environment: JVM, OS, CPU, memory, and the recording's own window. */
  provenanceJson?: RuntimeProvenance | null;
}

/**
 * The provenance document stored with an artefact.
 *
 * Mirrors exactly what the reader records and nothing more, and is therefore one type with
 * per-reader sections rather than two loosely-related ones: both readers fill `environment` and
 * `produced`, and a recording fills `recording` while a thread dump fills `dump`. The missing fields
 * are the point: there is no `jvmArguments`, no `systemProperties` and no per-event setting, because
 * RepoPilot does not read them — they are credential carriers, and a threshold or sampling period
 * cannot be attributed to an event type through the JDK 21 consumer API. A type that offered those
 * fields would invite a client to display them. A thread dump's stacks are absent for the same kind
 * of reason: they are the application's own structure, they stay in the file, and only counts are
 * stored.
 */
export interface RuntimeProvenance {
  environment?: {
    jvmName?: string | null;
    /** The JVM's full build string. Already begins with `jvmName`; shown verbatim, never merged. */
    jvmVersion?: string | null;
    /** How the VM was running, e.g. `mixed mode, sharing`. A thread dump states this in its header. */
    jvmMode?: string | null;
    jvmStartTime?: string | null;
    processId?: number | null;
    /** A multi-line description, kept as the JVM wrote it. */
    osVersion?: string | null;
    /** A multi-line CPU description, kept as the JVM wrote it. */
    cpu?: string | null;
    cpuCores?: number | null;
    hardwareThreads?: number | null;
    physicalMemoryBytes?: number | null;
    physicalMemoryUsedBytes?: number | null;
    /**
     * A heap dump's format version, e.g. `1.0.2`, read from the file's own header. A thread dump
     * writes no environment block beyond its header, so these are absent for one.
     */
    formatVersion?: string | null;
    /** The width of an object identifier in the file, in bytes. */
    identifierSize?: number | null;
    /** Why `identifierSize` is not the width of a reference: a 64-bit dump with compressed
     * references still writes 8-byte identifiers. The reader's own explanation. */
    identifierSizeBasis?: string | null;
    /** The JMH version that wrote a benchmark result. */
    jmhVersion?: string | null;
    /** The JDK the benchmark ran on, as its result file states it. */
    jdkVersion?: string | null;
  };
  recording?: {
    name?: string | null;
    startedAt?: string | null;
    firstEventAt?: string | null;
    lastEventAt?: string | null;
    events?: number | null;
    eventTypes?: number | null;
  };
  /**
   * A thread dump's header and counts.
   *
   * `takenAt` is text, not an instant: HotSpot writes local time there and the file records no
   * zone, so converting it would be a guess about which offset the process was in.
   */
  dump?: {
    takenAt?: string | null;
    /** Why `takenAt` is a string: the reader's own explanation, shown next to it. */
    takenAtBasis?: string | null;
    threads?: number | null;
    javaThreads?: number | null;
    threadsWithoutJavaState?: number | null;
    threadsWithUnknownState?: number | null;
    javaThreadListLength?: number | null;
    deadlocks?: number | null;
    contendedMonitors?: number | null;
    /** The uploaded file's size, as the reader saw it. */
    fileBytes?: number | null;
    fileBytesBasis?: string | null;
    /** String records in the file, which is what a dump's class names and thread names live in. */
    strings?: number | null;
    /**
     * A heap dump's header carries the format version, the identifier width and a timestamp, and
     * nothing about the JVM that wrote it — so for a heap dump this is the reader saying so rather
     * than a name. A thread dump states the VM in its header and fills `environment.jvmName`.
     */
    jvmName?: string | null;
  };
  /**
   * A heap dump's census, as the reader walked it.
   *
   * Every byte figure here is what the dump recorded for the objects themselves — an instance's
   * field values, an array's elements — and excludes object headers, class metadata and everything
   * reachable from those objects. It is therefore smaller than the memory the objects occupy and is
   * not a retained size. The measurement rows repeat these numbers with that basis attached; these
   * are here so the run's own provenance is readable on its own.
   */
  heap?: {
    objects?: number | null;
    objectBytes?: number | null;
    instances?: number | null;
    instanceBytes?: number | null;
    arrays?: number | null;
    arrayBytes?: number | null;
    objectArrays?: number | null;
    primitiveArrays?: number | null;
    types?: number | null;
    classesLoaded?: number | null;
    classesDumped?: number | null;
    roots?: number | null;
    /** Present only when it is not zero, which is the only case where it needs saying. */
    objectsWithoutAType?: number | null;
  };
  produced?: {
    measurements?: number | null;
    /** Always zero: this reader classifies nothing. Stated rather than left out. */
    findings?: number | null;
    thresholdsApplied?: boolean | null;
    /** False for a heap dump, and stated: no dominator tree is built, so no size is retained. */
    retainedSizesComputed?: boolean | null;
    /**
     * False for a benchmark result: one file states one run, there is no baseline in the repository,
     * and RepoPilot does not compare two numbers it did not measure under the same conditions.
     */
    comparisonsMade?: boolean | null;
  };
  /**
   * A benchmark result's own facts.
   *
   * The JVM's command line is counted, never carried: a `-D` argument is a credential carrier, which
   * is the same rule the JFR reader applies. `jvmArgumentsBasis` is the reader saying so.
   */
  benchmark?: {
    entries?: number | null;
    /** True when the file held more entries than the reader stores, so the rows are a prefix. */
    truncated?: boolean | null;
    jvmArgumentsRecorded?: number | null;
    jvmArgumentsBasis?: string | null;
    modes?: string[] | null;
    parameters?: string[] | null;
    entriesWithoutIterations?: number | null;
    entriesWithoutErrorEstimate?: number | null;
    warmupTimes?: string[] | null;
    measurementTimes?: string[] | null;
  };
  /** The reader's own prose about what these numbers do and do not mean. */
  note?: string | null;
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

// ---------------------------------------------------------- investigations

export type InvestigationStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

export type ClaimKind = "OBSERVATION" | "INFERENCE" | "RECOMMENDATION";

/** The backend's verdict on one claim. Never the model's own confidence. */
export type ClaimVerification = "VERIFIED" | "PARTIAL" | "UNSUPPORTED";

/** Whether the excerpt a model was shown still matches the file on disk. */
export type EvidenceSourceStatus = "CONFIRMED" | "CHANGED" | "MISSING" | "NOT_LOCATED";

export type InvestigationEvidenceKind =
  | "FINDING"
  | "SYMBOL"
  | "FILE"
  | "MEASUREMENT"
  | "REPOSITORY";

export interface InvestigationSubmission {
  investigationId: Uuid;
  jobId: Uuid;
  status: InvestigationStatus;
}

export interface InvestigationSummary {
  id: Uuid;
  title: string;
  question: string;
  status: InvestigationStatus;
  grounded: boolean;
  confidence: string | null;
  evidenceCount: number | null;
  modelProvider: string | null;
  jobId: Uuid | null;
  createdAt: Instant;
  completedAt: Instant | null;
  errorMessage: string | null;
}

/**
 * One piece of evidence that was in the prompt.
 *
 * There is no excerpt field, and that is deliberate: the text was hashed and sent, and only the
 * hash is stored. `excerptChars` and `sourceStatus` are what a reader gets instead of a prompt log.
 */
export interface InvestigationEvidence {
  key: string;
  kind: InvestigationEvidenceKind;
  label: string;
  detail: string | null;
  path: string | null;
  startLine: number | null;
  endLine: number | null;
  symbolName: string | null;
  symbolKind: string | null;
  retrievalScore: number;
  retrievalMethod: string | null;
  rank: number;
  cited: boolean;
  sourceStatus: EvidenceSourceStatus | null;
  verifiedAt: Instant | null;
  excerptChars: number | null;
}

export interface InvestigationClaim {
  index: number;
  text: string;
  kind: ClaimKind;
  citedKeys: string[];
  verificationStatus: ClaimVerification;
  notes: string | null;
}

export interface InvestigationDiagnostics {
  unanswered: string[];
  danglingMarkers: string[];
  retrievalNotes: string[];
  parserNotes: string[];
}

export interface InvestigationDetail {
  id: Uuid;
  repositoryId: Uuid;
  revisionId: Uuid | null;
  jobId: Uuid | null;
  title: string;
  question: string;
  scope: string | null;
  status: InvestigationStatus;
  answer: string | null;
  confidence: string | null;
  grounded: boolean;
  retrievalSummary: string | null;
  limitations: string | null;
  modelProvider: string | null;
  modelName: string | null;
  promptChars: number | null;
  contextChars: number | null;
  evidenceCount: number | null;
  latencyMs: number | null;
  promptSha256: string | null;
  createdAt: Instant;
  completedAt: Instant | null;
  errorMessage: string | null;
  evidence: InvestigationEvidence[];
  claims: InvestigationClaim[];
  diagnostics: InvestigationDiagnostics;
  verifiedClaims: number;
  partialClaims: number;
  unsupportedClaims: number;
}
