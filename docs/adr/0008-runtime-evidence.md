# ADR 0008 — Runtime evidence: measurements are not findings

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** RepoPilot maintainers

## Context

Static analysis produces *findings*: a claim about code, carrying a rule id, a severity and a
line to cite. Runtime analysis produces something structurally different — a *quantity observed
while a process was running*. A GC pause figure is a fact about one JVM on one machine for one
recording. It has no line to cite, and it has no severity that does not come from a policy.

The tempting shortcut is to store measurements in the `finding` table: it already has prose,
counts, a repository and a run, and the UI already renders it. That shortcut is wrong in four
ways that only show up later:

1. **A finding's severity is a judgement; a measurement's is a policy.** `finding.severity` is
   non-null and ordered. To put "max GC pause = 128 ms" in that column, something has to decide
   that 128 ms is `MEDIUM` — and nothing in RepoPilot has that authority. Inventing a threshold
   and colouring the result is how a measurement becomes an opinion while still looking like a
   measurement.
2. **A finding is about source; a measurement is about a run.** A measurement is only
   interpretable with its sample count, its reduction and the recording it came from. Three of
   those four do not fit a finding row, and a number that has been separated from them is a
   number nobody can judge.
3. **A finding's identity is a citation; a measurement's is not.** Findings deduplicate on
   `analyzerId|ruleId|path|lines`. Two measurements of the same metric are two observations, not
   a duplicate, and a fingerprint would merge exactly the data the operator is comparing.
4. **Re-analysing is not re-reporting.** Measurements are read from a file that does not change.
   A second run of the same recording should reproduce the same numbers — which is a property
   worth being able to state, and impossible to state if the rows are keyed as findings.

There is also a platform trap that this ADR has to record, because the wrong answer is
plausible-looking: in JFR metadata, timestamps and timespans are annotated
`@Timestamp("TICKS")` / `@Timespan("TICKS")`. A raw `getValue(field)` on a duration field
returns a **tick count**, not a duration. On JDK 21.0.5 a 50 ms sleep reports `511264` that way.
Only `getDuration()` / `getDuration(field)` converts through the file's own tick resolution.

## Decision

**Runtime measurements are first-class rows in their own table, tied to the run and the
artefact that produced them, and RepoPilot applies no thresholds to them.**

1. **A measurement is its own record.** `runtime_measurement` (V4) carries `metric_key`,
   `metric_kind` (`COUNTER`/`DURATION`/`GAUGE`/`SIZE`), `aggregation`
   (`COUNT`/`SUM`/`MEAN`/`MAX`), `value`, `unit`, `sample_count`, `scope` and
   `attributes_json`, plus the `analysis_run_id` and `artifact_id` it belongs to. `analysis_run`
   gains `measurement_count`, separate from `finding_count`, so a runtime run cannot read as
   "0 findings, all clear".

2. **No thresholds, no severity, no percentiles.** Severity is not a column on a measurement,
   and the run summary says in prose that no threshold was applied and no severity assigned.
   There is deliberately no p95/p99: a percentile cannot be recovered from a stored maximum, so
   the schema does not offer the claim. `MAX`, `MEAN`, `SUM` and `COUNT` are what the reader
   actually observed.

3. **Durations come from `getDuration`, never from a raw field.** `JfrReader` is the only class
   that touches `jdk.jfr`, and the two helpers that convert timespans carry that reasoning in a
   comment, because a tick count is a number that would look entirely reasonable on screen.

4. **A sampling weight is reported as a weight.** `ObjectAllocationSample.weight` is annotated
   `@DataAmount("BYTES")` but is a *sampling* weight: a run that allocated 200 MiB produced 60
   samples summing 75.8 MiB. RepoPilot stores it with unit `weight` and the UI prints the word
   `weight` next to it. Presenting it as bytes would understate allocation by more than half.

5. **Nothing credential-bearing is read from a recording.** Command-line arguments, system
   properties and environment variables are present in a JFR file and are not read. Per-event
   settings are also not read: on JDK 21 `ActiveSetting` cannot be attributed to the event type
   it configured, so a sampling period or threshold would have to be guessed — and a guessed
   sampling parameter makes every count that follows it a lie. Threshold-gated channels instead
   carry an explicit caveat, and the caveat travels with the number.

6. **Every measurement keeps its provenance.** The artefact's `metadata_json` holds the JVM,
   OS, CPU, memory and the recording's own window; the run summary echoes it. A measurement
   whose machine is unknown is not comparable to anything.

7. **Artefacts are content-addressed, outside every repository.** Bytes land at
   `<data-dir>/artifacts/<repoId>/<hash[0..2]>/<sha256>.<ext>`. The client's filename is a
   display label and nothing else: a file uploaded as `../../etc/passwd.jfr` is stored under
   its hash and listed as `passwd.jfr`. `resolve()` re-checks containment lexically *and* after
   `toRealPath()`, so neither `..` nor a symlink escapes.

8. **Upload and analysis are separate requests.** A recording is evidence that outlives the run
   that read it, so an operator can upload a set, analyse later, and re-analyse after a reader
   fix. Collapsing the two would make "the upload failed" and "the analysis failed" the same
   answer.

9. **An artefact this build cannot read is stored and refused with a reason.** Heap dumps,
   thread dumps and benchmark results are accepted, stored and reported as unsupported by name.
   Refusing the upload would discard evidence the operator already collected; accepting and
   pretending to analyse it would be worse. A request that cannot start fails *before* any run
   row exists, so there is never a run that failed for a reason the API could have stated.

10. **Uploads are streamed, not buffered.** The multipart part is written to a scratch file,
    hashed on a second pass, and moved into place with `ATOMIC_MOVE`. Peak memory is one hash
    buffer regardless of file size — the artefacts this endpoint exists for are larger than the
    heap. The cost is disk: peak usage is about twice the artefact size, transiently. The limit
    is 2 GiB per artefact, enforced after the upload lands, with nothing partial kept.

## Consequences

- "How slow is this?" and "is this bad?" are separate questions, and only the first is answered
  by this system. That is the point: the UI can show a measurement with its sample count and
  its caveats, and cannot accidentally render a verdict nobody asked for.
- A measurement is reproducible: same file, same reader version, same numbers. The reader
  version is recorded on the run, so a change in a number can be attributed to a change in the
  reader.
- The reader is testable without a database: `JfrMeasurementMapper` is a pure function from a
  parsed report to drafts, and the unit tests assert exact values against a recording generated
  in the test.
- Adding a threshold policy later (a `repopilot.policy.*` file, say) does not change this
  schema: it adds a derivation on top of measurements that remain unopinionated.
- **Ingest is bounded, twice, and a refusal leaves nothing behind.** The framework's per-part disk
  cap (`spring.webflux.multipart.max-disk-usage-per-part`, 2 GiB by default) bounds what a stream can
  write while it is still arriving; `repopilot.analysis.max-artifact-size-bytes` (1 GiB) is the
  published limit, checked on the landed file so the refusal can name the exact size and the exact
  limit. Both answer `413 LIMIT_EXCEEDED`, and both delete the scratch file. Two numbers because they
  do two jobs: only the framework can stop a stream early, and only RepoPilot can explain itself.
- **A deleted repository takes its bytes with it.** Removing the rows and leaving a heap dump — which
  is process memory — on disk would make "deleted" a claim about the database rather than about the
  machine. The filesystem work runs after the transaction commits (ADR 0006), and a startup sweep
  removes artefact directories whose repository is not in the database, which covers a deletion
  interrupted between the two.
- **Known platform limitation.** The JDK's `RecordingFile` keeps its channel open when it fails
  to parse a file, and there is no way to close it from outside; the file stays locked until the
  next collection releases it. `JfrReader.detectKind` checks the file header before any
  `jdk.jfr` object is created, which covers the realistic wrong-file cases (a text file, a
  gzip, an empty file) without touching the JDK API. A structurally plausible but corrupt
  recording still reaches `RecordingFile` and still holds a descriptor until GC; this is
  recorded as a limitation rather than papered over with a `System.gc()` in production code.
- Memory and concurrency analysis are not implemented, and they inherit this contract: a heap
  dump's retained sizes are measurements with a provenance, not findings with a severity.
