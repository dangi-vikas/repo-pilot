# Measured results

Recorded measurements of RepoPilot's indexing pipeline, with the method that produced them.
A number without its host, build and input is an anecdote, so all three are stated.

- **Date:** 2026-10-17
- **Host:** 13th Gen Intel Core i5-1335U, 12 logical cores, 15.7 GiB RAM, NVMe SSD
- **OS:** Windows 11 (10.0.26200)
- **Runtime:** JDK 21.0.5 LTS
- **Storage:** H2 file database (`local` profile), PostgreSQL-compatibility mode
- **Workers:** 1 indexing job at a time; worker pool size 3
- **Input:** `corpus/generated/jdk21-synthetic`, generated with the default parameters of
  `scripts/generate-corpus.ps1` (100 packages × 6 types)

## The input

| Item | Value |
| --- | --- |
| Files on disk | 3 008 |
| `.java` files | 3 002 |
| Total size | 3.58 MiB (3 756 725 bytes) |
| Java source lines | 105 014 |
| Non-Java fixtures | 6 (see [README](README.md#the-fixtures-that-are-not-java)) |

The corpus is small in bytes and large in file count on purpose: indexing cost here is
dominated by per-file work (open, read, parse, resolve, insert), which is what the pipeline
is built to bound, rather than by throughput of large files.

## Method

```powershell
cd <repo root>
$env:REPOPILOT_WORKSPACE_ROOTS = "<repo root>"
$env:REPOPILOT_DATA_DIR        = "<temp>\repopilot-data"
$env:REPOPILOT_AUTH_TOKEN      = "<token>"
cd backend; .\mvnw.cmd -q -DskipTests spring-boot:run    # profile `local`

# register corpus/generated/jdk21-synthetic through the UI, then:
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\measure-index.ps1 `
    -RepositoryId <repository-uuid>
```

`measure-index.ps1` starts the job, polls it to a terminal state, and samples the Java
process that owns the listening port every 3 s for its working set and CPU time. The span
below is the job row's own `startedAt → finishedAt` interval, not the script's polling
window. Two full re-indexes of identical content were run; both are reported, because
reporting only the faster one would be choosing a number rather than measuring one.

## Results

### Three runs of the same input

| Run | Build | Span | Throughput | Peak working set | Average CPU |
| --- | --- | --- | --- | --- | --- |
| A | before the recorded-file fix | 157.95 s (`createdAt → finishedAt`) | 19.0 files/s · 52.6 ms/file | not sampled | not sampled |
| B | before the recorded-file fix | 172.07 s (`startedAt → finishedAt`) | 17.5 files/s · 57.3 ms/file | 945.6 MiB | 8.7 % of 12 cores |
| C | final | 190.15 s (`startedAt → finishedAt`) | 15.8 files/s · 63.3 ms/file | 897.3 MiB | 10.1 % of 12 cores |

63 resource samples were taken in run B and 62 in run C (every 3 s). The dispatch interval
(queued → running) was 0.89 s in run B and 0.15 s in run C.

**Read the spread, not the best number.** The three runs span 158–190 s — a 20 % spread on
identical input, with no code change that could plausibly account for it (the recorded-file
fix adds a language check and skips the Java parser for one file of 3 003). What differed was
the machine: a Vite dev server, a browser tab polling the API, and this agent's own tooling
were running alongside. The honest headline is therefore **≈16–19 files/s, roughly three
minutes for 3 000 files**, not a single figure quoted to three significant figures.

The CPU figure is the stable and interesting one: parsing 3 000 files at about **one busy
core out of twelve** means the pipeline is bound by per-file round trips (read, parse,
insert), not by parallelism. The worker pool is deliberately not the constraint at this size.

The memory figures are **process** peaks, not indexing costs. They include the JVM baseline
(heap reservation, H2, Netty, Hibernate metadata) and were not profiled down to an
attribution. They support exactly one claim: a 3 000-file index stayed under 1 GiB of working
set on this host.

### What the index contains

| Metric | Value |
| --- | --- |
| Files read (`source_file` rows) | 3 003 |
| — parsed cleanly | 3 001 |
| — parsed with problems | 1 |
| — failed to parse | 0 |
| — recorded without a language analyzer | 1 |
| Files inspected and deliberately not read | 5 |
| — `SECRET_BEARING` | 3 |
| — `UNSUPPORTED_LANGUAGE` | 2 |
| Directories never walked | 0 |
| Symbols | 16 205 |
| References | 105 001 |
| — resolved with a proven target | 28 800 (27.4 %) |
| — inferred (plausible, unproven) | 4 800 (4.6 %) |
| — external to the repository | 71 401 (68.0 %) |
| API mappings | 2 400 |
| Database size after indexing | 73 MiB (76 431 360 bytes on disk) |

The one `PARSED_WITH_PROBLEMS` file is the deliberately broken `_SyntaxBreak.java` fixture:
the parser recovered a tree from it, and it is counted as a problem rather than a success.
It is not reported as a parse failure either — a file that does not compile is still a file
the indexer read and partly understood.

The one `RECORDED` file is `src/main/resources/application.properties`. It is read, hashed
and stored, with no symbols and no references, because no analyzer for that language has been
written yet. Earlier runs of this pipeline reported it as `PARSED_WITH_PROBLEMS`, which was a
Java syntax verdict about a file that is not Java — the JavaParser had been fed the
`.properties` text. Recorded is not parsed, and the two are now counted separately.

The 27 % resolution rate is not a defect. 68 % of references in this corpus point outside
it (`java.util.*`, Jakarta annotations), and RepoPilot refuses to claim a target it cannot
prove. The corpus is generated to avoid ambiguity: the shared `com.example.shared.SharedRegistry`
is referenced from every package through an explicit import, and because imports are not
modelled those references are correctly reported as `INFERRED`, not `RESOLVED`.

### The dependency-graph endpoint, before and after bounding

The same corpus exposed a separate defect on the read path. `GET /repositories/{id}/graph`
originally returned every edge of the selected types, unpaged:

| | Before | After |
| --- | --- | --- |
| Edges returned | 100 801 | 1 000 of 100 801 (`truncated: true`) |
| Response size | 21.95 MiB | 223.1 KiB |
| Wall time | 49 687 ms | 1 061 / 1 153 / 1 483 ms over three calls |

Both rows are the same request, on the same host, against the same revision; the "after"
timings are steady state (the first call after a restart was 3 430 ms, which is JIT and page
cache warming, not a property of the query). The 50-second response was not an outlier — it
was the only behaviour the endpoint had, and the UI rendered the first 500 of the 100 801
edges it had downloaded without saying that anything was missing. The endpoint now returns a
bounded `GraphView` that names the total it sliced.

## Invariants checked against the API

These are asserted by the integration tests, and were re-checked on the recorded run:

| Invariant | Values | Holds |
| --- | --- | --- |
| `parsed + problem + failed + recorded == totalFiles` | 3 001 + 1 + 0 + 1 = 3 003 | ✅ |
| `enumeratedFiles == totalFiles + excludedFiles` | 3 008 = 3 003 + 5 | ✅ |
| `resolved + inferred + external == edgeCount` | 28 800 + 4 800 + 71 401 = 105 001 | ✅ |
| `excludedFiles` equals the sum of `excludedByReason` | 3 + 2 = 5 | ✅ |

The second row is the one that used to be false. Before the scan accounting was persisted,
`excludedFiles` was counted from `source_file` rows with an `EXCLUDED` status — rows the
indexer never writes — so a scan that skipped three credential files reported
`excludedFiles: 0`. An excluded file never becomes a row, so no row count could ever have
produced the right answer.

## Baseline: `examples/sample-java`

The 8-file sample repository is the deterministic reference used by the integration tests.
Re-indexing it after the accounting change produced:

| Metric | Value |
| --- | --- |
| Files | 8 read, 8 inspected, 0 excluded |
| Symbols | 49 |
| References | 138 — 49 resolved, 14 inferred, 75 external |
| API mappings | 10 |

This repository has no non-Java files, which is why the inspected and read counts agree
here. It is the case where "0 excluded" is a true statement, and it is worth having that
case next to the corpus case where it is not.

## Static analysis over the same corpus

Recorded 2026-10-18 on the same host, same corpus revision, with
`scripts/measure-analysis.ps1` (which reads the result back through the public API instead of
trusting the job's own summary):

| Metric | Value |
| --- | --- |
| Files analysed | 3 002 (PMD and the built-in rules) |
| Files skipped, with reason | 1 (`application.properties`: no analyzer for the language) |
| Findings | 600, all `pmd/ClassNamingConventions`, all `HIGH`, all `SOURCE_CONFIRMED` |
| Analysis span | 118.17 s (≈25 files/s) |
| Peak process working set | 770.5 MiB (OS-observed) |
| Peak private bytes | 814.5 MiB |
| Average CPU | 8.9 % of 12 logical cores |
| Database size afterwards | 76.7 MiB (73 MiB after indexing) |

The count was checked a second way, because a round number is either the whole truth or a
truncation: the corpus contains exactly 600 enum declarations whose names do not match
`[A-Z][a-zA-Z0-9]*`, which is precisely what that PMD rule reports. Nothing was dropped.

One file was reported rather than analysed: PMD could not parse the deliberately broken
`_SyntaxBreak.java`. The run recorded that in its summary and still succeeded, because the
authoritative statement that the file does not parse is the index's own
`PARSED_WITH_PROBLEMS` status — the analysis engine repeating it is a coverage fact, not a
run-level failure.

The corpus is a scale fixture and the numbers above are scale numbers. It was generated for
indexing volume, so one rule accounts for every finding. Rule-variety evidence comes from
`fixtures/quality-demo` instead: 3 files, 14 findings (10 from the built-in rules, 4 from PMD),
every built-in rule firing exactly once, one clean file with nothing, and a fake credential
whose value appears in no finding.

## What these numbers do not say

- **They are not PostgreSQL numbers.** The recorded runs used H2 in
  PostgreSQL-compatibility mode ([ADR 0004](../docs/adr/0004-database-portability.md)).
  Postgres timings will differ, and no Postgres measurement has been taken.
- **They are not a scaling curve.** One input size was measured. Whether the 53–63 ms/file
  cost is flat at 30 000 files is not known and is not claimed.
- **They say nothing about analysis quality.** The corpus is synthetic; it measures the
  pipeline, not whether the analysis is useful on real code.
- **Run-to-run noise is about 20 %** on this machine (158 s, 172 s, 190 s). A single faster
  run is not evidence of an optimisation: any change to this pipeline needs repeat runs
  before a difference is believable.
- **No warm/cold separation.** All runs used a warm JVM (the application had been serving
  requests), and none separated JIT warm-up from steady state.
- **The resource samples are 3-second snapshots.** A spike shorter than 3 s between samples
  would not appear in the peak. `PeakWorkingSet64` from the OS is reported by the script but
  not used here, precisely because a peak is only as good as its sampling.
