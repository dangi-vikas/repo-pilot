import type {
  CoverageView,
  FindingSeverity,
  JobStatus,
  MeasurementAggregation,
  ParseStatus,
  RepositoryStatus,
  VerificationStatus,
} from "@/lib/types";

/** A badge tone, mapped to the `.badge-*` classes. */
export type Tone = "neutral" | "success" | "warning" | "danger" | "info" | "accent";

/**
 * True when a value is present.
 *
 * The backend omits null-valued properties (`default-property-inclusion: non_null`),
 * so a missing value arrives as `undefined` while an explicitly-null one would arrive
 * as `null`. Both mean "no value", and every optional API field must be tested with
 * this rather than against `null` — a `field !== null` check passes for `undefined`
 * and then throws on the very next property access.
 */
export function hasValue<T>(value: T | null | undefined): value is T {
  return value !== null && value !== undefined;
}

/**
 * Formats a recorded source line, which is 1-based.
 *
 * The indexer writes `0` when JavaParser had no position for a node, so zero is "not
 * recorded" rather than "line zero" and must never be shown as a real citation.
 */
export function formatLine(line: number | null | undefined): string {
  return hasValue(line) && line > 0 ? String(line) : "—";
}

/** Formats an inclusive line range, collapsing it when start and end coincide. */
export function formatLineRange(
  start: number | null | undefined,
  end: number | null | undefined,
): string {
  const from = hasValue(start) && start > 0 ? start : null;
  const to = hasValue(end) && end > 0 ? end : null;
  if (from === null && to === null) {
    return "—";
  }
  if (from !== null && to !== null && to !== from) {
    return `${from}–${to}`;
  }
  return String(from ?? to);
}

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "—";
  }
  return new Intl.NumberFormat().format(value);
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) {
    return "—";
  }
  if (bytes === 0) {
    return "0 B";
  }
  const units = ["B", "KB", "MB", "GB", "TB"];
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const value = bytes / Math.pow(1024, exponent);
  const digits = exponent === 0 ? 0 : value >= 100 ? 0 : value >= 10 ? 1 : 2;
  return `${value.toFixed(digits)} ${units[exponent] ?? "B"}`;
}

export function formatPercent(value: number, digits = 0): string {
  if (!Number.isFinite(value)) {
    return "—";
  }
  return `${(value * 100).toFixed(digits)}%`;
}

/**
 * Human labels for the scan's skip reasons.
 *
 * The API returns the reason codes the walker uses (`SECRET_BEARING=3`), because the
 * codes are stable identifiers. The label is presentation, and it lives here so the
 * same phrase is used everywhere a skip is explained.
 */
const SKIP_REASON_LABELS: Record<string, string> = {
  VERSION_CONTROL: "version-control files",
  BUILD_OUTPUT: "build-output files",
  DEPENDENCY_CACHE: "dependency-cache files",
  IDE_METADATA: "IDE metadata files",
  SECRET_BEARING: "credential files",
  GENERATED: "generated files",
  UNSUPPORTED_LANGUAGE: "unrecognised language files",
  BINARY: "binary files",
  SIZE_LIMIT: "over the size limit",
  REPOSITORY_LIMIT: "past the repository file limit",
  SYMLINK: "symbolic links",
  UNREADABLE: "unreadable files",
};

/**
 * Turns the API's `REASON=count` breakdown into a readable phrase.
 *
 * Returns `null` rather than an empty string when there is nothing to explain, so a
 * caller can decide whether to render anything at all.
 */
export function formatSkipReasons(encoded: string | null | undefined): string | null {
  if (!hasValue(encoded) || encoded.trim().length === 0) {
    return null;
  }
  const parts = encoded
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part.length > 0)
    .map((part) => {
      const separator = part.indexOf("=");
      if (separator <= 0) {
        return part;
      }
      const reason = part.slice(0, separator);
      const count = part.slice(separator + 1);
      const label = SKIP_REASON_LABELS[reason] ?? titleCase(reason);
      return `${count} ${label}`;
    });
  return parts.length > 0 ? parts.join(", ") : null;
}

function plural(count: number, singular: string, pluralForm = `${singular}s`): string {
  return count === 1 ? singular : pluralForm;
}

/**
 * One sentence describing what a revision's scan actually covered.
 *
 * Deliberately a sentence rather than a set of chips: "3 003 files indexed" reads as a
 * complete account, while "3 003 of 3 008 files inspected; 5 not read, 2 of them
 * credentials" cannot. Rendered from the API's numbers only.
 */
export function formatCoverageSentence(coverage: CoverageView): string {
  const segments: string[] = [
    `Inspected ${formatNumber(coverage.enumeratedFiles)} ${plural(coverage.enumeratedFiles, "file")}: `
      + `${formatNumber(coverage.parsedFiles + coverage.problemFiles)} read`,
  ];
  if (coverage.recordedFiles > 0) {
    // Read and stored, but no analyzer exists for the language yet. Said out loud so the
    // gap between the file count and the symbol count has a visible explanation.
    segments.push(`${formatNumber(coverage.recordedFiles)} recorded without an analyzer`);
  }
  if (coverage.excludedFiles > 0) {
    const count = formatNumber(coverage.excludedFiles);
    const reasons = formatSkipReasons(coverage.excludedByReason);
    segments.push(reasons === null
      ? `${count} deliberately not read`
      : `${count} deliberately not read (${reasons})`);
  }
  if (coverage.prunedDirectories > 0) {
    const count = formatNumber(coverage.prunedDirectories);
    const directories = plural(coverage.prunedDirectories, "directory", "directories");
    const reasons = formatSkipReasons(coverage.prunedByReason);
    segments.push(reasons === null
      ? `${count} ${directories} not walked`
      : `${count} ${directories} not walked (${reasons})`);
  }
  return `${segments.join("; ")}.`;
}

/**
 * Renders a measured value with its unit.
 *
 * A bare number is not a measurement: `2845456` says nothing, `2.71 MB` says something.
 * The unit string comes from the backend and is never inferred here, because a client
 * that guesses a unit is a client that can print the wrong one.
 *
 * Note `weight`: the recording's allocation samples carry a sampling weight, not a byte
 * count. It is rendered as a number and explicitly labelled a weight so it can never be
 * mistaken for a memory figure.
 */
export function formatMetricValue(value: number, unit: string): string {
  if (!Number.isFinite(value)) {
    return "—";
  }
  switch (unit) {
    case "bytes":
      return formatBytes(value);
    case "ms":
      return formatMillis(value);
    case "%":
      return `${value.toFixed(1)}%`;
    case "count":
      return formatNumber(value);
    case "weight":
      return `${formatNumber(value)} weight`;
    default:
      return `${formatNumber(value)} ${unit}`;
  }
}

/** A millisecond figure at a readable scale, without inventing precision. */
function formatMillis(ms: number): string {
  const magnitude = Math.abs(ms);
  if (magnitude >= 1000) {
    return `${(ms / 1000).toFixed(2)} s`;
  }
  if (magnitude >= 10) {
    return `${ms.toFixed(1)} ms`;
  }
  if (magnitude >= 1) {
    return `${ms.toFixed(2)} ms`;
  }
  return `${ms.toFixed(3)} ms`;
}

/** The word for how a value's samples were reduced. */
export function aggregationLabel(aggregation: MeasurementAggregation): string {
  switch (aggregation) {
    case "COUNT":
      return "count";
    case "SUM":
      return "sum";
    case "MEAN":
      return "mean";
    case "MAX":
      return "max";
  }
}

/**
 * The reduction and its sample count: `max of 92 samples`.
 *
 * Rendered next to every value because `128 ms` and `128 ms (max of 92 samples)` support
 * different conclusions, and only the second can be judged. A sample count of zero is not a
 * missing value: it is the backend saying the figure reduces no samples at all (the recording's
 * own window, for instance), and it must not be rendered as `max of 0 samples` — an arithmetic
 * claim about an empty set rather than a description of one derived span.
 */
export function formatSampleScope(
  aggregation: MeasurementAggregation,
  sampleCount: number,
): string {
  if (sampleCount < 1) {
    return "one derived value, not a reduction over samples";
  }
  const samples = sampleCount === 1 ? "1 sample" : `${formatNumber(sampleCount)} samples`;
  return `${aggregationLabel(aggregation)} of ${samples}`;
}

/**
 * The family segment of a metric key: `gc` from `jfr.gc.pause.max`.
 *
 * The backend groups by this segment in `byMetricFamily`, so the UI groups by the same
 * rule rather than re-deriving one that could disagree with the counts it was given.
 */
export function metricFamily(metricKey: string): string {
  const segments = metricKey.split(".");
  return segments.length > 1 ? (segments[1] ?? metricKey) : metricKey;
}

/** The part of a metric key after its family: `pause.max` from `jfr.gc.pause.max`. */
export function metricLeaf(metricKey: string): string {
  const segments = metricKey.split(".");
  return segments.length > 2 ? segments.slice(2).join(".") : metricKey;
}

export function formatInstant(instant: string | null | undefined): string {
  if (!instant) {
    return "—";
  }
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return "—";
  }
  return date.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

export function formatRelative(instant: string | null | undefined): string {
  if (!instant) {
    return "—";
  }
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return "—";
  }
  const deltaSeconds = Math.round((Date.now() - date.getTime()) / 1000);
  const abs = Math.abs(deltaSeconds);
  if (abs < 45) {
    return deltaSeconds >= 0 ? "just now" : "in a moment";
  }
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ["second", 60],
    ["minute", 60],
    ["hour", 24],
    ["day", 7],
    ["week", 4.348],
    ["month", 12],
    ["year", Number.POSITIVE_INFINITY],
  ];
  let value = deltaSeconds;
  for (const [unit, factor] of units) {
    if (Math.abs(value) < factor || unit === "year") {
      const rounded = Math.round(value);
      return new Intl.RelativeTimeFormat(undefined, { numeric: "auto" }).format(-rounded, unit);
    }
    value /= factor;
  }
  return "—";
}

export function formatDuration(from: string | null, to: string | null): string {
  if (!from) {
    return "—";
  }
  const start = new Date(from).getTime();
  const end = to ? new Date(to).getTime() : Date.now();
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return "—";
  }
  const ms = Math.max(0, end - start);
  if (ms < 1000) {
    return `${ms} ms`;
  }
  const seconds = ms / 1000;
  if (seconds < 60) {
    return `${seconds.toFixed(1)} s`;
  }
  const minutes = Math.floor(seconds / 60);
  const remainder = Math.round(seconds % 60);
  return `${minutes}m ${remainder}s`;
}

// ---------------------------------------------------------------- tones

export function jobStatusTone(status: JobStatus): Tone {
  switch (status) {
    case "SUCCEEDED":
      return "success";
    case "RUNNING":
      return "info";
    case "QUEUED":
      return "neutral";
    case "FAILED":
      return "danger";
    case "CANCELLED":
      return "warning";
  }
}

export function repositoryStatusTone(status: RepositoryStatus): Tone {
  switch (status) {
    case "READY":
      return "success";
    case "INDEXING":
      return "info";
    case "FAILED":
      return "danger";
    case "REGISTERED":
      return "neutral";
    case "ARCHIVED":
      return "neutral";
  }
}

export function parseStatusTone(status: ParseStatus): Tone {
  switch (status) {
    case "PARSED":
      return "success";
    case "PARSED_WITH_PROBLEMS":
      return "warning";
    case "FAILED":
      return "danger";
    case "RECORDED":
      // Informational, not a problem: the file was read and stored, and no analyzer for its
      // language has run yet.
      return "info";
    case "EXCLUDED":
    case "SKIPPED":
      return "neutral";
  }
}

/**
 * Badge tone for a finding's severity.
 *
 * Only `CRITICAL` and `HIGH` are a danger tone. If everything above low were red, the
 * colour would stop carrying information, which is the failure mode of a severity scale
 * rendered as decoration.
 */
export function severityTone(severity: FindingSeverity): Tone {
  switch (severity) {
    case "CRITICAL":
    case "HIGH":
      return "danger";
    case "MEDIUM":
      return "warning";
    case "LOW":
      return "info";
    case "INFO":
      return "neutral";
  }
}

/** Badge tone for a finding's verification status. A stale citation is a rule defect. */
export function verificationTone(status: VerificationStatus): Tone {
  switch (status) {
    case "SOURCE_CONFIRMED":
      return "success";
    case "LOCATION_STALE":
      return "danger";
    case "NO_LOCATION_CLAIMED":
      return "neutral";
  }
}

/**
 * `path:line` for a finding, or just the path when no line was claimed.
 *
 * Never renders zero. A rule that reports line 0 is reported as claiming no line, and
 * a citation of "line 0" is exactly the kind of plausible-looking nonsense that must not
 * reach a reader.
 */
export function formatFindingLocation(
  sourcePath: string | null | undefined,
  startLine: number | null | undefined,
): string {
  if (!hasValue(sourcePath)) {
    return "—";
  }
  return hasValue(startLine) && startLine > 0 ? `${sourcePath}:${startLine}` : sourcePath;
}

/**
 * Human label for the evidence category carried on every finding.
 *
 * Kept in one place because the whole product depends on these four words meaning
 * the same thing everywhere: an observation is a confirmed fact, a measurement is
 * from a specific run, and a hypothesis is the model's guess.
 */
export function evidenceLabel(category: string): string {
  switch (category) {
    case "OBSERVATION":
      return "Observation";
    case "STATIC_FINDING":
      return "Static finding";
    case "RUNTIME_MEASUREMENT":
      return "Runtime measurement";
    case "AI_HYPOTHESIS":
      return "AI hypothesis";
    default:
      return category;
  }
}

export function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split(/[_\s]+/)
    .filter((part) => part.length > 0)
    .map((part) => (part[0]?.toUpperCase() ?? "") + part.slice(1))
    .join(" ");
}

/** A short, stable colour for a revision/short label, used for avatars and chips. */
export function hueFromString(value: string): number {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) % 360;
  }
  return hash;
}
