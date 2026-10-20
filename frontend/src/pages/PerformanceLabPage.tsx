import { Fragment, useMemo, useRef, useState, type ReactNode } from "react";
import { Icon } from "@/components/icons";
import { JobTable } from "@/components/JobTable";
import { RepositoryRequired } from "@/components/RepositoryRequired";
import {
  Badge,
  Banner,
  Card,
  EmptyState,
  ErrorState,
  KeyValue,
  Loading,
  Metric,
  PageHeader,
} from "@/components/ui";
import {
  useAnalyzeArtifact,
  useJobs,
  useRuntimeArtifacts,
  useRuntimeMeasurements,
  useRuntimeSummary,
  useUploadArtifact,
} from "@/hooks/queries";
import { ApiError } from "@/lib/api";
import {
  aggregationLabel,
  formatBytes,
  formatInstant,
  formatMetricValue,
  formatNumber,
  formatRelative,
  formatSampleScope,
  hasValue,
  metricFamily,
  metricLeaf,
  titleCase,
} from "@/lib/format";
import type {
  MetricKind,
  RuntimeArtifactType,
  RuntimeArtifactView,
  RuntimeMeasurementView,
  RuntimeProvenance,
  RuntimeSummaryView,
  Uuid,
} from "@/lib/types";
import { useSelection } from "@/state/selection";

const ARTIFACT_TYPES: RuntimeArtifactType[] = [
  "JFR_RECORDING",
  "HEAP_DUMP",
  "THREAD_DUMP",
  "BENCHMARK_RESULT",
];

const KINDS: MetricKind[] = ["DURATION", "COUNTER", "GAUGE", "SIZE"];

/** The job types this screen produces and reports. */
const RUNTIME_JOB_TYPES = [
  "JFR_ANALYSIS",
  "THREAD_DUMP_ANALYSIS",
  "HEAP_ANALYSIS",
  "BENCHMARK_IMPORT",
];

/**
 * The namespace each reader puts in front of its metric keys.
 *
 * A metric key is `<namespace>.<family>.<leaf>`, and the backend's `metric` filter is a key prefix,
 * so filtering by family means filtering by `<namespace>.<family>`. The namespace is read from the
 * run's artefact type rather than assumed to be `jfr`: a thread dump's keys start `threaddump.`, a
 * heap dump's `heapdump.` and a benchmark result's `benchmark.`, and a family filter that sent
 * `jfr.threads` would quietly return nothing for a run that has rows. A type this map does not know
 * is filtered family-wide rather than wrongly, so an unlisted reader shows everything instead of
 * hiding it.
 */
const METRIC_NAMESPACE: Partial<Record<RuntimeArtifactType, string>> = {
  JFR_RECORDING: "jfr",
  THREAD_DUMP: "threaddump",
  HEAP_DUMP: "heapdump",
  BENCHMARK_RESULT: "benchmark",
};

/**
 * The Performance Lab: recordings in, measurements out.
 *
 * The screen never shows a number without the run it came from. That is the whole
 * discipline: a figure read from a recording of one JVM on one machine is evidence about
 * that run, and the page keeps the artefact, its provenance and the reduction attached to
 * every value. Nothing here is estimated, interpolated or thresholded — the backend
 * applies no thresholds and assigns no severities, and this page says so out loud rather
 * than colouring a number that has no policy behind it.
 */
export function PerformanceLabPage() {
  const { repositoryId } = useSelection();

  if (repositoryId === null) {
    return (
      <>
        <PageHeader
          title="Performance Lab"
          description="Upload a runtime artefact — a JFR recording, a thread dump, a heap dump or a JMH result file — analyse it locally, and read what it measured."
        />
        <RepositoryRequired />
      </>
    );
  }

  return <PerformanceLab repositoryId={repositoryId} />;
}

function PerformanceLab({ repositoryId }: { repositoryId: Uuid }) {
  const jobs = useJobs(undefined);

  // A runtime job in flight changes the answers on this page, so the derived queries poll
  // only while one is running. Otherwise a stale measurement is honest and a number that
  // moves for no visible reason is not.
  const runtimeJobActive =
    jobs.data?.items.some(
      (job) =>
        RUNTIME_JOB_TYPES.includes(job.jobType) &&
        job.repositoryId === repositoryId &&
        (job.status === "QUEUED" || job.status === "RUNNING"),
    ) ?? false;

  const summary = useRuntimeSummary(repositoryId, null, { refetchWhileActive: runtimeJobActive });
  const artifacts = useRuntimeArtifacts(repositoryId, { refetchWhileActive: runtimeJobActive });

  const hasRun = hasValue(summary.data?.runId);

  // Which reader produced the rows under this header, and therefore what their keys start with.
  const artifactType = summary.data?.artifactType ?? null;
  const namespace = artifactType === null ? "" : (METRIC_NAMESPACE[artifactType] ?? "");

  const [family, setFamily] = useState<string>("");
  const [kind, setKind] = useState<MetricKind | "">("");

  const measurementFilters = useMemo(
    () => ({
      // The backend matches `metric` as a key prefix, so a family filter carries the namespace of
      // whichever reader produced the rows. Without it, a family from a thread dump would be sent
      // as `jfr.threads` and match nothing.
      metric:
        family === "" || namespace === ""
          ? undefined
          : `${namespace}.${family}`,
      kind: kind === "" ? undefined : kind,
    }),
    [family, kind, namespace],
  );

  const measurements = useRuntimeMeasurements(repositoryId, measurementFilters, {
    enabled: hasRun,
    refetchWhileActive: runtimeJobActive,
  });

  const families = useMemo(
    () => Object.keys(summary.data?.byMetricFamily ?? {}).sort(),
    [summary.data?.byMetricFamily],
  );

  const runtimeJobs = (jobs.data?.items ?? []).filter(
    (job) => RUNTIME_JOB_TYPES.includes(job.jobType) && job.repositoryId === repositoryId,
  );

  return (
    <>
      <PageHeader
        title="Performance Lab"
        description="Recordings, thread dumps and heap dumps are read locally: JFR through the JDK's own Flight Recorder API, a thread dump through a parser for the format jstack writes, a heap dump through a parser for the HPROF files jcmd writes. A JMH result file is read too — and every figure in it is quoted from the harness that computed it, not measured here. Every number is a measurement of a specific run, never an estimate."
      />

      {runtimeJobActive ? (
        <div style={{ marginBottom: "var(--space-4)" }}>
          <Banner tone="info" title="Runtime evidence is being analysed">
            A job is running on the backend. Measurements appear below when it finishes.
          </Banner>
        </div>
      ) : null}

      <UploadCard repositoryId={repositoryId} onUploaded={() => void artifacts.refetch()} />

      <div className="grid grid-4" style={{ margin: "var(--space-5) 0" }}>
        <Metric
          label="Measurements"
          value={formatNumber(summary.data?.measurementCount ?? 0)}
          foot={hasRun ? "From the newest run" : "No artefact analysed yet"}
        />
        <Metric
          label="Artefacts"
          value={formatNumber(artifacts.data?.totalItems ?? 0)}
          foot="Stored, by content hash"
        />
        <Metric
          label="Metric families"
          value={formatNumber(families.length)}
          foot={families.length > 0 ? families.join(", ") : "—"}
        />
        <Metric
          label="Runtime jobs"
          value={formatNumber(runtimeJobs.length)}
          foot={runtimeJobActive ? "One in flight" : "Finished or queued"}
          accent={runtimeJobActive}
        />
      </div>

      {summary.isLoading ? (
        <Loading label="Loading runtime evidence" />
      ) : summary.isError ? (
        <ErrorState
          message={
            summary.error instanceof Error
              ? summary.error.message
              : "The runtime summary could not be loaded."
          }
          onRetry={() => void summary.refetch()}
        />
      ) : !hasRun ? (
        <Card title="No runtime artefact has been analysed">
          <EmptyState
            icon="gauge"
            title="Nothing has been measured yet"
            message={
              <>
                Upload a recording, a thread dump or a heap dump above and analyse it. This state is
                deliberately not the same as a run that measured nothing: RepoPilot will only report
                numbers a file actually contains.
              </>
            }
          />
        </Card>
      ) : (
        <>
          <RunCard data={summary.data} provenance={summary.data?.provenanceJson ?? null} />

          <Card
            title="Measurements"
            actions={
              <>
                <select
                  className="select"
                  aria-label="Filter by metric family"
                  value={family}
                  onChange={(event) => setFamily(event.target.value)}
                >
                  <option value="">All families</option>
                  {families.map((value) => (
                    <option key={value} value={value}>
                      {titleCase(value)} ({formatNumber(summary.data?.byMetricFamily[value] ?? 0)})
                    </option>
                  ))}
                </select>
                <select
                  className="select"
                  aria-label="Filter by metric kind"
                  value={kind}
                  onChange={(event) => setKind(event.target.value as MetricKind | "")}
                >
                  <option value="">All kinds</option>
                  {KINDS.map((value) => (
                    <option key={value} value={value}>
                      {titleCase(value)}
                    </option>
                  ))}
                </select>
              </>
            }
            flush
          >
            {measurements.isLoading ? (
              <Loading label="Loading measurements" />
            ) : measurements.isError ? (
              <ErrorState
                message={
                  measurements.error instanceof Error
                    ? measurements.error.message
                    : "The measurements could not be loaded."
                }
                onRetry={() => void measurements.refetch()}
              />
            ) : (measurements.data?.items.length ?? 0) === 0 ? (
              <EmptyState
                icon="info"
                title="No measurements under these filters"
                message="The run produced no measurement matching the selected family and kind. Clear a filter to see the rest of the run."
              />
            ) : (
              <MeasurementTable measurements={measurements.data?.items ?? []} />
            )}
          </Card>
        </>
      )}

      <div style={{ marginTop: "var(--space-5)" }}>
        <ArtifactsCard
          artifacts={artifacts.data?.items ?? []}
          isLoading={artifacts.isLoading}
          isError={artifacts.isError}
          onRetry={() => void artifacts.refetch()}
        />
      </div>

      <div style={{ marginTop: "var(--space-5)" }}>
        <Card
          title="Runtime analysis jobs"
          actions={
            <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
              {RUNTIME_JOB_TYPES.join(", ")}
            </span>
          }
          flush
        >
          {jobs.isLoading ? (
            <Loading label="Loading jobs" />
          ) : (
            <JobTable
              jobs={runtimeJobs}
              emptyMessage="No runtime artefact has been analysed yet. Upload one above and it will appear here with its outcome."
            />
          )}
        </Card>
      </div>
    </>
  );
}

/**
 * The upload control.
 *
 * The file goes to the backend as multipart and is stored by content hash. The type is
 * inferred from the extension unless it is stated, and stating it wrongly is caught on the
 * server: a file that is not what it claims is refused with the reason, not analysed into
 * nothing.
 */
function UploadCard({
  repositoryId,
  onUploaded,
}: {
  repositoryId: Uuid;
  onUploaded: () => void;
}) {
  const upload = useUploadArtifact(repositoryId);
  const fileInput = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [type, setType] = useState<RuntimeArtifactType | "">("");
  const [uploaded, setUploaded] = useState<RuntimeArtifactView | null>(null);

  const memoryBearing = type === "HEAP_DUMP";
  const threadDumpBearing = type === "THREAD_DUMP";
  const benchmarkBearing = type === "BENCHMARK_RESULT";

  return (
    <Card
      title="Upload a runtime artefact"
      actions={
        <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
          Stored on this machine only
        </span>
      }
    >
      {upload.isError ? (
        <div style={{ marginBottom: "var(--space-3)" }}>
          <Banner tone="danger" title="The upload was refused">
            {upload.error instanceof ApiError
              ? upload.error.message
              : upload.error instanceof Error
                ? upload.error.message
                : "The file could not be uploaded."}
          </Banner>
        </div>
      ) : null}

      {uploaded !== null ? (
        <div style={{ marginBottom: "var(--space-3)" }}>
          <Banner
            tone={uploaded.supported ? "success" : "warning"}
            title={uploaded.supported ? "Stored" : "Stored, but not readable yet"}
          >
            <span className="rp-mono">{uploaded.originalFilename}</span> was stored as{" "}
            <span className="rp-mono">{uploaded.contentHash.slice(0, 12)}…</span> (
            {formatBytes(uploaded.sizeBytes)}).{" "}
            {uploaded.supported
              ? "Analyse it to produce measurements."
              : (uploaded.unsupportedReason ?? "This build cannot read this kind of file.")}
          </Banner>
        </div>
      ) : null}

      <div className="toolbar">
        <input
          className="input"
          type="file"
          ref={fileInput}
          aria-label="Recording file"
          onChange={(event) => {
            setUploaded(null);
            setFile(event.target.files?.[0] ?? null);
          }}
        />
        <select
          className="select"
          aria-label="Artefact type"
          value={type}
          onChange={(event) => setType(event.target.value as RuntimeArtifactType | "")}
        >
          <option value="">Detect from extension</option>
          {ARTIFACT_TYPES.map((value) => (
            <option key={value} value={value}>
              {titleCase(value)}
            </option>
          ))}
        </select>
        <button
          type="button"
          className="btn btn-primary btn-sm"
          disabled={file === null || upload.isPending}
          onClick={() => {
            if (file === null) {
              return;
            }
            upload.mutate(
              { file, artifactType: type === "" ? undefined : type },
              {
                onSuccess: (view) => {
                  setUploaded(view);
                  setFile(null);
                  // The native input keeps its own state, so clear it or the same filename
                  // stays on screen while the upload button is disabled.
                  if (fileInput.current !== null) {
                    fileInput.current.value = "";
                  }
                  onUploaded();
                },
              },
            );
          }}
        >
          <Icon name="upload" size={14} />
          {upload.isPending ? "Uploading…" : "Upload"}
        </button>
      </div>

      <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-2)" }}>
        The file is streamed to the backend's data directory and addressed by its SHA-256. Only
        derived numbers are ever returned; the file itself stays on this machine.
      </p>

      {memoryBearing ? (
        <div style={{ marginTop: "var(--space-3)" }}>
          <Banner tone="warning" title="This file contains process memory">
            A heap dump is the literal contents of the process's heap, including any string or
            credential that was live in it. RepoPilot stores it locally and never uploads it
            anywhere, but treat the file and the data directory as sensitive.
          </Banner>
        </div>
      ) : null}

      {/*
        A thread dump is not process memory, and saying that it is would teach an operator to
        distrust a warning that matters elsewhere. What it does reveal is stated precisely instead:
        synchronisation structure and every thread and frame name, which is the application's own
        code, and no memory contents at all.
      */}
      {threadDumpBearing ? (
        <div style={{ marginTop: "var(--space-3)" }}>
          <Banner tone="info" title="This file contains no process memory">
            A thread dump records what each thread was doing — its state, its stack of class and
            method names, and the monitors it held or waited for. It is not a copy of the heap and
            contains none of the values in memory, but it does describe your application's structure
            and it names your threads, so treat the file as internal. It is stored locally and never
            uploaded anywhere.
          </Banner>
        </div>
      ) : null}

      {/*
        A benchmark result carries no heap and no stack, but it does carry the JVM's command line and
        the path to the JVM binary — both of which the reader counts and discards rather than storing.
        Saying that at upload time is more honest than the absence of a warning that would be read as
        "nothing sensitive here".
      */}
      {benchmarkBearing ? (
        <div style={{ marginTop: "var(--space-3)" }}>
          <Banner tone="info" title="A benchmark result holds numbers, and a command line">
            Only the array <span className="rp-mono">-rf json</span> writes is read. The file also
            carries the JVM's arguments and the path to its binary, and RepoPilot counts those without
            storing them, because an argument is where a credential usually is. The figures inside are
            the harness's own — this build measures nothing when it imports them.
          </Banner>
        </div>
      ) : null}
    </Card>
  );
}

/** The run header: what was read, and the caveats that apply to everything under it. */
function RunCard({
  data,
  provenance,
}: {
  data: RuntimeSummaryView | undefined;
  provenance: RuntimeProvenance | null;
}) {
  const byKind = data?.byKind ?? {};

  return (
    <Card
      title="Analysis run"
      actions={
        <>
          {hasValue(data?.status) ? (
            <Badge tone={data?.status === "SUCCEEDED" ? "success" : "danger"} dot>
              {titleCase(String(data?.status))}
            </Badge>
          ) : null}
          {hasValue(data?.artifactId) ? (
            <Badge tone="neutral">{data?.artifactType ?? "artefact"}</Badge>
          ) : null}
        </>
      }
    >
      <div className="chip-row" style={{ marginBottom: "var(--space-3)" }}>
        {Object.entries(byKind).map(([metricKind, count]) => (
          <Badge key={metricKind} tone="info">
            {titleCase(metricKind)}: {formatNumber(count ?? 0)}
          </Badge>
        ))}
        <Badge tone="neutral">{formatNumber(data?.measurementCount ?? 0)} total</Badge>
        {hasValue(data?.analyzerVersions) ? (
          <Badge tone="neutral">{data?.analyzerVersions}</Badge>
        ) : null}
      </div>

      <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginBottom: "var(--space-2)" }}>
        {hasValue(data?.artifactName) ? (
          <>
            <span className="rp-mono">{data?.artifactName}</span>
            {hasValue(data?.artifactSizeBytes) ? ` · ${formatBytes(data?.artifactSizeBytes)}` : ""}
          </>
        ) : null}
        {hasValue(data?.startedAt)
          ? `${hasValue(data?.artifactName) ? " · " : ""}read ${formatInstant(data?.startedAt)} (${formatRelative(data?.startedAt)})`
          : ""}
        {hasValue(data?.artifactHash) ? ` · sha256 ${data?.artifactHash?.slice(0, 12)}…` : ""}
      </p>

      {hasValue(data?.summary) ? (
        <p className="rp-muted" style={{ fontSize: "var(--text-sm)", marginBottom: "var(--space-3)" }}>
          {data?.summary}
        </p>
      ) : null}

      {/*
        A benchmark result is the one artefact on this screen that RepoPilot did not observe. It is a
        statement by a harness on another machine, and every figure under it was computed there. The
        banner says that instead of the runtime one, because "one process, on one machine, over one
        window" would describe a recording that does not exist.
      */}
      {data?.artifactType === "BENCHMARK_RESULT" ? (
        <Banner tone="warning" title="Quoted from the benchmark harness — not measured here">
          Every score, error, confidence interval and percentile on this page was computed by the
          benchmark harness that ran on the machine the file describes. RepoPilot repeats them without
          changing them and applies no threshold and no severity. One file states one run and this
          repository holds no baseline, so nothing here is compared with anything.
        </Banner>
      ) : (
        <Banner tone="info" title="No threshold was applied and no severity was assigned">
          These are quantities read from one runtime artefact: one process, on one machine, over one
          window or one instant. RepoPilot does not decide whether a value is good or bad without a
          policy you supplied, so it reports the measurement and its limits and leaves the judgement to
          you.
        </Banner>
      )}

      {provenance !== null ? (
        <div style={{ marginTop: "var(--space-3)" }}>
          <KeyValue items={provenanceItems(provenance)} />
        </div>
      ) : null}
    </Card>
  );
}

/**
 * Flattens the stored provenance into rows.
 *
 * The long values (`cpu`, `osVersion`) are multi-line blobs the JVM wrote; they are shown by
 * their first line with the rest available on hover rather than dumped into the panel, and the
 * count of hidden lines is stated. Nothing is summarised away silently: a reader can still get the
 * JVM's exact words, and can see that there are more of them.
 */
function provenanceItems(provenance: RuntimeProvenance): Array<{ label: string; value: ReactNode }> {
  const recording = provenance.recording ?? {};
  const dump = provenance.dump ?? {};
  const heap = provenance.heap ?? {};
  const environment = provenance.environment ?? {};
  const produced = provenance.produced ?? {};

  const items: Array<{ label: string; value: ReactNode }> = [];
  const add = (label: string, value: string | number | null | undefined): void => {
    if (hasValue(value) && String(value).length > 0) {
      items.push({ label, value: String(value) });
    }
  };
  const addBlob = (label: string, value: string | null | undefined): void => {
    if (!hasValue(value) || value.trim().length === 0) {
      return;
    }
    const lines = value.trim().split(/\r?\n/).filter((line) => line.trim().length > 0);
    const first = lines[0]?.trim() ?? "";
    if (first.length === 0) {
      return;
    }
    // The JDK's own descriptions put the value on the line after a bare label: the OS block starts
    // with "OS:" and then " Windows 11 , 64 bit Build 26100 …". Showing the first line alone would
    // render an operating system as the word "OS:", so a bare label takes its next line with it.
    const takesNextLine = first.endsWith(":") && lines.length > 1;
    const shown = takesNextLine ? `${first} ${(lines[1] ?? "").trim()}` : first;
    const rest = lines.length - (takesNextLine ? 2 : 1);
    items.push({
      label,
      value:
        rest <= 0 ? (
          shown
        ) : (
          <span title={value.trim()}>
            {shown} <span className="rp-faint">(+{formatNumber(rest)} more line(s))</span>
          </span>
        ),
    });
  };

  add("Recording", recording.name);
  if (hasValue(recording.firstEventAt) || hasValue(recording.lastEventAt)) {
    add(
      "Window",
      `${formatInstant(recording.firstEventAt)} → ${formatInstant(recording.lastEventAt)}`,
    );
  }
  add("Events read", recording.events != null ? formatNumber(recording.events) : null);
  add("Event types", recording.eventTypes != null ? formatNumber(recording.eventTypes) : null);

  // The header's timestamp, with the reader's own basis for it alongside rather than paraphrased
  // here. The readers disagree about what it means and each is right: a thread dump writes local
  // time with no zone, so it is shown as text and never parsed into an instant, while a heap dump
  // writes UTC and the reader converted it. Which one this is changes the value by hours.
  add("Dump taken", dump.takenAt);
  add("Dump time basis", dump.takenAtBasis);
  if (hasValue(dump.threads)) {
    const own = hasValue(dump.threadsWithoutJavaState)
      ? `, ${formatNumber(dump.threadsWithoutJavaState ?? 0)} without a Java state`
      : "";
    add(
      "Threads",
      `${formatNumber(dump.threads ?? 0)} total, ${formatNumber(dump.javaThreads ?? 0)} with a Java state${own}`,
    );
  }
  add("Deadlock cycles reported", dump.deadlocks != null ? formatNumber(dump.deadlocks) : null);
  add(
    "Contended monitors",
    dump.contendedMonitors != null ? formatNumber(dump.contendedMonitors) : null,
  );

  // A heap dump's header and the census behind the measurement rows. The byte figures are repeated
  // here only as the reader's own totals, and the measurement rows carry the same numbers with their
  // basis attached; nothing on this list is a retained size.
  add("Heap dump format", environment.formatVersion);
  add(
    "Identifier size",
    environment.identifierSize != null
      ? `${formatNumber(environment.identifierSize)} bytes`
      : null,
  );
  add("Strings in the file", dump.strings != null ? formatNumber(dump.strings) : null);
  add("Objects recorded", heap.objects != null ? formatNumber(heap.objects) : null);
  add("Recorded bytes", heap.objectBytes != null ? formatBytes(heap.objectBytes) : null);
  add("Types", heap.types != null ? formatNumber(heap.types) : null);
  add(
    "Objects without a type",
    heap.objectsWithoutAType != null ? formatNumber(heap.objectsWithoutAType) : null,
  );
  add(
    "Retained sizes computed",
    produced.retainedSizesComputed === true
      ? "yes"
      : produced.retainedSizesComputed === false
        ? "no — RepoPilot builds no dominator tree, so it reports no retained size"
        : null,
  );

  // The name and the build string are shown as the file states them, separately. Re-deriving a
  // shortened label here would mean re-implementing the backend's parsing, and a second
  // implementation of the same string handling is a second place it can be wrong. The run summary
  // above already carries the readable form. A heap dump names no VM at all, and the reader's
  // sentence saying so is the honest value for this row.
  add("VM", environment.jvmName ?? dump.jvmName);
  add("VM build", environment.jvmVersion);
  add("JMH version", environment.jmhVersion);
  add("JDK", environment.jdkVersion);
  add("VM started", environment.jvmStartTime != null ? formatInstant(environment.jvmStartTime) : null);
  add("Process id", environment.processId != null ? formatNumber(environment.processId) : null);
  addBlob("Operating system", environment.osVersion);
  addBlob("CPU", environment.cpu);
  if (hasValue(environment.cpuCores) || hasValue(environment.hardwareThreads)) {
    add(
      "Cores",
      `${formatNumber(environment.cpuCores ?? 0)} core(s), ${formatNumber(
        environment.hardwareThreads ?? 0,
      )} hardware thread(s)`,
    );
  }
  if (hasValue(environment.physicalMemoryBytes)) {
    const used = hasValue(environment.physicalMemoryUsedBytes)
      ? ` (${formatBytes(environment.physicalMemoryUsedBytes)} in use at recording start)`
      : "";
    add(
      "Physical memory",
      `${formatBytes(environment.physicalMemoryBytes)}${used}`,
    );
  }

  // A benchmark result's own facts. The JVM's command line is counted and never carried — a `-D`
  // argument is a credential carrier, which is the same rule the JFR reader applies — so the basis
  // row is shown beside the count rather than the count being left to imply the arguments are here.
  const benchmark = provenance.benchmark ?? {};
  add("Benchmark entries", benchmark.entries != null ? formatNumber(benchmark.entries) : null);
  add(
    "Entries truncated",
    benchmark.truncated === true
      ? "yes — the file held more entries than this run stored, in file order"
      : null,
  );
  add("Modes", (benchmark.modes ?? []).join(", "));
  add("Parameters", (benchmark.parameters ?? []).join(", "));
  add("Warmup time", (benchmark.warmupTimes ?? []).join(", "));
  add("Measurement time", (benchmark.measurementTimes ?? []).join(", "));
  add(
    "JVM arguments recorded",
    benchmark.jvmArgumentsRecorded != null
      ? `${formatNumber(benchmark.jvmArgumentsRecorded)} in the file, none stored`
      : null,
  );
  add("JVM arguments basis", benchmark.jvmArgumentsBasis);
  add(
    "Entries without per-iteration values",
    benchmark.entriesWithoutIterations != null
      ? formatNumber(benchmark.entriesWithoutIterations)
      : null,
  );
  add(
    "Entries without an error estimate",
    benchmark.entriesWithoutErrorEstimate != null
      ? formatNumber(benchmark.entriesWithoutErrorEstimate)
      : null,
  );

  // The reader's own account of what it did not do. Stated as facts rather than left implicit,
  // because a reader is entitled to know that nothing here decides good from bad.
  add("Measurements", produced.measurements != null ? formatNumber(produced.measurements) : null);
  add("Findings", produced.findings != null ? formatNumber(produced.findings) : "0");
  add("Thresholds applied", produced.thresholdsApplied === true ? "yes" : "no");
  add(
    "Comparisons made",
    produced.comparisonsMade === true
      ? "yes"
      : produced.comparisonsMade === false
        ? "no — one file states one run, and there is no baseline in this repository"
        : null,
  );

  return items;
}

/**
 * A table cell whose text is clipped to a fixed width, with the full value kept as a tooltip.
 *
 * The clipping is done by a block element rather than by `max-width` on the `<td>`: a table cell in
 * an auto-layout table takes its minimum width from its longest unbreakable content, so a class name
 * like `java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject` widens the column past
 * the panel and pushes the value column — the one column that matters — off the right edge.
 */
function TruncatedCell({
  text,
  width,
  mono = false,
}: {
  text: string;
  width: number;
  mono?: boolean;
}) {
  return (
    <div
      title={text}
      className={mono ? "rp-mono" : undefined}
      style={{
        maxWidth: width,
        overflow: "hidden",
        textOverflow: "ellipsis",
        whiteSpace: "nowrap",
      }}
    >
      {text}
    </div>
  );
}

/**
 * The measurements, as a table.
 *
 * Every row carries its unit, its reduction and its sample count. A row can be expanded to
 * show the metric's own caveats, because "this channel is threshold-gated" changes what the
 * number means and must be readable next to it.
 */
function MeasurementTable({ measurements }: { measurements: RuntimeMeasurementView[] }) {
  const [expanded, setExpanded] = useState<string | null>(null);

  return (
    <div className="table-wrap">
      {/*
        A fixed layout, because this table has six columns and the ones that matter most — the
        value and its unit — are the ones an auto layout pushes off the right edge. An auto-layout
        table takes each column's width from its widest cell, so an identifier such as
        `java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject` in the scope column
        silently widens the whole table past the panel. Fixed proportions mean the columns are the
        same width whatever the data contains, and the cells that can be long are the ones that
        clip (scope) or wrap (measure, value).
      */}
      <table className="data data-fixed">
        <colgroup>
          <col style={{ width: "25%" }} />
          <col style={{ width: "21%" }} />
          <col style={{ width: "13%" }} />
          <col style={{ width: "16%" }} />
          <col style={{ width: "13%" }} />
          <col style={{ width: "12%" }} />
        </colgroup>
        <thead>
          <tr>
            <th>Measure</th>
            <th>Scope</th>
            <th>Kind</th>
            <th className="table-num">Value</th>
            <th>Reduction</th>
            <th className="table-num">Samples</th>
          </tr>
        </thead>
        <tbody>
          {measurements.map((row) => {
            const attributes = parseAttributes(row.attributesJson);
            const isOpen = expanded === row.id;
            return (
              <Fragment key={row.id}>
                <tr
                  className="clickable"
                  onClick={() => setExpanded(isOpen ? null : row.id)}
                >
                  <td>
                    <span className="rp-mono">{metricFamily(row.metricKey)}</span>
                    <span className="rp-faint"> · </span>
                    <span className="rp-mono">{metricLeaf(row.metricKey)}</span>
                  </td>
                  <td>
                    <TruncatedCell
                      text={hasValue(row.scope) ? row.scope : "—"}
                      width={170}
                      mono={hasValue(row.scope)}
                    />
                  </td>
                  <td>
                    <Badge tone="neutral">{titleCase(row.metricKind)}</Badge>
                  </td>
                  <td className="table-num">{formatMetricValue(row.value, row.unit)}</td>
                  <td>{aggregationLabel(row.aggregation)}</td>
                  <td className="table-num">{row.sampleCount < 1 ? "—" : formatNumber(row.sampleCount)}</td>
                </tr>
                {isOpen ? (
                  <tr>
                    <td colSpan={6} style={{ backgroundColor: "var(--bg-canvas)" }}>
                      <p className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                        {formatSampleScope(row.aggregation, row.sampleCount)} · recorded{" "}
                        {formatInstant(row.createdAt)}
                      </p>
                      {attributes.length === 0 ? (
                        <p className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                          No caveats recorded for this metric.
                        </p>
                      ) : (
                        <dl className="rp-detail">
                          {attributes.map(([key, value]) => (
                            <Fragment key={key}>
                              <dt>{titleCase(key)}</dt>
                              <dd>{value}</dd>
                            </Fragment>
                          ))}
                        </dl>
                      )}
                    </td>
                  </tr>
                ) : null}
              </Fragment>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/** Metric caveats, which are a small string map. Anything else is ignored rather than shown raw. */
function parseAttributes(raw: string | null | undefined): Array<[string, string]> {
  if (!hasValue(raw) || raw.trim() === "") {
    return [];
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      return [];
    }
    return Object.entries(parsed as Record<string, unknown>).map(([key, value]) => [
      key,
      typeof value === "string" ? value : JSON.stringify(value),
    ]);
  } catch {
    return [];
  }
}

/** Stored artefacts, including any whose bytes retention has removed. */
function ArtifactsCard({
  artifacts,
  isLoading,
  isError,
  onRetry,
}: {
  artifacts: RuntimeArtifactView[];
  isLoading: boolean;
  isError: boolean;
  onRetry: () => void;
}) {
  const { repositoryId } = useSelection();
  const analyze = useAnalyzeArtifact(repositoryId);

  return (
    <Card title="Artefacts" flush>
      {analyze.isError ? (
        <div style={{ padding: "var(--space-3) var(--space-4)" }}>
          <Banner tone="danger" title="The analysis could not be queued">
            {analyze.error instanceof Error ? analyze.error.message : "Unknown error."}
          </Banner>
        </div>
      ) : null}

      {isLoading ? (
        <Loading label="Loading artefacts" />
      ) : isError ? (
        <ErrorState message="The artefact list could not be loaded." onRetry={onRetry} />
      ) : artifacts.length === 0 ? (
        <EmptyState
          icon="file"
          title="No artefacts"
          message="Nothing has been uploaded for this repository. Upload a runtime artefact above; it is stored by content hash before anything reads it."
        />
      ) : (
        <div className="table-wrap">
          <table className="data">
            <thead>
              <tr>
                <th>File</th>
                <th>Type</th>
                <th className="table-num">Size</th>
                <th>Stored</th>
                <th>Read</th>
                <th className="table-num">Measurements</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {artifacts.map((artifact) => (
                <tr key={artifact.id}>
                  <td>
                    <TruncatedCell text={artifact.originalFilename} width={240} />
                    <div className="rp-faint rp-mono" style={{ fontSize: "var(--text-xs)" }}>
                      {artifact.contentHash.slice(0, 12)}…
                    </div>
                  </td>
                  <td className="nowrap">
                    <Badge tone={artifact.supported ? "info" : "warning"}>
                      {titleCase(artifact.artifactType)}
                    </Badge>
                    {artifact.containsProcessMemory ? (
                      <Badge tone="warning">process memory</Badge>
                    ) : null}
                    {/*
                      The bytes were removed by retention but the row, the run and every measurement
                      are still here. That is a different state from "not uploaded" and from "not
                      analysed", and collapsing it into either would lose the reason the Analyse button
                      is unavailable.
                    */}
                    {hasValue(artifact.purgedAt) ? (
                      <Badge tone="neutral">file purged</Badge>
                    ) : null}
                  </td>
                  <td className="table-num nowrap">
                    {formatBytes(artifact.sizeBytes)}
                    {hasValue(artifact.purgedAt) ? (
                      <div className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                        bytes released
                      </div>
                    ) : null}
                  </td>
                  <td className="nowrap">{formatRelative(artifact.createdAt)}</td>
                  <td>
                    {hasValue(artifact.analysisRunId) ? (
                      <span className="rp-mono">{String(artifact.analysisRunId).slice(0, 8)}</span>
                    ) : (
                      <span className="rp-faint">not analysed</span>
                    )}
                    {!artifact.supported && hasValue(artifact.unsupportedReason) ? (
                      <TruncatedCell text={artifact.unsupportedReason} width={220} />
                    ) : null}
                    {hasValue(artifact.purgedAt) ? (
                      <div className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                        file removed {formatRelative(artifact.purgedAt)}; upload it again to re-analyse
                      </div>
                    ) : null}
                  </td>
                  <td className="table-num">{formatNumber(artifact.measurementCount)}</td>
                  <td>
                    <button
                      type="button"
                      className="btn btn-sm"
                      disabled={!artifact.supported || hasValue(artifact.purgedAt) || analyze.isPending}
                      title={
                        hasValue(artifact.purgedAt)
                          ? "The stored file was removed by retention. Its measurements are still here, but there is nothing left to analyse; upload the file again first."
                          : artifact.supported
                            ? "Queue the analysis of this artefact"
                            : (artifact.unsupportedReason ?? "This build cannot read this file.")
                      }
                      onClick={() => analyze.mutate(artifact.id)}
                    >
                      <Icon name="play" size={14} />
                      Analyse
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
