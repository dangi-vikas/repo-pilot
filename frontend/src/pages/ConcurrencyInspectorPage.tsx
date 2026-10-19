import { Fragment, useMemo, useState } from "react";
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
import { formatInstant, formatNumber, formatPercent, hasValue } from "@/lib/format";
import type { RuntimeArtifactView, RuntimeMeasurementView, Uuid } from "@/lib/types";
import { useSelection } from "@/state/selection";

/** The four states a live thread can be in, in the order a reader looks for them. */
const REPORTABLE_STATES = ["RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING"] as const;

/** The metric keys this screen reads, spelled once. A typo in one of them is then a compile error. */
const K = {
  threads: "threaddump.threads.count",
  javaThreads: "threaddump.threads.java.count",
  ownThreads: "threaddump.threads.nonjava.count",
  unknownState: "threaddump.threads.unknownState.count",
  state: "threaddump.threads.state.count",
  deadlocks: "threaddump.threads.deadlock.count",
  deadlocked: "threaddump.threads.deadlocked.count",
  contended: "threaddump.monitors.contended.count",
  worstMonitor: "threaddump.monitors.waiters.max",
  monitorTable: "threaddump.monitors.waiters",
  observed: "threaddump.monitors.waiters.observed",
  reported: "threaddump.monitors.waiters.reported",
  deepestStack: "threaddump.threads.stack.depth.max",
  mostHeld: "threaddump.monitors.held.max",
} as const;

/**
 * The Concurrency Inspector: what one thread dump said about one instant of one process.
 *
 * The screen is deliberately narrow. It reads the newest thread dump that has actually been
 * analysed, and shows what that dump reported: how many threads were in each state, which monitors
 * had threads waiting to enter them, and which deadlock cycles the JVM's own detector printed. It
 * does not profile, it does not sample over time, and it does not decide that anything is wrong.
 *
 * Three refusals shape the copy:
 *
 * <ul>
 *   <li>A dump is one instant. No number here is a rate or a trend, and a busy thread that happened
 *       to be between calls is not a finding.</li>
 *   <li>Deadlocks are the JVM's claim, not RepoPilot's. When the dump carries no deadlock section
 *       the page says the detector printed nothing — not that the process has no lock problem.</li>
 *   <li>No threshold is applied and no severity is assigned, because a queue of waiters is a fact
 *       about a workload and RepoPilot will not grade it without a policy it can state.</li>
 * </ul>
 */
export function ConcurrencyInspectorPage() {
  const { repositoryId } = useSelection();
  const navigate = useNavigate();
  const [chosenRunId, setChosenRunId] = useState<Uuid | null>(null);

  const artifacts = useRuntimeArtifacts(repositoryId);

  // Dumps that have been read, newest first. A dump that was uploaded and never analysed is not
  // evidence about anything yet, so it is not offered here; it is listed in the Performance Lab.
  const analysed = useMemo(() => {
    const items = artifacts.data?.items ?? [];
    return items
      .filter((artifact) => artifact.artifactType === "THREAD_DUMP" && hasValue(artifact.analysisRunId))
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
          title="Concurrency Inspector"
          description="Thread states, monitor contention and the deadlocks a thread dump reported."
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
          title="Concurrency Inspector"
          description="Thread states, monitor contention and the deadlocks a thread dump reported."
        />
        <Card title="No thread dump has been analysed">
          <EmptyState
            icon="threads"
            title="Nothing to inspect yet"
            message="A thread dump — the plain-text output of a running JVM — is uploaded in the Performance Lab and analysed there. This screen then reads the result."
            action={
              <div className="toolbar" style={{ justifyContent: "center" }}>
                <button
                  type="button"
                  className="btn btn-sm btn-primary"
                  onClick={() => navigate("/performance")}
                >
                  Upload a thread dump
                </button>
              </div>
            }
          />
          <HowToTakeADump />
        </Card>
      </>
    );
  }

  const threads = valueOf(byKey.get(K.threads));
  const javaThreads = valueOf(byKey.get(K.javaThreads));
  const ownThreads = byKey.get(K.ownThreads);
  const unknownState = byKey.get(K.unknownState);
  const deadlocks = byKey.get(K.deadlocks);
  const deadlocked = byKey.get(K.deadlocked);
  const contended = byKey.get(K.contended);
  const worstMonitor = byKey.get(K.worstMonitor);
  const deepestStack = byKey.get(K.deepestStack);
  const mostHeld = byKey.get(K.mostHeld);
  const observed = byKey.get(K.observed);
  const reported = byKey.get(K.reported);

  const stateRows = REPORTABLE_STATES.map((state) => ({
    state,
    row: rows.find((row) => row.metricKey === K.state && row.scope === state),
  }));

  const waiters = rows
    .filter((row) => row.metricKey === K.monitorTable)
    .slice()
    .sort((left, right) => right.value - left.value);

  const cycles = cyclesOf(deadlocks);
  const dumpProvenance = summary.data?.provenanceJson?.dump;

  return (
    <>
      <PageHeader
        title="Concurrency Inspector"
        description="What one thread dump said about one instant of this process. No sampling, no thresholds, no severity."
      />

      <Card
        title="The dump this report describes"
        actions={
          analysed.length > 1 ? (
            <select
              className="input"
              style={{ width: "auto" }}
              value={selected.id}
              aria-label="Thread dump"
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
            label="Threads in the dump"
            value={formatNumber(threads)}
            foot="One block per thread, as the JVM printed it"
          />
          <Metric
            label="With a Java state"
            value={formatNumber(javaThreads)}
            foot="Blocks carrying java.lang.Thread.State"
          />
          <Metric
            label="The JVM's own threads"
            value={ownThreads === undefined ? "—" : formatNumber(valueOf(ownThreads))}
            foot="VM, GC and compiler threads, which state no Java state"
          />
          <Metric
            label="Monitors contended"
            value={contended === undefined ? "—" : formatNumber(valueOf(contended))}
            foot="At least one thread waiting to enter"
          />
        </div>

        <dl className="rp-detail" style={{ marginTop: "var(--space-4)" }}>
          <dt>File</dt>
          <dd>
            <span className="rp-mono">{selected.originalFilename}</span>{" "}
            <span className="rp-faint">
              {formatNumber(selected.sizeBytes)} B · {selected.contentHash.slice(0, 12)}…
            </span>
          </dd>
          <dt>Analysed</dt>
          <dd>{formatInstant(summary.data?.startedAt)}</dd>
          {hasValue(dumpProvenance?.takenAt) ? (
            <>
              <dt>Dump taken</dt>
              <dd>
                {dumpProvenance.takenAt}{" "}
                <span className="rp-faint">
                  (the JVM's local time; the file records no zone, so it is not converted)
                </span>
              </dd>
            </>
          ) : null}
          {hasValue(summary.data?.provenanceJson?.environment?.jvmVersion) ? (
            <>
              <dt>VM</dt>
              <dd className="rp-mono">{summary.data?.provenanceJson?.environment?.jvmVersion}</dd>
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
        <Card title="Thread states">
          <EmptyState
            icon="info"
            title="This run recorded no measurements"
            message="A run that read a dump and produced nothing is not the same as no run at all, and the run header above says which happened."
          />
        </Card>
      ) : (
        <>
          <div style={{ marginTop: "var(--space-5)" }}>
            <Card
              title="Thread states at the instant of the dump"
              actions={
                unknownState === undefined ? undefined : (
                  <Badge tone="warning">{formatNumber(valueOf(unknownState))} unknown state</Badge>
                )
              }
            >
              <table className="data">
                <thead>
                  <tr>
                    <th>State</th>
                    <th className="table-num">Threads</th>
                    <th>Share of Java threads</th>
                    <th>Detail the dump gave</th>
                  </tr>
                </thead>
                <tbody>
                  {stateRows.map(({ state, row }) => (
                    <tr key={state}>
                      <td>
                        <span className="rp-mono">{state}</span>
                      </td>
                      <td className="table-num">{formatNumber(valueOf(row))}</td>
                      <td>
                        <div className="toolbar" style={{ gap: "var(--space-2)" }}>
                          <div style={{ flex: 1, minWidth: 80 }}>
                            <ProgressBar
                              value={javaThreads === 0 ? 0 : (valueOf(row) / javaThreads) * 100}
                            />
                          </div>
                          <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                            {javaThreads === 0 ? "—" : formatPercent(valueOf(row) / javaThreads, 1)}
                          </span>
                        </div>
                      </td>
                      <td>
                        <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                          {detailOf(row)}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <p className="rp-faint" style={{ marginTop: "var(--space-3)", fontSize: "var(--text-xs)" }}>
                {caveatOf(stateRows.find((entry) => entry.state === "RUNNABLE")?.row) ??
                  "All four states are listed, including the ones with no threads in them, so a quiet process is distinguishable from a dump this build could not read."}
              </p>
            </Card>
          </div>

          <div style={{ marginTop: "var(--space-5)" }}>
            <Card
              title="Deadlock"
              actions={
                <Badge tone={valueOf(deadlocks) > 0 ? "danger" : "success"}>
                  {valueOf(deadlocks) > 0
                    ? `${formatNumber(valueOf(deadlocks))} cycle(s) reported by the JVM`
                    : "none reported by the JVM"}
                </Badge>
              }
            >
              {valueOf(deadlocks) === 0 ? (
                <Banner tone="success" title="The dump's own detector reported no deadlock cycle">
                  This is the JVM's statement about the instant it was asked, not a proof that the
                  process has no lock problem. A thread waiting on a monitor that will be released is
                  not a deadlock, and a cycle that forms a second later is not in this dump. RepoPilot
                  does not run its own cycle search, so it reports exactly what the section said.
                </Banner>
              ) : (
                <>
                  <Banner tone="danger" title="The JVM's deadlock detector reported a cycle">
                    These are the monitors and threads the dump's own detector named. RepoPilot repeated
                    them; it did not search for the cycle itself.
                  </Banner>
                  {cycles.map((cycle, index) => (
                    <Fragment key={index}>
                      <h4 style={{ marginTop: "var(--space-4)" }}>
                        Cycle {index + 1} · {cycle.edges?.length ?? 0} edge(s) ·{" "}
                        {(cycle.threads ?? []).length} thread(s)
                      </h4>
                      <table className="data">
                        <thead>
                          <tr>
                            <th>Thread</th>
                            <th>Waits to enter</th>
                            <th>Which is held by</th>
                          </tr>
                        </thead>
                        <tbody>
                          {(cycle.edges ?? []).map((edge, edgeIndex) => (
                            <tr key={`${index}-${edgeIndex}`}>
                              <td className="rp-mono">{edge.thread ?? "—"}</td>
                              <td className="rp-mono">{edge.waitsToEnter ?? "a monitor"}</td>
                              <td className="rp-mono">{edge.heldBy ?? "—"}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </Fragment>
                  ))}
                </>
              )}

              {deadlocked !== undefined ? (
                <p className="rp-faint" style={{ marginTop: "var(--space-3)", fontSize: "var(--text-xs)" }}>
                  {formatNumber(valueOf(deadlocked))} thread(s) were named by the deadlock section:{" "}
                  <span className="rp-mono">
                    {namesOf(deadlocked).join(", ")}
                  </span>
                </p>
              ) : null}
            </Card>
          </div>

          <div style={{ marginTop: "var(--space-5)" }}>
            <Card
              title="Monitor contention"
              actions={
                worstMonitor === undefined ? undefined : (
                  <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                    Most waiters on one monitor: {formatNumber(valueOf(worstMonitor))}
                  </span>
                )
              }
            >
              {waiters.length === 0 ? (
                <EmptyState
                  icon="check"
                  title="No thread was waiting to enter a monitor"
                  message="At this instant no thread in the dump was blocked on a monitor. That is a statement about one instant: contention that lasted a millisecond either side of this dump is not in it."
                />
              ) : (
                <>
                  <table className="data">
                    <thead>
                      <tr>
                        <th>Monitor</th>
                        <th className="table-num">Threads waiting</th>
                        <th className="table-num">Share of waits</th>
                      </tr>
                    </thead>
                    <tbody>
                      {waiters.map((row) => (
                        <tr key={row.id}>
                          <td>
                            <div
                              title={hasValue(row.scope) ? row.scope : undefined}
                              className="rp-mono"
                              style={{
                                maxWidth: 320,
                                overflow: "hidden",
                                textOverflow: "ellipsis",
                                whiteSpace: "nowrap",
                              }}
                            >
                              {hasValue(row.scope) ? row.scope : "an untyped monitor"}
                            </div>
                          </td>
                          <td className="table-num">{formatNumber(row.value)}</td>
                          <td className="table-num">
                            {formatPercent(shareOf(row), 1)}
                            <div className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                              of {formatNumber(valueOf(observed))} waiting
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <p
                    className="rp-faint"
                    style={{ marginTop: "var(--space-3)", fontSize: "var(--text-xs)" }}
                  >
                    A holder is named where the dump reported one (
                    <span className="rp-mono">- locked</span>); "no holder shown" means the dump never
                    said who holds it, which is not the same as nobody holding it.{" "}
                    {reported !== undefined && observed !== undefined && valueOf(reported) < valueOf(observed)
                      ? `The table lists the first ${formatNumber(valueOf(reported))} of ${formatNumber(valueOf(observed))} contended monitors.`
                      : null}
                  </p>
                </>
              )}
            </Card>
          </div>

          <div style={{ marginTop: "var(--space-5)" }}>
            <Card title="Shape of the dump">
              <div className="grid grid-4">
                <Metric
                  label="Deepest stack"
                  value={deepestStack === undefined ? "—" : formatNumber(valueOf(deepestStack))}
                  foot={
                    hasValue(deepestStack?.scope)
                      ? `${deepestStack.scope} · frames, not a finding`
                      : "Frames in the deepest thread block"
                  }
                />
                <Metric
                  label="Most monitors held by one thread"
                  value={mostHeld === undefined ? "—" : formatNumber(valueOf(mostHeld))}
                  foot={
                    hasValue(mostHeld?.scope)
                      ? `${mostHeld.scope} · holds the most at this instant`
                      : "No thread reported holding a monitor"
                  }
                />
                <Metric
                  label="Contended monitors reported"
                  value={formatNumber(valueOf(observed))}
                  foot="Counted once per monitor"
                />
                <Metric
                  label="Threads waiting to enter a monitor"
                  value={formatNumber(totalWaiters(waiters))}
                  foot="Sum of the waits above"
                />
              </div>
            </Card>
          </div>
        </>
      )}

      <div style={{ marginTop: "var(--space-5)" }}>
        <Banner tone="info" title="What this report is, and what it is not">
          Every number here describes one instant of one process on this machine. A thread dump is a
          snapshot, not a profile: nothing on this page is a rate, a duration or a trend, no threshold
          was applied and no severity was assigned. Whether three threads waiting on one monitor
          matters is a judgement about your workload, and RepoPilot makes those only where it can
          state the policy behind them.
        </Banner>
      </div>
    </>
  );
}

/**
 * How to produce a dump this reader can use.
 *
 * Shown on the empty state, because the difference between the two forms is invisible until the
 * upload is refused, and a JVM's own `Thread.dump_to_file` writes the form that cannot be read.
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
        {`jcmd <pid> Thread.print > thread-dump.txt
jstack <pid> > thread-dump.txt
kill -3 <pid>        # writes the same text to the process's own stdout`}
      </pre>
      <Banner tone="warning" title="jcmd Thread.dump_to_file is refused, in both of its formats">
        <span className="rp-mono">Thread.dump_to_file</span> writes a stack per thread and no thread
        state, no monitor ownership and no deadlock section — so a report built from it would be a page
        of zeros that looks exactly like a healthy JVM. The upload is refused by name with the command
        that produces a readable dump instead.
      </Banner>
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

function attributeOf(row: RuntimeMeasurementView | undefined, key: string): unknown {
  return attributesOf(row)[key];
}

function detailOf(row: RuntimeMeasurementView | undefined): string {
  const details = attributeOf(row, "details");
  if (typeof details !== "object" || details === null || Array.isArray(details)) {
    return "—";
  }
  const entries = Object.entries(details as Record<string, unknown>);
  if (entries.length === 0) {
    return "—";
  }
  return entries.map(([key, value]) => `${value} ${key}`).join(", ");
}

function caveatOf(row: RuntimeMeasurementView | undefined): string | null {
  const caveat = attributeOf(row, "caveat");
  return typeof caveat === "string" && caveat.trim() !== "" ? caveat : null;
}

/** A contended monitor's share of all waits, as the backend computed it. */
function shareOf(row: RuntimeMeasurementView): number {
  const share = attributeOf(row, "share");
  return typeof share === "number" ? share : 0;
}

/** The deadlock cycles the run stored, as the rows the backend named. */
interface Cycle {
  threads?: string[];
  edges?: Array<{ thread?: string; waitsToEnter?: string; heldBy?: string }>;
}

function cyclesOf(row: RuntimeMeasurementView | undefined): Cycle[] {
  const cycles = attributeOf(row, "cycles");
  return Array.isArray(cycles) ? (cycles as Cycle[]) : [];
}

function namesOf(row: RuntimeMeasurementView | undefined): string[] {
  const threads = attributeOf(row, "threads");
  return Array.isArray(threads) ? threads.map((name) => String(name)) : [];
}

function totalWaiters(waiters: RuntimeMeasurementView[]): number {
  return waiters.reduce((sum, row) => sum + row.value, 0);
}
