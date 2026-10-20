# Benchmark fixtures: one real JMH run, recorded twice

The benchmark reader in `dev.repopilot.runtime.benchmark` is checked against two result files that a
real JMH run produced. Neither is a performance claim; both are format evidence.

## What produced them

The harness is [`fixture/RepoPilotFixtureBenchmarks.java`](fixture/RepoPilotFixtureBenchmarks.java) and
[`fixture/pom.xml`](fixture/pom.xml): three benchmark methods in three JMH modes (`Throughput`,
`AverageTime`, `SampleTime`), one parameter with two values (`size` = 16, 256), and a deliberately short
run configuration so the whole fixture takes a couple of minutes.

| | |
|---|---|
| JMH | 1.37 |
| JDK | 21.0.5, `21.0.5+9-LTS-239`, `Java HotSpot(TM) 64-Bit Server VM` |
| Host | Windows, x64 |
| Recorded | 2026-10-20 |
| Build | `mvn -q package` (shade plugin) → `target/benchmarks.jar`, 2 885 773 bytes |

```text
# 1 fork: the error estimate JMH cannot compute
java -jar target/benchmarks.jar -f 1 -wi 1 -i 2 -r 500ms -w 500ms -rf json -rff jmh-result.json

# 2 forks: a real error estimate, and nested per-fork data
java -jar target/benchmarks.jar -f 2 -wi 1 -i 3 -r 500ms -w 500ms -rf json -rff jmh-result-2-forks.json
```

| File | Bytes | Entries | Where it lives |
|---|---|---|---|
| `jmh-result.json` | 129 658 | 14 | `backend/src/test/resources/benchmarks/jmh-result.json` |
| `jmh-result-2-forks.json` | 287 856 | 14 | `backend/src/test/resources/benchmarks/jmh-result-2-forks.json` |

The JSON is checked in once, next to the tests that read it, rather than copied here as well. This page
is the provenance for both files.

## Why two files instead of one

They differ in exactly the ways the reader has to get right, and one file cannot show all of them:

| Fact the reader depends on | `-f 1` | `-f 2` |
|---|---|---|
| `scoreError` for throughput and average time | the **string** `"NaN"` | a number (e.g. `3.259704679281219`) |
| `scoreConfidence` for those modes | `["NaN","NaN"]` | a real interval (e.g. `[7.0997, 13.6191]`) |
| `scoreError` for sample mode | a real number | a real number |
| `rawData` shape | 1 fork × 2 values | 2 forks × 3 values |
| `rawData` for sample mode | absent (a histogram instead) | absent (a histogram instead) |
| `secondaryMetrics` | `p0.00`…`p1.00`, 8 per sample entry | the same, 8 per sample entry |

Three consequences are visible in the first row and worth stating plainly, because they are the sort of
thing a reader gets subtly wrong:

1. **`"NaN"` is a JSON string, not a bare token.** The files are valid JSON, so a parser cannot rely on a
   non-standard `NaN` literal, and a reader that called `asDouble()` on it would store `0` — an error
   estimate of exactly zero, which is the strongest precision claim a measurement can make and one the
   file never made. RepoPilot stores "the file stated no number" instead, and says so on the row.
2. **Sample mode gets an error estimate even from one fork**, because its error comes from the sample
   distribution rather than from comparing forks. So "no error" is not a property of the fork count; it
   is a property of the mode and the fork count together.
3. **`rawData` is nested per fork.** Counting the outer array would report one iteration where the file
   records six. The reader flattens for the count and keeps the fork count separately, and each score row
   says how many values it was checked against.

## What the importer produces from them

The tests assert these totals, so a change in the reader that silently drops or duplicates rows fails
rather than passes:

| | `-f 1` | `-f 2` |
|---|---|---|
| Measurements written | **254** | **277** |
| Rows in throughput / average-time / sample mode | 16 / 16 / 20 per entry | 19 / 19 / 20 per entry |
| Entries with no per-iteration values | 6 (every sample entry) | 6 |
| Entries with no error estimate | 8 (the string `"NaN"`) | 0 |
| `jvmArgs` counted / stored | 0 / 0 | 0 / 0 |

The difference between the two totals is not a bug: a single-fork rate benchmark states no error, so
no error or confidence row is written for those entries. What the reader does with each is in
[ADR 0011](../../docs/adr/0011-benchmark-evidence.md), and the end-to-end checks are in
[the Phase 6 report](../../docs/phase-reports/phase-6-benchmarks-and-retention.md).

## What the numbers are not

The runs are 500 ms per iteration with one warmup iteration, on whatever else the machine was doing, with
no attempt at a quiet host. They are not a measurement of the fixture's methods and must not be cited as
one. What they are is a faithful recording of JMH 1.37's `-rf json` output, which is the only thing the
reader is being tested on.

## Regenerating them

```bash
cd corpus/benchmarks/fixture
mvn -q package
java -jar target/benchmarks.jar -f 1 -wi 1 -i 2 -r 500ms -w 500ms -rf json -rff jmh-result.json
java -jar target/benchmarks.jar -f 2 -wi 1 -i 3 -r 500ms -w 500ms -rf json -rff jmh-result-2-forks.json
```

Then copy both files into `backend/src/test/resources/benchmarks/` and re-run
`BenchmarkReaderTest` and `BenchmarkMeasurementMapperTest`. If the numbers move, the tests that assert
exact values will fail rather than pass quietly — which is the point of asserting them.
