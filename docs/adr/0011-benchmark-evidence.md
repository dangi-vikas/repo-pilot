# ADR 0011 — Benchmark results: quoting a harness, not measuring

- **Status:** Accepted
- **Date:** 2026-10-20
- **Deciders:** RepoPilot maintainers

## Context

Every artefact before this one was produced by a process RepoPilot could describe: a JFR recording, a
thread dump and a heap dump are all written *by the JVM*, at a moment the operator chose, about the
process that is in front of them. A benchmark result file is different in kind. It is written by a
**harness**, on a machine RepoPilot has never seen, under a configuration the operator assembled, and
the numbers in it were computed by that harness and nobody else. The three existing readers repeat
what the JVM recorded; this one repeats what a human-driven experiment claimed.

That difference decides almost everything below, and it is the reason the fourth evidence category
exists alongside "confirmed observation", "static-analysis finding" and "runtime measurement": a
benchmark score is an **external claim, quoted verbatim**, with the file as its only witness. RepoPilot
did not run the benchmark, did not time anything, cannot reproduce the machine, and has no baseline in
the repository — one upload states one run.

### What was established by recording real runs, not by reading about JMH

Two result files were recorded with JMH 1.37 on JDK 21.0.5 (`corpus/benchmarks/README.md` holds the
command, the exact fixture and the warning that neither is a performance claim): one with `-f 1` and
one with `-f 2`, over a fixture with three modes and one two-valued parameter, 14 entries each. Six
facts changed the reader:

1. **`"NaN"` is a JSON string.** When JMH cannot compute an error — a single fork of a throughput or
   average-time benchmark — it writes `"scoreError" : "NaN"` and
   `"scoreConfidence" : ["NaN", "NaN"]`, quoted, so the document stays valid JSON. A reader that called
   `asDouble()` would store `0.0`: an error estimate of exactly zero, the strongest precision claim a
   measurement can make, and one the file never made.
2. **`rawData` is nested one array per fork.** The `-f 2` file holds `[[3 values],[3 values]]`. Counting
   the outer array reports **one** iteration where the file records six; the reader flattens for the
   count and keeps the fork count separately.
3. **Sample mode writes no `rawData` at all**, and records `rawDataHistogram` instead. So "no
   per-iteration values" is a property of the mode, and the six sample entries in the `-f 1` file have
   nothing to check their score against.
4. **A histogram is per fork and per iteration, and its bucket counts differ between iterations.**
   The two histograms of one sample entry held 43 and 53 buckets — 96 in total, not `2 × 53`. Any
   arithmetic that assumed a rectangular shape would have been wrong in the first entry it met.
5. **`secondaryMetrics` duplicates `primaryMetric.scorePercentiles` in sample mode** — the same eight
   percentile levels, under `p0.00`…`p1.00` keys rather than `0.0`…`100.0`. Storing both as values
   would double-count one number under two names.
6. **The stated score is the mean of the recorded per-iteration values.** For the `-f 2` entry,
   `mean(rawData) == score` to every digit JMH printed. That is the one check this reader is entitled
   to make, because both sides of it are in the file.

## Decision

**RepoPilot reads the array `-rf json` writes, quotes every number in it as
`AS_REPORTED`, and compares nothing with anything.**

1. **Only `-rf json` is read, and the other three shapes are refused by name at upload.** A
   `-rf csv` table loses the percentiles and the per-iteration values; `-rf text` (and the console table)
   rounds for display and loses them too; a gzip file is refused with `gzip -d`. Each refusal is a
   `422 UNSUPPORTED_INPUT` that names the command which produces the readable file, **before** an
   artefact row exists.

2. **The upload check is deliberately weaker than the reader's, and the difference is stated.** JSON has
   no magic number and no header: the strongest claim available at upload is "the first non-space
   character is `[`" over the first 4 KiB, and a size check against
   `repopilot.analysis.max-benchmark-result-bytes` (64 MiB). A file that passes is stored; if it is an
   array of something else, the import run **fails**, naming the wrong entry and the field it lacks. The
   alternative — a shallow check that rejected every array it did not fully parse — would move a
   parse of the whole document into the upload request.

3. **Every value is `AS_REPORTED`; every count is `COUNT`.** The score, its error, its confidence
   bounds and each percentile are the harness's arithmetic. `MeasurementAggregation` gained
   `AS_REPORTED` for exactly this: a score relabelled `MEAN` would claim RepoPilot had averaged
   something it never saw, and a quoted percentile relabelled `MAX` would be false. The mapper is a
   pure function of the parsed report and performs no reduction.

4. **The key is `benchmark.<modeKey>.<leaf>`.** `thrpt → throughput`, `avgt → averageTime`,
   `sample → sampleTime`, `ss → singleShotTime`, with the harness's own mode kept verbatim in the row's
   `mode` attribute. Two modes of one method are two scores in the same file and must not collide. The
   scope is the benchmark name plus its declared parameters, `[size=16]`, so two parameter values of
   one method stay two distinguishable rows.

5. **The score is checked against the per-iteration values where the file records them.** Where
   `rawData` exists, the stated score is compared with the mean of the flattened values (relative
   tolerance `1e-9`) and the outcome becomes the row's `check` attribute: how many values and how many
   forks confirmed it. Where the file records a histogram instead, the attribute says
   `NOT_CHECKABLE`. **Nothing is stored as a result of the check** — no second number, no correction.
   The check is a statement about the file's internal consistency, phrased as one.

6. **The JVM's command line is counted and never stored, and the JVM's binary path is never read.**
   `jvmArgs` is where a `-D` credential lives, and the file carries it whether or not anyone meant to
   publish it; this is the rule the JFR reader already applies, applied to the second format that
   carries a command line. The provenance records `jvmArgumentsRecorded` and, beside it,
   `jvmArgumentsBasis` in the reader's own words. Declared `params` *are* stored, because they identify
   the measurement rather than describe the machine.

7. **No baseline, no comparison, no threshold, no severity.** `produced.comparisonsMade = false` and
   `produced.thresholdsApplied = false`; the file-level `entries` row carries a `note` saying one run
   of one benchmark on one machine says nothing about faster or slower. The UI has no state in which a
   delta could be drawn, because there is no delta to draw.

8. **File-level facts are rows, not only provenance.** `benchmark.file.entries`, `.modes`,
   `.parameters`, `.jvmArguments`, `.entries.withoutErrorEstimate`, `.entries.withoutIterations`. The
   last two bound what the rest can be trusted to say: a reader shown fourteen scores must be able to
   see that six of them could not be checked against anything.

9. **A percentile keeps the harness's level and loses only the decimal point in the key.** `99.0` →
   `.percentiles.99_0`, `99.9999` → `.percentiles.99_9999`, with the level itself in the `percentile`
   attribute. RepoPilot computes no percentile; it repeats one under the name the harness gave it.

10. **A histogram is counted, not ingested.** `histogram.buckets` carries the number of buckets across
    every histogram of the entry and `histograms` the number of histograms, because a bucket is a
    `(value, count)` pair and 96 of them are not 96 observations of anything RepoPilot can reduce.
    `secondaryMetrics` is counted with a basis saying the levels repeat the primary percentiles.

11. **Bounds are backstops, not policy.** `MAX_ENTRIES = 10_000` in the reader (a file beyond it is read
    as a prefix and the run says `truncated`), `MAX_MEASUREMENTS = 20_000` in the handler, a progress
    checkpoint per entry, and the 64 MiB upload bound. None is a judgement about a benchmark.

12. **A benchmark result is not process memory.** `containsProcessMemory = false`, and the upload screen
    says what the file does carry — a command line — instead of showing a warning that would be false.

## Consequences

- **"What did this benchmark say?" has an exact answer, and "is it faster?" does not.** Every figure is
  readable with the unit, the mode, the parameters, the harness version and the JVM it ran on. Whether
  the number is good, bad, or better than last month is not a question one file can answer, and every
  score row says so.
- **The fourth reader cost one enum change and no migration.** `RuntimeArtifactType.BENCHMARK_RESULT`
  flipped from "stored, unread" to supported, `AnalysisRunType.BENCHMARK_IMPORT` already declared
  `producesRuntimeMeasurements = true`, and `BenchmarkImportJobHandler` is a subclass of
  `AbstractRuntimeAnalysisHandler`. The "stored but unread" mechanism now has **no instance**: every
  type has a reader, and the Phase 3 smoke checks that asserted the unread contract were retired with
  a comment saying why rather than left to pass vacuously.
- **`AS_REPORTED` is available to any future reader** that repeats a number computed elsewhere. It is
  the aggregation that says "not mine", and it exists because the alternative was to mislabel one.
- **The fixture is evidence about the format, not about the methods.** The two files are 500 ms
  iterations on a machine doing other things; the corpus README says so, and the tests assert the
  numbers the files state rather than numbers that would be true of a quiet host.
- **Still not claimed:** baseline tracking, regression detection, cross-run comparison, throughput
  extrapolation, or any statement that a benchmark result reflects production traffic. One file, one
  run, one machine, quoted.
