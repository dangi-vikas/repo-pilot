# Phase report — concurrency inspection (thread dumps)

**Phase 4 — the Concurrency Inspector.** One thread dump, read as evidence: thread states, monitor
contention and the deadlocks the JVM's own detector reported, with no threshold, no severity and no
cycle search of RepoPilot's own.

## 1. What works now

Take a dump of a running JVM, upload it, and read what it said:

```text
jcmd <pid> Thread.print > thread-dump.txt
```

The Performance Lab accepts it (stored by SHA-256, deduplicated by content), the
`THREAD_DUMP_ANALYSIS` job reads it, and the Concurrency Inspector renders the result. There is no
new database migration: Phase 3's `runtime_measurement` table was designed for a second reader, and
this phase proved it.

Measured live, on this machine, with real dumps:

| Dump | Result |
| --- | --- |
| `td-rich.txt` — `jcmd 9568 Thread.print`, 93 690 bytes, captured through a PowerShell redirect (UTF-16LE + BOM) | **11 measurements.** 60 thread blocks, 42 with a Java state and 18 of the JVM's own. States: 22 `RUNNABLE`, 11 `TIMED_WAITING`, 9 `WAITING`, 0 `BLOCKED`. No deadlock reported, no contended monitor. Deepest stack 13 frames (`parallel-1`); the most monitors held by one thread is 2 (`reactor-http-nio-1`) |
| `td-deadlock.txt` — a two-thread deadlock, 9 235 bytes | **17 measurements.** 26 thread blocks, 13 with a Java state. 1 deadlock cycle involving 2 threads, as the dump's own `Found 1 deadlock.` line says; 2 contended monitors. Deepest stack 12 frames (`main`) |
| `td-plain.txt` — `Thread.dump_to_file -format=plain` | **Refused at upload**, `422 UNSUPPORTED_INPUT`, naming the command that wrote it and the command that produces a readable dump |
| `td-probe.json` — `Thread.dump_to_file -format=json` | **Refused at upload**, same reason |

Both refusals happen **before** anything is stored and before any run row exists, so a file RepoPilot
cannot read never leaves a run that "failed" for a reason the API could have stated up front.

## 2. What was built

**The reader.** `ThreadDumpReader` is the only class that knows HotSpot's wording. It streams the
file (a dump of a JVM with a hundred thousand threads never has to fit in memory), calls a
`checkpoint` as it goes so a cancelled job stops promptly, and produces a `ThreadDumpReport`:
environment, the dump's own timestamp, per-thread state and lock lines, the deadlock section, and the
`JVM global refs` tail line that marks the end of the thread blocks.

Three format facts were established by probing JDK 21.0.5 rather than assumed, and each one changed
the code:

- The **presence of a `java.lang.Thread.State:` line** is the reliable "is this a Java thread"
  discriminator. VM, GC and compiler threads have a thread header and no state line, which is why
  `threads.nonjava.count` exists and why "all threads" is not the denominator of any percentage.
- The thin `-format=plain` dump starts with the bare process id (`9568`), **not** `9568:`. The first
  version of the detector required the colon and let a real thin dump through as "unknown".
- The deadlock section **repeats thread blocks with identical lock lines**. A parser that kept
  reading blocks through it would count every deadlocked thread twice. Block parsing stops at
  `JNI global refs:` / `Found `, and inside the section a `"name":` only counts when the next line
  matches the wait pattern.

**The mapper.** `ThreadDumpMeasurementMapper` is a pure function from report to `MeasurementDraft`s,
and it is where every judgement is written down. The full set, all `COUNTER` / unit `count`:

| Key | What it is |
| --- | --- |
| `threads.count` | Thread blocks in the file |
| `threads.java.count` | Blocks carrying a Java state |
| `threads.nonjava.count` | Blocks with no state line — the JVM's own threads (written only when > 0) |
| `threads.state.count` | Four rows, `scope` = the state, always written even at zero |
| `threads.unknownState.count` | A state line this reader does not recognise (only when > 0) |
| `threads.deadlock.count` | Cycles the JVM's own detector reported |
| `threads.deadlocked.count` | Threads named by the deadlock section (only when > 0) |
| `monitors.contended.count` | Monitors with at least one thread waiting to enter |
| `monitors.waiters.max` | The most waiters on one monitor, `scope` naming that monitor |
| `monitors.waiters.observed` / `.reported` | How many were seen / how many the table lists (the `TOP_N = 20` disclosure) |
| `monitors.waiters` | One row per contended monitor, `scope` = monitor and holder, `share` in attributes |
| `threads.stack.depth.max` | Frames in the deepest block, `scope` naming the thread |
| `monitors.held.max` | Most monitors held by one thread, `scope` naming it |

**The shared run lifecycle.** The second reader made the shape visible, so the sequence moved into
`AbstractRuntimeAnalysisHandler`: repository check, artefact lookup, type check, refusal check,
resolve, **create the run before reading**, attach the artefact, `analyse()`, persist drafts in one
batch, write provenance, and complete on every path (`SUCCEEDED`, `CANCELLED`,
`IOException → FAILED + unsupported`, `RuntimeException → FAILED + rethrown`). `JfrAnalysisJobHandler`
shrank by roughly two hundred lines; `ThreadDumpAnalysisJobHandler` is 181 lines, most of them prose
about what the summary may claim.

**What counts as runtime evidence** moved out of a query and into an enum.
`AnalysisRunType.producesRuntimeMeasurements()` now declares it once, and
`RuntimeQueryService.currentRun` asks for the set instead of naming `JFR_ANALYSIS`. See §4 — the
first version of this was a defect.

**The UI.** A new Concurrency Inspector, plus changes to the Performance Lab so that one screen can
host two readers honestly:

- The family filter is namespace-aware. It used to build `` `jfr.${family}` ``; it now maps the
  artefact type to `jfr` or `threaddump` and sends `${namespace}.${family}`. An unknown namespace
  filters nothing — showing everything is the safe failure, hiding rows is not.
- The memory banner split in two. A heap dump "contains process memory"; a thread dump "contains no
  process memory" and names the class/method/frame structure it *does* reveal. The server flag
  (`containsProcessMemory`) drives it, and a thread dump is deliberately `false`.
- Provenance rows for a dump: when it was taken (as text, because the file records no zone), threads
  with and without a Java state, deadlock cycles reported, contended monitors.
- The Concurrency Inspector shows the dump picker (every analysed thread dump, newest first), the
  census, the four states with share bars and the RUNNABLE caveat, the deadlock cycles as
  thread → monitor → holder edges, the contention table with each monitor's owner and share, and the
  depth/held maxima. Every empty state names the command to take a dump, and says that
  `Thread.dump_to_file` is refused, in both formats, and why.

## 3. What the system refuses to claim

- **"0 deadlocks" is not "healthy".** When the dump has no deadlock section the count is zero and the
  attribute says: *"no section means the detector printed none, which is not the same claim as the
  process having no deadlock of any kind"*. The UI repeats it in the card, not in a tooltip.
- **`RUNNABLE` is not "on a CPU".** The row carries the caveat, verified live: twelve
  `reactor-http-nio-*` threads were `RUNNABLE` while parked in `sun.nio.ch.WEPoll.wait(Native
  Method)`.
- **No deadlock search.** Cycles are the connected components of the edges the dump itself printed.
  RepoPilot does not infer a lock graph from frames, and a cycle in a snapshot is not proof that the
  cycle persists.
- **No severity, no threshold, no trend.** A deep stack and a queue of waiters are facts. One dump is
  one instant: no rate, no duration, no comparison with an earlier dump.
- **No stacks in the database, and no bulk thread names.** The file is stored by hash; the provenance
  stores counts and the environment. Thread names appear only in the deadlock cycle's own
  measurement, because naming the threads is the entire point of a deadlock report.
- **`Thread.dump_to_file` is refused, not partially read.** A report built from it would be all
  zeros — indistinguishable from a healthy JVM.

## 4. Defects found and fixed during verification

1. **The "current runtime run" query was JFR-only.** `RuntimeQueryService.currentRun` named
   `JFR_ANALYSIS`, so the moment a thread-dump run existed and was newer, `GET /runtime/summary`
   reported the *older* JFR run — and on a repository that had only ever had a dump, it would report
   no runtime evidence at all. Found by the integration test, not by reading the code. Fixed by
   `AnalysisRunType.producesRuntimeMeasurements()` and
   `findFirstByRepositoryIdAndRunTypeInOrderByStartedAtDesc(id, Set<AnalysisRunType>)`. Adding a
   reader is now a change in one enum, and the smoke test asserts the summary follows the newest run
   across readers.
2. **A dump captured through a Windows shell was refused as "not a thread dump".** `jcmd <pid>
   Thread.print > dump.txt` in PowerShell writes UTF-16LE with a BOM; read as UTF-8, every line is
   every other byte, the `Full thread dump` header disappears, and the file is refused. That is a
   refusal of evidence caused by the operator's terminal. Found by live verification — the unit and
   integration tests had captured `jcmd` through `ProcessBuilder` (raw bytes) and never saw it. Fixed
   by reading the byte-order mark, decoding accordingly, and stripping the mark from the first line
   (which is exactly where classification looks; `String.trim()` does not remove `U+FEFF`). Covered by
   two new reader tests and an integration test that re-encodes a real dump.
3. **The thin-format detector required a colon the real file does not have.** `Thread.dump_to_file
   -format=plain` writes `9568` on the first line; the pattern was `^(\d+):$`, matching the *rich*
   dump's `9568:`. A real thin dump would have been classified as unknown rather than refused by
   name. Found by probing the actual command and comparing the two files.
4. **The smoke test's real-dump section skipped itself for the wrong reason.** The `java.home` probe
   ran `java -XshowSettings:properties -version 2>&1`, and `java -version` writes to stderr:
   under `$ErrorActionPreference = "Stop"` in PowerShell 5.1 that becomes a terminating error, the
   surrounding `try` swallowed it, and 30-odd assertions silently became a "SKIPPED". Fixed by
   going through `cmd /c`, which merges the streams before PowerShell sees them. The section now runs
   and takes a real dump of the backend's own JVM.
5. **A smoke assertion assumed a disclosure row that is omitted by design.** With zero contention the
   mapper writes no `monitors.waiters.*` table, so `monitors.waiters.observed` does not exist — and
   the check that it "is stated" failed. The contract is the opposite and correct: the count row is
   the whole answer when there is nothing to disclose. The assertion now checks both branches
   (absent when nothing was contended, present and consistent when something was).
6. **Two CSS classes in the new page did not exist.** `metric-grid` and `rp-code-block` are not in
   `app.css`; the build would have shipped an unstyled grid and a bare `<pre>`. Caught before the
   build by grepping the stylesheet, replaced with `grid grid-4` and an inline-styled block matching
   the token system.

## 5. Known limitations, stated rather than papered over

- **One dump is one instant.** No trend, no comparison, no "this is getting worse". A sampling
  profiler is a different feature and is not implemented.
- **No per-thread CPU time.** HotSpot's dump does not carry it (the `cpu=` field is cumulative for
  the thread's life, not a rate), and RepoPilot does not present it.
- **The lock graph is the dump's own.** No inference from frames, no resolution of lock objects to
  fields, no prediction of who acquires a monitor next.
- **A UTF-16 file without a byte-order mark is refused**, not guessed at. Guessing would be a second
  format detector disagreeing with the first.
- **A cancelled thread-dump run keeps the measurements it already persisted**, exactly as a JFR run
  does, and the run row says `CANCELLED`. Nothing pretends a partial read is complete.
- **Heap dumps are still accepted and refused by name.** They are the next reader, and they are the
  reason the lifecycle was extracted.
- **No heap analysis, no benchmark import, no AI investigation** — unchanged from Phase 3. The
  remaining `Not yet` rows in the README say so explicitly.
- **Docker is unavailable in this environment**, so `docker compose up --build` is still unverified,
  and **PostgreSQL has never executed** (the migrations are portable by design and exercised on H2 in
  PostgreSQL compatibility mode only).

## 6. Files added or changed

**Backend — new**

| File | Lines | Purpose |
| --- | ---: | --- |
| `runtime/AbstractRuntimeAnalysisHandler.java` | 238 | The run lifecycle both readers share; nested `AnalysisOutcome` |
| `runtime/JvmLabels.java` | 68 | JVM label/version extraction, shared once two readers needed it |
| `runtime/threaddump/ThreadDumpFormat.java` | 134 | Which of the three shapes a file is, and the refusal prose |
| `runtime/threaddump/ThreadDumpReader.java` | 493 | The only class that knows HotSpot's wording |
| `runtime/threaddump/ThreadDumpReport.java` | 271 | The parsed dump: environment, threads, states, locks, deadlocks |
| `runtime/threaddump/ThreadDumpMeasurementMapper.java` | 287 | Report → drafts, pure, with every caveat |
| `runtime/threaddump/ThreadDumpAnalysisJobHandler.java` | 181 | The `THREAD_DUMP_ANALYSIS` handler and its summary prose |

**Backend — changed:** `analysis/AnalysisRunRepository.java` (set-based run-type query),
`analysis/AnalysisRunType.java` (`producesRuntimeMeasurements()`),
`runtime/JfrAnalysisJobHandler.java` (now a subclass, −200 lines),
`runtime/RuntimeArtifactType.java` (`THREAD_DUMP` supported, `containsProcessMemory = false`, accurate
javadoc), `runtime/RuntimeService.java` (`verifyThreadDump` at upload),
`runtime/query/RuntimeQueryService.java` (the current-run fix).

**Backend tests — new:** `runtime/ThreadDumpAnalysisIntegrationTest.java` (445 lines, 5 tests),
`runtime/threaddump/RealThreadDump.java` (71), `ThreadDumpFormatTest.java` (109),
`ThreadDumpReaderTest.java` (383), `ThreadDumpMeasurementMapperTest.java` (265).
**Changed:** `runtime/JvmLabelTest.java` (moved with the class).

**Frontend — new:** `pages/ConcurrencyInspectorPage.tsx` (643 lines).
**Changed:** `components/AppShell.tsx` (route), `components/destinations.ts` (concurrency available),
`lib/format.ts` (`metricNamespace`), `lib/types.ts` (`RuntimeProvenance.dump`, the
`containsProcessMemory` doc), `pages/PerformanceLabPage.tsx` (namespace-aware filter, split banners,
dump provenance rows, runtime job types), `pages/CapabilityPage.tsx` (the now-dead concurrency plan
removed, and the memory plan's "what already exists" corrected to name the contract both readers
already use).

**Scripts:** `scripts/smoke-test.ps1` — a thread-dump section (refusals by name, a real dump of the
backend, the full measurement contract, cross-reader run selection) plus the `cmd /c` probe fix.
**No migration was added.**

**Documentation:** `docs/adr/0009-thread-dump-evidence.md`, this report, `README.md`,
`docs/architecture.md` (the thread-dump read path), `docs/security.md` §9 (what a dump reveals and
what is stored from it), and five screenshots:
`concurrency-inspector.png`, `concurrency-inspector-states.png`,
`concurrency-inspector-deadlock.png`, `concurrency-inspector-empty.png`,
`performance-lab-thread-dump.png`.

## 7. Verification actually run

| Check | Result |
| --- | --- |
| `.\mvnw.cmd -B test` (backend) | **100 tests, 0 failures, 0 errors, 0 skipped** across 19 test classes |
| `npm.cmd run typecheck` | clean |
| `npm.cmd run build` | 116 modules, **332.28 kB** JS (99.78 kB gzip), 1.31 s |
| `scripts\smoke-test.ps1` against the live backend | **180 checks, 0 failures** (was 130 before this phase) |
| Live upload + analysis, two real dumps | 11 and 17 measurements; run `THREAD_DUMP_ANALYSIS` / `SUCCEEDED`; provenance as designed |
| Live refusal, `-format=plain` and `-format=json` | `422 UNSUPPORTED_INPUT`, both naming the supported command |
| Screenshots | 5, from the running UI against the live backend |

The 20 unit tests that read a dump take a **real** dump of the test JVM with `jcmd` (skipping with an
`Assumptions` failure only if no JDK is present), so the assertions run against HotSpot's actual
wording rather than a checked-in fixture that can age.

## 8. Next phase

**Heap-dump analysis** on the same contract, which is the first feature in the phase plan that the
shared handler was extracted for:

1. `HEAP_ANALYSIS` handler subclass; `RuntimeArtifactType.HEAP_DUMP` becomes supported.
2. A reader that reports what a heap dump can say without a full dominator computation, and refuses
   what it cannot: the file format, the classes with the largest retained *sampled* share, and the
   provenance. `containsProcessMemory` stays `true`, and the UI's warning stays as it is.
3. Deterministic measurements only: no "leak" verdict, no severity, and a stated sampling basis for
   anything that is not exact.
4. Then `BENCHMARK_IMPORT` (JMH), and AI investigation last, with citation validation against the
   index.
