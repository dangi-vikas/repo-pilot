# Phase report — benchmark results and artefact retention

- **Phase:** 6 of 8
- **Date:** 2026-10-20
- **Status:** complete; backend suite and live smoke test green
- **ADRs:** [0011 — benchmark evidence](../adr/0011-benchmark-evidence.md),
  [0012 — artefact retention](../adr/0012-artefact-retention.md),
  [0008 amendment](../adr/0008-runtime-evidence.md)

## 1. What works now

**Benchmark results are a first-class runtime artefact, and every figure in one is quoted.**

1. A JMH result file written with `-rf json` can be uploaded to a repository like any other runtime
   artefact: stored by content hash under `<data-dir>/artifacts/<repoId>/…`, deduplicated, bounded at
   64 MiB, listed with its type and its support status.
2. The type is inferred from `.json`, or stated explicitly; CSV output, the text/console table and a
   gzip file are refused **by name at upload**, each with `-rf json -rff results.json`.
3. A `BENCHMARK_IMPORT` job reads it and writes measurements. On the recorded two-fork fixture that is
   **277 rows from 14 entries**: 4 throughput, 4 average-time, 6 sample, two parameter values.
4. Every score, error, confidence bound and percentile is stored as `AS_REPORTED` with the harness's
   own unit (`ops/us`, `us/op`), the mode, the parameters and the harness's percentile level.
5. Where the file records per-iteration values, the stated score is checked against their mean and the
   row says so (`the stated score is the mean of the 6 per-iteration value(s) … across 2 fork(s)`).
   Where it records a histogram instead, the row says `not checkable`.
6. The JVM's `jvmArgs` are **counted and not stored**, and the path to the JVM binary is never read.
   Declared parameters are stored, because they identify the measurement.
7. The run card states the figures are the harness's, not this build's, and the provenance ends with
   **comparisons made: no**. There is no baseline, no delta, no threshold and no severity anywhere in
   the feature.
8. An array that is not a result file uploads (JSON has no header to check) and then **fails in the
   job**, naming the entry and the `benchmark` field it lacked.

**Artefact retention reclaims disk without losing evidence.**

9. `GET /repositories/{id}/runtime/retention` returns a plan computed against the current clock:
   stored/purged counts and bytes, the cutoff, and per candidate file its size, age and the number of
   measurements that would survive. It is the dry run; there is no dry-run job.
10. `POST …/retention/purge` queues an `ARTIFACT_RETENTION` job. It deletes **only the stored file**,
    marks the row `purged_at`, and keeps the artefact row, the analysis run and every measurement. The
    summary states both figures: files purged, bytes reclaimed, measurements kept.
11. A window of `0` means pruning is off — no candidate, no scheduled pass — and an explicit purge with
    no window is `400 VALIDATION_FAILED` rather than "everything".
12. Re-uploading the same bytes clears `purged_at`; analysing a purged artefact before that is `409`
    with a sentence saying the measurements are still here.
13. `RetentionScheduler` queues one pass per repository per day (hourly sweep, initial delay 120 s,
    idempotency key `retention:<repoId>:<yyyy-MM-dd>`), skips repositories with active work, and
    survives a repository that cannot be queued.
14. The Performance Lab marks purged artefacts (`file purged`, Analyse disabled with the reason), and
    Settings has a retention card: policy, cutoff, counts, a window you can preview, and a
    confirmation that repeats how many measurements are kept before anything is queued.

## 2. What was built

### Backend

| File | Role |
| --- | --- |
| `runtime/benchmark/BenchmarkFormat` | The upload-time check: first non-space byte is `[` over the first 4 KiB; names the three refused shapes and the command that produces a readable file |
| `runtime/benchmark/BenchmarkResultReport` | The parsed file: environment, per-entry metrics, percentiles, parameters, iteration times, argument count |
| `runtime/benchmark/BenchmarkReader` | Structural read of the JSON array; `MAX_ENTRIES = 10_000`; names the wrong entry; wraps a malformed document as an `IOException` with the truncation sentence |
| `runtime/benchmark/BenchmarkMeasurementMapper` | Pure function: `BenchmarkResultReport → List<MeasurementDraft>`, `AS_REPORTED` values, `COUNT` counts, score cross-check, file-level rows |
| `runtime/benchmark/BenchmarkImportJobHandler` | The `BENCHMARK_IMPORT` handler: provenance, `MAX_MEASUREMENTS = 20_000`, summary, progress per entry |
| `runtime/retention/ArtifactRetentionService` | `plan(repo[, days])`, `purge(repo, olderThanDays)`; refuses a window ≤ 0; deletes files then marks rows |
| `runtime/retention/ArtifactRetentionJobHandler` | The `ARTIFACT_RETENTION` handler: writes the window into the job's parameters, summarises what was removed and kept |
| `runtime/retention/RetentionScheduler` | Hourly queueing pass with a per-repository `try`/`catch` and a daily idempotency key |
| `db/migration/V5__artifact_retention.sql` | `runtime_artifact.purged_at` |
| `api/v1/RuntimeController` | `GET …/retention` (optional `?days=` preview), `POST …/retention/purge` |

Supporting changes: `RuntimeArtifact.purgedAt`, `ArtifactStore.discard`, purge queries on
`RuntimeArtifactRepository`, `JobService` counting active jobs **excluding** maintenance types,
`RepopilotProperties.Storage.retentionSweepIntervalMillis` / `retentionInitialDelayMillis`,
`Analysis.maxBenchmarkResultBytes`, `RuntimeArtifactView.purgedAt`.

### Frontend

| File | Change |
| --- | --- |
| `lib/types.ts` | `MeasurementAggregation` += `AS_REPORTED`; `RuntimeArtifactView.purgedAt`; `RuntimeProvenance.benchmark` + `produced.comparisonsMade` + `environment.jmhVersion`/`jdkVersion`; `RetentionPlan` / `RetentionCandidate` |
| `lib/format.ts` | `aggregationLabel("AS_REPORTED") → "as reported"` |
| `lib/api.ts` | `retentionPlan(repositoryId, days?)`, `purgeRetention(repositoryId, olderThanDays)` |
| `hooks/queries.ts` | `useRetentionPlan`, `usePurgeRetention` (invalidates artefacts, plan, summary, jobs, counters) |
| `pages/PerformanceLabPage.tsx` | `BENCHMARK_RESULT` namespace and job type; benchmark banner; provenance rows (modes, parameters, warm-up/measurement time, argument count and basis, entries without iterations/error estimate, comparisons made); `file purged` badge and disabled Analyse |
| `pages/SettingsPage.tsx` | `RetentionCard`: policy, cutoff, stored/purged counts and bytes, window preview, candidate table with measurements kept, confirmation modal |
| `components/destinations.ts` | Performance Lab and Settings descriptions name the new capability without changing the nine-destination navigation |

## 3. What the system refuses to claim

- **A benchmark score is not measured here.** It is the harness's arithmetic, on a machine RepoPilot
  has never seen, under a configuration the operator assembled. The row says `as reported` and the
  banner says the same in a sentence.
- **No comparison, no baseline, no regression.** One file states one run. `produced.comparisonsMade` is
  `false`, and the file-level row carries a note saying nothing became faster or slower than anything.
- **No threshold and no severity**, as in every runtime reader. `produced.thresholdsApplied` is `false`
  and the run summary states it.
- **No percentile is computed.** A level the file states is repeated under the harness's own number
  (`99.9999`); RepoPilot derives none from a stored maximum.
- **A missing error estimate is not zero.** `"scoreError": "NaN"` is a string, and no error row is
  written — `0.0` would be the strongest precision claim a measurement can make.
- **The score check corrects nothing.** It compares the file with itself and stores no second number.
- **Retention removes bytes, not evidence.** The artefact row, the run and every measurement stay; the
  plan says per file how many measurements survive, and the confirmation repeats the total.
- **Retention is not secure erasure.** Deleting a file does not overwrite the blocks it occupied; the
  security document says so instead of implying otherwise.
- **No per-artefact delete.** The only age-bounded path is the window, and the only path that removes
  everything is repository deletion.

## 4. Defects found and fixed during verification

1. **A truncated result file surfaced as an internal error.** `BenchmarkReader` let Jackson's
   `JsonProcessingException` escape, so a half-written upload became a `500` with a parser message in
   it rather than a named refusal. Caught by the reader test that feeds a document cut mid-object.
   Fixed: the exception is wrapped as an `IOException` whose message says the file "is not readable as
   JSON, or it ends in the middle of it", followed by "A truncated upload is not a shorter benchmark
   run, so nothing is stored from it."
2. **The retention scheduler could not queue anything after an analysis started.** The
   one-job-per-repository guard counted *every* active job, so a maintenance pass waited behind — or
   blocked — work the operator started. Fixed with
   `countByRepositoryIdAndStatusInAndJobTypeNotIn`, excluding `ARTIFACT_RETENTION` by type; a
   maintenance pass and an analysis now coexist, which is the point of the former.
3. **The retention plan could not be previewed when pruning was off.** With
   `retention-days = 0` the endpoint could only report "off", so the Settings card had no way to answer
   "what would a 30-day window remove?" — the exact question an operator asks before turning the policy
   on. Fixed by adding an optional `?days=` to the plan read, with `0` still meaning "off"; the purge
   already took an explicit `olderThanDays`, so the preview now uses the same number the purge will.
4. **The settings preview could show one window and act on another.** An input reading `0` (or anything
   not a whole number of days) left the button enabled while `days` silently fell back to the
   configured window. Fixed so an invalid box disables the purge and says why; the number in the
   confirmation is always the number the preview described.
5. **The smoke test compared a JSON number with a PowerShell literal and got `false` for equal
   values.** `ConvertFrom-Json` yields a `Decimal` for a value with this many digits, and PowerShell's
   decimal-versus-double comparison converts through 15 significant digits: `10.359395201829896` never
   equalled itself. Fixed by comparing `[double]` values with a `1e-9` tolerance — the assertion now
   states what it means ("is the number the file states") rather than relying on a representation.
6. **The smoke test counted rows on one page.** A measurement query is capped at 200 items while
   `totalItems` states 277, so "the API returns every row" failed on a correct server. Fixed to assert
   the envelope's total **and** that the page is a slice of it, which is the contract that actually
   holds.
7. **A `-like` pattern containing backticks silently matched nothing.** The job failure message reads
   ``Result entry 0 has no `benchmark` field``, and in a wildcard pattern a backtick is an escape, so
   the assertion searched for `no benchmark field`. Fixed to wildcard around the word.
8. **The JPA fixture could not backdate an artefact.** `created_at` is mapped non-updatable, so a
   retention test cannot age a row through the entity. Fixed in the test with a direct
   `update runtime_artifact set created_at = ?`, with a comment saying why; the production path still
   never moves a timestamp.

## 5. Known limitations, stated rather than papered over

- **No baseline, no trend and no regression detection.** Deliberate, and the product says so rather
  than shipping a comparison between runs that were not run under the same conditions.
- **Only the `-rf json` array is read.** A CSV export, the text table and a gzip file are refused by
  name. Harnesses that are not JMH and do not emit that shape are refused by the structural check.
- **The upload check cannot tell one JSON array from another.** It checks the first byte and the size;
  a wrong array is stored and then fails in the job. That is a deliberate trade: a full parse inside
  the upload request would be unbounded work on the request thread.
- **A histogram is counted, not ingested.** `histogram.buckets` and `histograms` are counts; the
  bucket contents are not turned into distributions, and RepoPilot computes no percentile from them.
- **`MAX_ENTRIES` and `MAX_MEASUREMENTS` are backstops.** A file beyond 10 000 entries is read as a
  prefix and the run says `truncated`; the row cap is 20 000. Neither is a judgement about a benchmark.
- **Retention is one window per repository.** No per-type window, no pinning ("never purge this one"),
  no on-demand delete for a single artefact, and no cross-repository deduplication.
- **Retention is not secure erasure** (see §3), and the startup orphan sweep is still the only
  defence for a deletion interrupted between its two steps.
- **Deleting a repository still deletes everything**, including measurements. Retention is the
  narrower operation, not its replacement.
- **The fixtures are not performance claims.** 500 ms iterations on a machine doing other things; the
  corpus README says so and the tests assert the numbers the files state.
- **Docker, PostgreSQL and Ollama remain unverified here** (see §7).

## 6. Files added or changed

**Added — backend**

```text
src/main/java/dev/repopilot/runtime/benchmark/BenchmarkFormat.java
src/main/java/dev/repopilot/runtime/benchmark/BenchmarkResultReport.java
src/main/java/dev/repopilot/runtime/benchmark/BenchmarkReader.java
src/main/java/dev/repopilot/runtime/benchmark/BenchmarkMeasurementMapper.java
src/main/java/dev/repopilot/runtime/benchmark/BenchmarkImportJobHandler.java
src/main/java/dev/repopilot/runtime/retention/ArtifactRetentionService.java
src/main/java/dev/repopilot/runtime/retention/ArtifactRetentionJobHandler.java
src/main/java/dev/repopilot/runtime/retention/RetentionScheduler.java
src/main/resources/db/migration/V5__artifact_retention.sql
src/test/java/dev/repopilot/runtime/benchmark/BenchmarkFormatTest.java
src/test/java/dev/repopilot/runtime/benchmark/BenchmarkReaderTest.java
src/test/java/dev/repopilot/runtime/benchmark/BenchmarkMeasurementMapperTest.java
src/test/java/dev/repopilot/runtime/BenchmarkImportIntegrationTest.java
src/test/java/dev/repopilot/runtime/ArtifactRetentionIntegrationTest.java
src/test/java/dev/repopilot/runtime/retention/RetentionSchedulerTest.java
src/test/resources/benchmarks/jmh-result.json
src/test/resources/benchmarks/jmh-result-2-forks.json
```

**Added — corpus and docs**

```text
corpus/benchmarks/README.md
corpus/benchmarks/fixture/pom.xml
corpus/benchmarks/fixture/RepoPilotFixtureBenchmarks.java
docs/adr/0011-benchmark-evidence.md
docs/adr/0012-artefact-retention.md
docs/phase-reports/phase-6-benchmarks-and-retention.md
docs/screenshots/performance-lab-benchmark-upload.png
docs/screenshots/performance-lab-benchmark-run.png
docs/screenshots/performance-lab-benchmark-provenance.png
docs/screenshots/performance-lab-benchmark-measurements.png
docs/screenshots/performance-lab-benchmark-artifact.png
docs/screenshots/settings-retention.png
```

**Changed**

```text
backend/src/main/java/dev/repopilot/api/v1/AnalysisController.java
backend/src/main/java/dev/repopilot/api/v1/RuntimeController.java
backend/src/main/java/dev/repopilot/config/RepopilotProperties.java
backend/src/main/java/dev/repopilot/config/StartupTasks.java
backend/src/main/java/dev/repopilot/jobs/AnalysisJobRepository.java
backend/src/main/java/dev/repopilot/jobs/JobService.java
backend/src/main/java/dev/repopilot/runtime/ArtifactStore.java
backend/src/main/java/dev/repopilot/runtime/MeasurementAggregation.java
backend/src/main/java/dev/repopilot/runtime/RuntimeArtifact.java
backend/src/main/java/dev/repopilot/runtime/RuntimeArtifactRepository.java
backend/src/main/java/dev/repopilot/runtime/RuntimeArtifactType.java
backend/src/main/java/dev/repopilot/runtime/RuntimeService.java
backend/src/main/java/dev/repopilot/runtime/query/RuntimeArtifactView.java
backend/src/main/resources/application.yml
frontend/src/lib/types.ts
frontend/src/lib/format.ts
frontend/src/lib/api.ts
frontend/src/hooks/queries.ts
frontend/src/pages/PerformanceLabPage.tsx
frontend/src/pages/SettingsPage.tsx
frontend/src/components/destinations.ts
scripts/smoke-test.ps1
.env.example
README.md
docs/architecture.md
docs/security.md
docs/adr/0008-runtime-evidence.md
corpus/benchmarks/README.md
```

## 7. Verification actually run

| Check | Command | Result |
| --- | --- | --- |
| Backend suite | `.\mvnw.cmd -B test` (from `backend/`) | **183 tests in 28 classes, 0 failures, 0 errors**, `BUILD SUCCESS`. Phase 6 added 55 of them: 10 format, 18 reader, 16 mapper, 4 import integration, 3 retention integration, 4 scheduler |
| Type check | `npm run typecheck` | clean under strict settings |
| Production build | `npm run build` | 117 modules, 358.03 kB JS (104.89 kB gzip), no warnings |
| Live smoke test | `scripts/smoke-test.ps1 -Token … -DataDir …` | **311 checks, 0 failures** (was 257; the new sections are benchmark import, benchmark refusals, retention plan, retention refusals, a maintenance pass, and the retirement of the three "stored but unread" checks) |
| Live benchmark import | upload + `POST …/analyze` on `benchmark-corpus` | `BENCHMARK_IMPORT` → `SUCCEEDED` in 96 ms; 277 measurements; summary names JMH 1.37 and JDK 21.0.5; provenance `comparisonsMade: false` |
| Live retention | `GET …/retention`, `POST …/purge` | plan reports `1 (281 KB)` stored, `0` candidates, cutoff 30 days; `{"olderThanDays":0}` → `400 VALIDATION_FAILED` ("… by omission"); `{"olderThanDays":3650}` → job `SUCCEEDED` with "Nothing to purge: no stored file is older than 3650 day(s)" and no analysis run |
| Frontend, live | Vite dev server + the running backend | Performance Lab reads the benchmark run (banner, provenance, 76 throughput rows), Settings reads and previews the retention plan; six screenshots captured |
| Fixture provenance | `corpus/benchmarks/README.md` | JMH 1.37, JDK 21.0.5+9-LTS-239, Windows x64, the exact `-f 1` / `-f 2` commands, and the warning that neither result is a performance claim |

**Not verified here, and why**

- **`docker compose up --build`** — no Docker daemon in this environment.
- **PostgreSQL** — never executed; the migration is portable by design and exercised on H2 in
  PostgreSQL-compatibility mode only.
- **The AI investigation flow** — Ollama is not installed here (`127.0.0.1:11434` refuses
  connections), so it remains the next phase's work and unexercised.
- **A live purge that actually deletes a file** — the smoke test runs against a fresh server where
  nothing is older than the window, so the deletion path is covered by
  `ArtifactRetentionIntegrationTest`, which backdates `created_at` and asserts: file gone, row marked,
  measurements intact, plan counts moved, re-upload clearing the mark, and the `409` before that.

## 8. Next phase

**Phase 7 — AI investigation with citation checking.** The remaining declared job types are
`INVESTIGATION`, `RECOMMENDATION_*` and `EMBEDDING_INDEX`; the first is the last destination with no
backend. It needs the provider abstraction that already exists, the evidence-selection rules already
written down, and a citation check that runs against the index before anything is shown — the same
discipline the runtime readers apply to a number, applied to a sentence a model wrote.
