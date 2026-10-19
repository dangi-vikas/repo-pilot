import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { RepositoryRequired } from "@/components/RepositoryRequired";
import {
  Banner,
  Badge,
  Card,
  EmptyState,
  ErrorState,
  Loading,
  Metric,
  PageHeader,
  ProgressBar,
} from "@/components/ui";
import { useRuntimeArtifacts, useRuntimeMeasurements, useRuntimeSummary } from "@/hooks/queries";
import { formatBytes, formatInstant, formatNumber, formatPercent, hasValue } from "@/lib/format";
import type { RuntimeArtifactView, RuntimeMeasurementView, Uuid } from "@/lib/types";
import { useSelection } from "@/state/selection";

/** The metric keys this screen reads, spelled once. A typo in one of them is then a compile error. */
const K = {
  objects: "heapdump.objects.count",
  objectBytes: "heapdump.objects.bytes",
  instances: "heapdump.instances.count",
  instanceBytes: "heapdump.instances.bytes",
  arrays: "heapdump.arrays.count",
  arrayBytes: "heapdump.arrays.bytes",
  primitiveArrays: "heapdump.arrays.primitive.count",
  primitiveArrayBytes: "heapdump.arrays.primitive.bytes",
  objectArrays: "heapdump.arrays.object.count",
  objectArrayBytes: "heapdump.arrays.object.bytes",
  types: "heapdump.types.count",
  classesLoaded: "heapdump.classes.loaded",
  classesDumped: "heapdump.classes.dumped",
  unnamed: "heapdump.objects.unnamed",
  largestObject: "heapdump.object.bytes.max",
  roots: "heapdump.roots.count",
  rootKind: "heapdump.roots.kind.count",
  typesByBytes: "heapdump.types.bytes",
  typesByBytesObserved: "heapdump.types.bytes.observed",
  typesByBytesReported: "heapdump.types.bytes.reported",
  typesByObjects: "heapdump.types.objects",
} as const;

/**
 * The Memory Analysis screen: what one heap dump recorded.
 *
 * The screen makes one large claim and then refuses to make the larger one an operator expects from
 * a heap tool. The claim it does make is exact: a heap dump is a census, so the number of objects of
 * each type and the bytes their fields and elements hold are read out of the file rather than
 * sampled or estimated. The claim it refuses is retained size — what would be freed if an object
 * became unreachable — because that needs a dominator tree over the whole object graph, which this
 * build does not build. So there is no leak verdict here, and the copy says so on every byte figure
 * rather than in a footnote.
 *
 * Three other refusals shape it:
 *
 * <ul>
 *   <li>Recorded bytes are not occupied memory. Every object also has a header the dump does not
 *       write, which is why a type near the top of this table can sit lower in `jmap -histo` for the
 *       same JVM. The two are not in conflict; they count different things.</li>
 *   <li>The census is of the objects the process was still holding. Taking a dump collects first, so
 *       an object that had already become garbage is not in the file — which is also why
 *       `GC.heap_dump` has an `-all` flag. "How many objects are in this heap" has two defensible
 *       answers, and the screen says which one it is showing.</li>
 *   <li>A dump is one instant. Nothing here is a rate, a growth curve or a comparison with an
 *       earlier dump, and a large type is a large type, not a trend.</li>
 *   <li>No threshold is applied and no severity is assigned, because whether a heap is too full is a
 *       policy about a process's purpose and its limit, and RepoPilot has been told neither.</li>
 * </ul>
 */
export function MemoryPage() {
  const { repositoryId } = useSelection();
  const navigate = useNavigate();
  const [chosenRunId, setChosenRunId] = useState<Uuid | null>(null);

  const artifacts = useRuntimeArtifacts(repositoryId);

  // Heap dumps that have been read, newest first. A dump that was uploaded and never analysed is not
  // evidence about anything yet, so it is not offered here; it is listed in the Performance Lab.
  const analysed = useMemo(() => {
    const items = artifacts.data?.items ?? [];
    return items
      .filter((artifact) => artifact.artifactType === "HEAP_DUMP" && hasValue(artifact.analysisRunId))
      .slice()
      .sort((left, right) => right.createdAt.localeCompare(left.createdAt));
  }, [artifacts.data]);

  const selected: RuntimeArtifactView | undefined =
    analysed.find((artifact) => artifact.analysisRunId === chosenRunId) ?? analysed[0];
  const runId = selected?.analysisRunId ?? null;

  const summary = useRuntimeSummary(repositoryId, runId);
  const measurements = useRuntimeMeasurements(
    repositoryId,
    { runId: runId ?? undefined },
    { enabled: runId !== null },
  );

  const rows = useMemo(() => measurements.data?.items ?? [], [measurements.data]);
  const byKey = useMemo(() => {
    const map = new Map<string, RuntimeMeasurementView>();
    for (const row of rows) {
      if (!map.has(row.metricKey)) {
        map.set(row.metricKey, row);
      }
    }
    return map;
  }, [rows]);

  if (repositoryId === null) {
    return (
      <>
        <PageHeader
          title="Memory Analysis"
          description="What one heap dump recorded: objects, types and the bytes they hold."
        />
        <Card>
          <RepositoryRequired />
        </Card>
      </>
    );
  }

  if (artifacts.isLoading) {
    return <Loading label="Loading runtime artefacts" />;
  }

  if (artifacts.isError) {
    return (
      <ErrorState
        message={
          artifacts.error instanceof Error
            ? artifacts.error.message
            : "The artefact list could not be loaded."
        }
        onRetry={() => void artifacts.refetch()}
      />
    );
  }

  if (selected === undefined) {
    return (
      <>
        <PageHeader
          title="Memory Analysis"
          description="What one heap dump recorded: objects, types and the bytes they hold."
        />
        <Card title="No heap dump has been analysed">
          <EmptyState
            icon="memory"
            title="Nothing to report yet"
            message="A heap dump — a snapshot of a running JVM's heap — is uploaded in the Performance Lab and analysed there. This screen then reads the result."
            action={
              <div className="toolbar" style={{ justifyContent: "center" }}>
                <button
                  type="button"
                  className="btn btn-sm btn-primary"
                  onClick={() => navigate("/performance")}
                >
                  Upload a heap dump
                </button>
              </div>
            }
          />
          <HowToTakeADump />
        </Card>
      </>
    );
  }

  const objects = byKey.get(K.objects);
  const objectBytes = byKey.get(K.objectBytes);
  const instances = byKey.get(K.instances);
  const arrays = byKey.get(K.arrays);
  const types = byKey.get(K.types);
  const classesLoaded = byKey.get(K.classesLoaded);
  const classesDumped = byKey.get(K.classesDumped);
  const unnamed = byKey.get(K.unnamed);
  const largestObject = byKey.get(K.largestObject);
  const roots = byKey.get(K.roots);
  const observed = byKey.get(K.typesByBytesObserved);
  const reported = byKey.get(K.typesByBytesReported);

  const byBytes = rows
    .filter((row) => row.metricKey === K.typesByBytes)
    .slice()
    .sort((left, right) => right.value - left.value);

  const byObjects = rows
    .filter((row) => row.metricKey === K.typesByObjects)
    .slice()
    .sort((left, right) => right.value - left.value);

  const rootKinds = rows
    .filter((row) => row.metricKey === K.rootKind)
    .slice()
    .sort((left, right) => right.value - left.value);

  const totalBytes = objectBytes?.value ?? 0;
  const dumpProvenance = summary.data?.provenanceJson;
  const heap = dumpProvenance?.heap;
  const environment = dumpProvenance?.environment;
  const basis = attributeOf(objectBytes, "basis");

  return (
    <>
      <PageHeader
        title="Memory Analysis"
        description="What one heap dump recorded. Recorded bytes, not retained sizes — no thresholds, no severity."
      />

      <Card
        title="The dump this report describes"
        actions={
          analysed.length > 1 ? (
            <select
              className="input"
              style={{ width: "auto" }}
              value={selected.id}
              aria-label="Heap dump"
              onChange={(event) => {
                const artifact = analysed.find((item) => item.id === event.target.value);
                setChosenRunId(artifact?.analysisRunId ?? null);
              }}
            >
              {analysed.map((artifact) => (
                <option key={artifact.id} value={artifact.id}>
                  {artifact.originalFilename} · {formatInstant(artifact.createdAt)}
                </option>
              ))}
            </select>
          ) : undefined
        }
      >
        <div className="grid grid-4">
          <Metric
            label="Objects in the dump"
            value={formatNumber(objects?.value)}
            foot={`${formatNumber(instances?.value)} instance(s), ${formatNumber(arrays?.value)} array(s)`}
          />
          <Metric
            label="Recorded bytes"
            value={objectBytes === undefined ? "—" : formatBytes(objectBytes.value)}
            foot="Field values and element data, excluding object headers"
          />
          <Metric
            label="Types"
            value={formatNumber(types?.value)}
            foot={
              classesDumped?.value === classesLoaded?.value
                ? `${formatNumber(classesLoaded?.value)} class(es) loaded, all dumped`
                : `${formatNumber(classesDumped?.value)} of ${formatNumber(classesLoaded?.value)} class(es) dumped`
            }
          />
          <Metric
            label="Largest single object"
            value={largestObject === undefined ? "—" : formatBytes(largestObject.value)}
            foot={
              hasValue(largestObject?.scope)
                ? `${largestObject.scope} · one object, recorded bytes only`
                : "No object recorded"
            }
          />
        </div>

        <dl className="rp-detail" style={{ marginTop: "var(--space-4)" }}>
          <dt>File</dt>
          <dd>
            <span className="rp-mono">{selected.originalFilename}</span>{" "}
            <span className="rp-faint">
              {formatBytes(selected.sizeBytes)} · {selected.contentHash.slice(0, 12)}…
            </span>
          </dd>
          <dt>Analysed</dt>
          <dd>{formatInstant(summary.data?.startedAt)}</dd>
          {hasValue(dumpProvenance?.dump?.takenAt) ? (
            <>
              <dt>Dump taken</dt>
              <dd>{formatInstant(dumpProvenance?.dump?.takenAt)}</dd>
            </>
          ) : null}
          {hasValue(environment?.formatVersion) ? (
            <>
              <dt>Heap dump format</dt>
              <dd className="rp-mono">
                {environment?.formatVersion}{" "}
                <span className="rp-faint">
                  · {formatNumber(environment?.identifierSize)}-byte identifiers
                </span>
              </dd>
            </>
          ) : null}
          {hasValue(heap?.objectsWithoutAType) && (heap?.objectsWithoutAType ?? 0) > 0 ? (
            <>
              <dt>Objects without a type</dt>
              <dd>
                {formatNumber(heap?.objectsWithoutAType)}{" "}
                <span className="rp-faint">
                  (no LOAD_CLASS record, so they are counted but not attributed)
                </span>
              </dd>
            </>
          ) : null}
        </dl>

        {hasValue(summary.data?.summary) ? (
          <p className="rp-muted" style={{ marginTop: "var(--space-3)" }}>
            {summary.data.summary}
          </p>
        ) : null}
      </Card>

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
      ) : rows.length === 0 ? (
        <Card title="What the dump recorded">
          <EmptyState
            icon="info"
            title="This run recorded no measurements"
            message="A run that read a dump and produced nothing is not the same as no run at all, and the run header above says which happened."
          />
        </Card>
      ) : (
        <>
          <div style={{ marginTop: "var(--space-5)" }}>
            <Card title="What the dump recorded">
              <div className="grid grid-4">
                <Metric
                  label="Instances"
                  value={formatNumber(instances?.value)}
                  foot={`${formatBytes(valueOf(byKey.get(K.instanceBytes)))} of field values recorded`}
                />
                <Metric
                  label="Arrays"
                  value={formatNumber(arrays?.value)}
                  foot={`${formatNumber(byKey.get(K.objectArrays)?.value)} object, ${formatNumber(
                    byKey.get(K.primitiveArrays)?.value,
                  )} primitive`}
                />
                <Metric
                  label="Primitive array bytes"
                  value={formatBytes(valueOf(byKey.get(K.primitiveArrayBytes)))}
                  foot="Element data of byte[], int[], char[] and the rest"
                />
                <Metric
                  label="GC roots"
                  value={formatNumber(roots?.value)}
                  foot="Where a collector starts from; not objects themselves"
                />
              </div>

              <table className="data" style={{ marginTop: "var(--space-4)" }}>
                <thead>
                  <tr>
                    <th>Part of the heap</th>
                    <th className="table-num">Objects</th>
                    <th className="table-num">Recorded bytes</th>
                    <th className="table-num">Share of bytes</th>
                  </tr>
                </thead>
                <tbody>
                  {[
                    { label: "Instances", count: K.instances, bytes: K.instanceBytes },
                    { label: "Object arrays", count: K.objectArrays, bytes: K.objectArrayBytes },
                    {
                      label: "Primitive arrays",
                      count: K.primitiveArrays,
                      bytes: K.primitiveArrayBytes,
                    },
                  ].map((part) => {
                    const count = byKey.get(part.count);
                    const byteRow = byKey.get(part.bytes);
                    return (
                      <tr key={part.label}>
                        <td>{part.label}</td>
                        <td className="table-num">{formatNumber(count?.value)}</td>
                        <td className="table-num">{formatBytes(valueOf(byteRow))}</td>
                        <td className="table-num">
                          {totalBytes === 0 ? "—" : formatPercent(valueOf(byteRow) / totalBytes, 1)}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>

              <p
                className="rp-faint"
                style={{ marginTop: "var(--space-3)", fontSize: "var(--text-xs)" }}
              >
                {basis ??
                  "Recorded bytes are what the dump wrote for the objects themselves; object headers and class metadata are not in them."}
              </p>
            </Card>
          </div>

          <div style={{ marginTop: "var(--space-5)" }}>
            <Card
              title="Types by recorded bytes"
              actions={
                <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                  {formatNumber(reported?.value)} of {formatNumber(observed?.value)} type(s) shown
                </span>
              }
            >
              <p
                className="rp-faint"
                style={{ marginBottom: "var(--space-3)", fontSize: "var(--text-xs)" }}
              >
                {noteOf(observed) ??
                  "The table lists the largest types by the bytes the dump recorded for their objects."}{" "}
                A share is of all the object bytes the dump recorded, not of the twenty rows shown.
              </p>
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th>Type</th>
                      <th>Kind</th>
                      <th className="table-num">Objects</th>
                      <th className="table-num">Bytes</th>
                      <th className="table-num">Share</th>
                    </tr>
                  </thead>
                  <tbody>
                    {byBytes.map((row) => (
                      <tr key={row.id}>
                        <td>
                          <TruncatedType text={hasValue(row.scope) ? row.scope : "an unnamed type"} />
                        </td>
                        <td className="rp-faint nowrap">{kindOf(row)}</td>
                        <td className="table-num">{formatNumber(attributeNumber(row, "objects"))}</td>
                        <td className="table-num nowrap">{formatBytes(row.value)}</td>
                        <td>
                          <div className="toolbar" style={{ gap: "var(--space-2)" }}>
                            <div style={{ flex: 1, minWidth: 56 }}>
                              <ProgressBar value={shareOf(row) * 100} />
                            </div>
                            <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                              {formatPercent(shareOf(row), 1)}
                            </span>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p
                className="rp-faint"
                style={{ marginTop: "var(--space-3)", fontSize: "var(--text-xs)" }}
              >
                {noteOf(byBytes[0]) ??
                  "The bytes are what the dump recorded for these objects, and they are not a retained size."}{" "}
                The largest single object is reported above the tables:{" "}
                {largestObject === undefined
                  ? "none was recorded."
                  : `${formatBytes(largestObject.value)} of ${
                      hasValue(largestObject.scope) ? largestObject.scope : "an unnamed type"
                    }.`}
              </p>
            </Card>
          </div>

          <div style={{ marginTop: "var(--space-5)" }}>
            <Card
              title="Types by object count"
              actions={
                <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                  {formatNumber(reported?.value)} of {formatNumber(observed?.value)} type(s) shown
                </span>
              }
            >
              <p
                className="rp-faint"
                style={{ marginBottom: "var(--space-3)", fontSize: "var(--text-xs)" }}
              >
                A different question from the table above: a type can hold millions of tiny objects
                and almost no bytes, or one enormous array and no objects at all. Shares are of all
                the objects the dump recorded.
              </p>
              <div className="table-wrap">
                <table className="data">
                  <thead>
                    <tr>
                      <th>Type</th>
                      <th>Kind</th>
                      <th className="table-num">Objects</th>
                      <th className="table-num">Share</th>
                      <th className="table-num">Bytes</th>
                    </tr>
                  </thead>
                  <tbody>
                    {byObjects.map((row) => (
                      <tr key={row.id}>
                        <td>
                          <TruncatedType text={hasValue(row.scope) ? row.scope : "an unnamed type"} />
                        </td>
                        <td className="rp-faint nowrap">{kindOf(row)}</td>
                        <td className="table-num">{formatNumber(row.value)}</td>
                        <td>
                          <div className="toolbar" style={{ gap: "var(--space-2)" }}>
                            <div style={{ flex: 1, minWidth: 56 }}>
                              <ProgressBar value={shareOf(row) * 100} />
                            </div>
                            <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                              {formatPercent(shareOf(row), 1)}
                            </span>
                          </div>
                        </td>
                        <td className="table-num nowrap">
                          {formatBytes(attributeNumber(row, "bytes"))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>

          {rootKinds.length > 0 ? (
            <div style={{ marginTop: "var(--space-5)" }}>
              <Card
                title="Garbage-collection roots"
                actions={
                  <Badge tone="neutral">{formatNumber(roots?.value)} root(s) recorded</Badge>
                }
              >
                <p
                  className="rp-faint"
                  style={{ marginBottom: "var(--space-3)", fontSize: "var(--text-xs)" }}
                >
                  Roots are where the collector starts from. The kinds are a fact about the dump and
                  about the JVM, not about the application: interned strings and JNI globals are the
                  JVM's own bookkeeping, while the thread roots are the stacks that were live when the
                  dump was written.
                </p>
                <table className="data">
                  <thead>
                    <tr>
                      <th>Kind of root</th>
                      <th className="table-num">Roots</th>
                      <th className="table-num">Share of roots</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rootKinds.map((row) => (
                      <tr key={row.id}>
                        <td className="rp-mono">{hasValue(row.scope) ? row.scope : "unnamed"}</td>
                        <td className="table-num">{formatNumber(row.value)}</td>
                        <td className="table-num">
                          {valueOf(roots) === 0
                            ? "—"
                            : formatPercent(row.value / valueOf(roots), 1)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Card>
            </div>
          ) : null}

          {unnamed !== undefined ? (
            <div style={{ marginTop: "var(--space-5)" }}>
              <Banner tone="warning" title="Some objects could not be attributed to a type">
                {formatNumber(unnamed.value)} object(s) in this dump have no{" "}
                <span className="rp-mono">LOAD_CLASS</span> record, so the reader counted them and
                their bytes but could not say what type they are. They are in the totals above and
                absent from both type tables, which is why the tables do not sum to the whole.
              </Banner>
            </div>
          ) : null}
        </>
      )}

      <div style={{ marginTop: "var(--space-5)" }}>
        <Banner tone="info" title="What this report is, and what it is not">
          A heap dump is a census, so the counts here are exact — but the bytes are the bytes the dump
          recorded for the objects themselves: field values and element data, with no object header
          and no class metadata, which is why they are smaller than the JVM's own estimate for the
          same objects in <span className="rp-mono">jmap -histo</span>. The objects counted are the
          ones the process was still holding: taking a dump collects first, so anything that had
          already become garbage is not in the file, and these are live-object totals rather than
          everything the process ever allocated. No retained size was computed,
          because that needs a dominator tree RepoPilot does not build, so nothing here is a leak
          verdict: a type with a large figure is a type whose objects hold a lot of recorded data. It
          is also one instant of one process, so nothing here is a rate or a trend, no threshold was
          applied and no severity was assigned.
        </Banner>
      </div>
    </>
  );
}

/**
 * How to produce a dump this reader can use.
 *
 * Shown on the empty state, because the file an operator most often has instead — a class histogram,
 * or a dump the JVM compressed — looks close enough to the right thing until the upload is refused.
 */
function HowToTakeADump() {
  return (
    <div style={{ marginTop: "var(--space-4)" }}>
      <p className="rp-muted">Take one from the running process:</p>
      <pre
        className="rp-mono"
        style={{
          marginTop: "var(--space-2)",
          padding: "var(--space-3)",
          backgroundColor: "var(--bg-canvas)",
          border: "1px solid var(--border-subtle)",
          borderRadius: "var(--radius-md)",
          fontSize: "var(--text-xs)",
          overflowX: "auto",
        }}
      >
        {`jcmd <pid> GC.heap_dump /tmp/heap.hprof
jmap -dump:format=b,file=/tmp/heap.hprof <pid>`}
      </pre>
      <Banner tone="warning" title="A compressed dump and a class histogram are refused by name">
        <span className="rp-mono">GC.heap_dump -gz=&lt;level&gt;</span> writes a gzip stream, which is
        refused with the <span className="rp-mono">gzip -d</span> command that uncompresses it rather
        than decompressed on the fly.{" "}
        <span className="rp-mono">jmap -histo</span> and{" "}
        <span className="rp-mono">jcmd GC.class_histogram</span> write counts without any objects at
        all; those are refused with the command that writes a real dump instead, because a report
        built from one would be a list of numbers with no types and no bytes behind it.
      </Banner>
      <Banner tone="danger" title="A heap dump is process memory">
        The file is the literal contents of the process's heap, including any credential or personal
        data that was live in it. RepoPilot reads it on this machine, stores it on this machine and
        uploads it nowhere, but treat the file and the data directory as sensitive.
      </Banner>
    </div>
  );
}

/** A type name, clipped rather than wrapped so the numeric columns stay aligned. */
function TruncatedType({ text }: { text: string }) {
  return (
    <div
      title={text}
      className="rp-mono"
      style={{
        maxWidth: 320,
        overflow: "hidden",
        textOverflow: "ellipsis",
        whiteSpace: "nowrap",
      }}
    >
      {text}
    </div>
  );
}

// ------------------------------------------------------------------ deriving values

function valueOf(row: RuntimeMeasurementView | undefined): number {
  return row?.value ?? 0;
}

/**
 * A metric's attributes, as data.
 *
 * Read permissively: a measurement whose attributes cannot be parsed still contributes its value,
 * because a value with missing prose is usable and a crashed screen is not.
 */
function attributesOf(row: RuntimeMeasurementView | undefined): Record<string, unknown> {
  if (row === undefined || !hasValue(row.attributesJson) || row.attributesJson.trim() === "") {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(row.attributesJson);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      return {};
    }
    return parsed as Record<string, unknown>;
  } catch {
    return {};
  }
}

function attributeOf(row: RuntimeMeasurementView | undefined, key: string): string | null {
  const attributes = attributesOf(row);
  const value = attributes[key];
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

function attributeNumber(row: RuntimeMeasurementView | undefined, key: string): number {
  const attributes = attributesOf(row);
  const value = attributes[key];
  return typeof value === "number" ? value : 0;
}

/** The reader's own sentence about what a row's figure is, shown rather than paraphrased. */
function noteOf(row: RuntimeMeasurementView | undefined): string | null {
  return attributeOf(row, "note");
}

/** A type's share of the whole, as the backend computed it — never recomputed here. */
function shareOf(row: RuntimeMeasurementView): number {
  const attributes = attributesOf(row);
  const value = attributes["share"];
  return typeof value === "number" ? value : 0;
}

/** What the dump called this kind of object, e.g. `instance` or `object array`. */
function kindOf(row: RuntimeMeasurementView): string {
  return attributeOf(row, "kind") ?? "—";
}
