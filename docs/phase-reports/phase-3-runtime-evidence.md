# Phase report — runtime evidence (JFR), measurements, and the honesty boundary

**Date:** 2026-10-18
**Scope of this increment:** the second analysis engine, and the first one that measures a
process instead of reading source. RepoPilot can now accept a JFR recording, store it by content
hash outside every repository, analyse it as a durable job with the JDK's own Flight Recorder
API, and report what it measured — with the unit, the reduction, the sample count and the
recording's provenance attached to every number. Nothing is uploaded anywhere: the reader runs in
process, on this machine, with no agent and no API key.

The work is a deliberate study in what a system should refuse to claim. A recording yields
numbers; it does not yield verdicts. This increment implements the numbers and refuses the
verdicts, and most of the code below exists to keep that boundary from eroding.

---

## 1. What works now

| Capability | State | Evidence |
| --- | --- | --- |
| JFR ingestion, stored by content hash | Working | A 303 679-byte live recording was uploaded and listed as `3d4487e9d9fe0ad9…`; a re-upload of the same bytes returned the first artefact's id |
| Header validation before any `jdk.jfr` object exists | Working | A text file named `.jfr` is refused `422 UNSUPPORTED_INPUT` with the message naming the `FLR` header |
| Reading a recording into measurements | Working | Live run: **101 measurements** from a 3.3 s recording — 5 572 events, 183 event types, 148 execution samples, 17 native samples, 132 allocation samples, 29 GC pauses totalling 78 ms |
| Measurement as a first-class record | Working | `runtime_measurement` rows, each carrying unit, kind, reduction, sample count, scope, run id and artefact id; asserted over HTTP in `RuntimeAnalysisIntegrationTest` |
| Provenance stored with the artefact | Working | The live run's `provenanceJson` names the JVM (`Java HotSpot(TM) 64-Bit Server VM 21.0.5+9-LTS-239`), the OS, the CPU and the memory, and records `thresholdsApplied=false` |
| Reproducibility | Working | Re-analysing the same recording produced the same measurement count; the smoke test asserts it |
| Content-addressed, traversal-proof storage | Working | An upload named `../../escape.jfr` is stored under its hash and listed as `smoke-recording.jfr`; `ArtifactStoreTest.refusesToEscape` covers the lexical and the `toRealPath` check |
| Streaming upload, bounded memory | Working | The multipart part is written to a scratch file, hashed on a second pass and moved with `ATOMIC_MOVE`; peak memory is one hash buffer |
| Unsupported artefact types | Working | A heap dump uploads, is stored, is reported `supported=false` with a named reason, and is flagged `containsProcessMemory=true` |
| Performance Lab UI | Working | `/performance` reads the run header, provenance, measurements with filters by family and kind, and the artefacts; screenshots in `docs/screenshots/` |
| Durable job, none of the run work in a transaction | Working | `JFR_ANALYSIS` is queued, claimed, run and completed through the same dispatcher as indexing and static analysis |

**Verification.** Backend `.\mvnw.cmd -B package` — **68 tests, 0 failures, BUILD SUCCESS**.
Frontend `npm run build` — typecheck clean, 115 modules, 316.44 kB JS (95.50 kB gzip).
`scripts/smoke-test.ps1` against the live server — **124 checks, 0 failures** (61 at Phase 2).

---

## 2. What was built

**Backend — `dev.repopilot.runtime` (19 classes, ~2 900 lines)**

| File | Lines | Role |
| --- | --- | --- |
| `jfr/JfrReader.java` | 670 | The only class that touches `jdk.jfr`. Parses a recording into a `JfrRecordingReport`; header detection; tick handling |
| `jfr/JfrMeasurementMapper.java` | 377 | Pure function from a report to `MeasurementDraft`s. Every honesty caveat lives here |
| `jfr/JfrRecordingReport.java` | 155 | The parsed shape: activity statistics, gauges, histograms, exceptions, allocation, provenance |
| `JfrAnalysisJobHandler.java` | 359 | The job: run creation, measurement persistence, summary, provenance JSON, failure semantics |
| `ArtifactStore.java` | 157 | Content-addressed storage: `stage`, `adopt`, `resolve`, containment re-check, scratch cleanup |
| `RuntimeService.java` | 236 | Ingestion: type resolution, filename sanitising, size limit, analysis submission |
| `RuntimeArtifact.java`, `RuntimeMeasurement.java` | 121, 128 | Entities; measurement column mapped as `metric_value` (`VALUE` is a reserved word) |
| `query/RuntimeQueryService.java` + views | 205 + 161 | The read path: summary, measurements with filters, paged |
| `api/v1/RuntimeController.java` | 157 | `POST/GET …/runtime/artifacts`, `…/{id}`, `…/{id}/analyze`, `…/measurements`, `…/summary` |

**Database — `V4__runtime_measurement.sql`**: `runtime_measurement`
(`metric_key`, `metric_kind`, `aggregation`, `metric_value`, `unit`, `sample_count`, `scope`,
`attributes_json`, `analysis_run_id`, `artifact_id`) and `analysis_run.measurement_count`. The
migration applies to the live database (3 → 4) with no data loss.

**Frontend**: `pages/PerformanceLabPage.tsx` (new), plus runtime types in `lib/types.ts`,
measurement formatting in `lib/format.ts` (`formatMetricValue`, `aggregationLabel`,
`formatSampleScope`, `metricFamily`, `metricLeaf`), multipart upload support and six runtime
calls in `lib/api.ts`, and five hooks in `hooks/queries.ts`. The `performance` destination is now
`available: true` and the placeholder plan was removed.

**Tests — 5 classes, ~1 366 lines**: `JfrMeasurementMapperTest` (13), `RuntimeAnalysisIntegrationTest`
(2, end to end through the real dispatcher), `ArtifactStoreTest` (3), `JvmLabelTest` (4),
`JfrReaderTest` (2). The reader tests generate their own recording rather than checking a binary
fixture in, so the assertions cannot drift away from the JDK in use.

---

## 3. What the system refuses to claim

This is the substance of the increment. Each refusal is enforced in code, not in a comment.

**No thresholds, and therefore no severity.** A measurement row has no severity column. The run
summary ends with "no threshold applied, no severity assigned", and the provenance records
`thresholdsApplied: false`. RepoPilot cannot say whether 128 ms of GC pause is bad, because that
depends on the application's contract and on a policy nobody has supplied. The UI repeats the
refusal on the page rather than only in the docs.

**No percentiles.** The reductions are `COUNT`, `SUM`, `MEAN` and `MAX`. There is no p95 or p99
because a percentile cannot be recovered from a stored maximum; offering the field would invite a
claim the data cannot support.

**A tick count is not a duration.** JFR annotates timespans `@Timespan("TICKS")`, and a raw
`getValue(field)` on such a field returns a tick count — on JDK 21.0.5 a 50 ms sleep reports as
`511264`. Only `getDuration()` converts through the file's own tick resolution. The reader uses
`getDuration` exclusively, and the live run's `jfr.sleep.total` is in milliseconds.

**A sampling weight is not a byte count.** `ObjectAllocationSample.weight` is annotated
`@DataAmount("BYTES")` but is a sampling weight. The live recording allocated 200 MiB and produced
60 samples summing 75.8 MiB; presenting the sum as bytes would understate allocation by more than
half. It is stored with unit `weight` and rendered as a weight.

**Nothing credential-bearing is read.** Command-line arguments, system properties and environment
variables are present in the file and are not read. Per-event settings are not read either: on
JDK 21 `ActiveSetting` cannot be attributed to the event type it configured, so a sampling period
or a threshold would have to be guessed — and a guessed sampling parameter makes every count that
follows it a lie. The smoke test asserts that no class-path setting reaches the provenance.

**A threshold-gated channel says so on the row.** JFR emits thread park, monitor, sleep and I/O
events only above a recording threshold, so the count is of events that exceeded it, not of every
occurrence. Those measurements carry `thresholdGated: true` and an explanation in
`attributes_json`, and the UI renders it when the row is expanded.

**An unreadable artefact is stored, not discarded, and never silently "analysed".** A heap dump
is accepted, stored and reported as unsupported with a reason; asking to analyse it is refused
`422` *before* a run row exists, so there is never a run that failed for a reason the API could
have stated.

---

## 4. Defects found and fixed during verification

1. **The run summary named the JVM twice.** `jdk.JVMInformation.jvmVersion` is the full build
   string and already begins with the VM name, so appending both produced "Java HotSpot(TM)
   64-Bit Server VM Java HotSpot(TM) 64-Bit Server VM (21.0.5+9-LTS-239) for windows-amd64 JRE…".
   Fixed in `JfrAnalysisJobHandler.jvmLabel`, which keeps the name once and reduces the version to
   its build token — chosen by shape, not position, because the name itself contains a parenthesis
   (`(TM)`) and the naive "first parenthesis" implementation returns `TM`. Covered by
   `JvmLabelTest`.
2. **`VALUE` is a reserved word.** The first draft of V4 created a `value` column; H2 rejected the
   migration. The column is `metric_value` here and in the entity. V4 had never been applied
   anywhere, so it was corrected in place rather than superseded.
3. **`max of 0 samples`.** The smoke test failed on a measurement with `sampleCount = 0` — the
   recording's own window, which by design reduces no samples (documented on `MeasurementDraft`).
   The data was right and the UI was wrong: `formatSampleScope` now says "one derived value, not a
   reduction over samples", and the samples column renders an em dash rather than `0`. The smoke
   assertion was rewritten to encode the real invariant — only a figure that reduces no samples
   may report a zero sample count.
4. **`$uri?artifactType=` is a PowerShell variable.** The smoke test's first run died on
   `$uri?artifactType` being parsed as a variable name; fixed with `${uri}`. The script also needed
   `Add-Type -AssemblyName System.Net.Http`, and PowerShell 5.1 silently drains the error response
   stream, so refusal bodies are read from `$_.ErrorDetails.Message` instead.

---

## 5. Known limitations, stated rather than papered over

- **A failed `RecordingFile` construction leaks a file handle until the next collection.** There is
  no way to close it from outside the JDK, so on Windows the file stays locked. `JfrReader.detectKind`
  checks the header before any `jdk.jfr` object is created, which covers the realistic wrong-file
  cases (text, gzip, empty) without touching the API. A structurally plausible but corrupt recording
  still reaches the JDK; that is recorded as a limitation rather than papered over with a
  `System.gc()` in production code. Established by `Probe10` in the probe corpus.
- **Peak disk during upload is about twice the artefact size**, transiently: the part lands in a
  scratch file and is copied to its content-addressed home. Peak *memory* is one hash buffer.
  The limit is 2 GiB per artefact.
- **No streamed event timeline.** The reader aggregates; it does not retain per-event rows, so
  "what happened at t=1.2 s" is not answerable. That is a different feature with a different cost.
- **Only `JFR_RECORDING` is readable.** Heap dumps, thread dumps and benchmark results are stored
  and refused by name. `HEAP_ANALYSIS`, `THREAD_DUMP_ANALYSIS` and `BENCHMARK_IMPORT` have no
  handler, and `JobRegistry` rejects them rather than accepting work it cannot do.
- **Not verified on PostgreSQL or in Docker.** Docker is not available in this environment, so
  `docker compose up --build` remains unverified, and the migration has only been exercised in
  H2's PostgreSQL-compatibility mode.
- **`StaticAnalysisIntegrationTest` took 409 s in the final full run** against ~35 s previously,
  with no change to the code under test. Recorded as an unexplained timing outlier on this machine,
  not as a passing test that was skipped: the suite ran to completion with 68/68 green.

---

## 6. Files added or changed

**Added (backend):** `runtime/` — 19 main classes as listed in §2, plus
`api/v1/RuntimeController.java`, `db/migration/V4__runtime_measurement.sql`, and 5 test classes.
**Changed (backend):** `analysis/AnalysisRun.java` (artefact columns, `measurement_count`, a
second `complete(...)` overload), `jobs/JobContext.java` (`parametersJson()`),
`jobs/JobDispatcher.java`, `resources/application.yml` (multipart limits, streamed to disk).
**Added (frontend):** `pages/PerformanceLabPage.tsx`.
**Changed (frontend):** `lib/types.ts`, `lib/api.ts`, `lib/format.ts`, `hooks/queries.ts`,
`components/AppShell.tsx`, `components/destinations.ts`, `pages/CapabilityPage.tsx`.
**Added (docs):** `docs/adr/0008-runtime-evidence.md`, this report,
`docs/screenshots/performance-lab-*.png`.
**Changed (docs):** `README.md`, `docs/architecture.md`, `docs/security.md`, `corpus/RESULTS.md`.
**Changed (scripts):** `scripts/smoke-test.ps1` — 63 new checks.

---

## 7. Next phase

1. Heap and thread dump analysis, on this same contract: retained sizes are measurements with a
   provenance, not findings with a severity. Heap dumps carry process memory, so the storage and
   redaction rules need to be stated before the reader is written.
2. Benchmark import: JMH results as measurements, with the machine and the JVM recorded alongside
   them, and the same refusal to rank two runs that were not comparable.
3. AI investigation over the evidence produced so far, with the backend — not the model —
   validating every cited file and line. Measurements join findings as citable evidence.
4. SpotBugs, gated on compiled classes actually being present rather than reporting
   `NOT_APPLICABLE`.
5. PostgreSQL and `docker compose up --build` verification, which needs an environment with Docker.
