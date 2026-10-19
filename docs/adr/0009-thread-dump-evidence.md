# ADR 0009 — Thread dumps: report what the JVM said, never search on its behalf

- **Status:** Accepted
- **Date:** 2026-10-19
- **Deciders:** RepoPilot maintainers

## Context

A thread dump is the cheapest evidence a Java operator can collect: one command against a running
process, no agent, no restart, no recording window to plan. That is exactly why it is also the most
over-claimed. It is a **text snapshot of one instant**: it says which threads existed at the moment
the JVM walked its thread list, what state each was in, which monitors it held or was waiting to
enter, and — if the JVM's own detector found one — which threads were deadlocked.

HotSpot can write that text three ways, and two of them throw away almost everything a concurrency
inspection is made of. Verified against JDK 21.0.5:

| Command | What the file actually contains |
| --- | --- |
| `jcmd <pid> Thread.print`, `jstack <pid>`, `kill -3 <pid>` | The rich form: `java.lang.Thread.State` per thread, `- locked`, `- waiting to lock`, `- waiting on`, `- parking to wait for`, and a deadlock section when the detector found one |
| `jcmd <pid> Thread.dump_to_file -format=plain` | Process id, an ISO-8601 timestamp, a JVM version, then `#tid "name"` and stack frames. **No state line, no lock line, no deadlock section.** Threads are not even in state order |
| `jcmd <pid> Thread.dump_to_file -format=json` | `{"threadDump": {...}}` with `processId`, `time`, `runtimeVersion` and `threadContainers`; a thread has `tid`, `name` and `stack[]` only |

A reader that accepted the latter two would produce a report in which every thread is in no state,
no monitor is contended, and no deadlock exists — a page of zeros that is **indistinguishable from a
healthy JVM**. That is worse than refusing the file, because a report is read as an answer.

The other temptations are subtler, and each produces a number that looks reasonable:

1. **"RUNNABLE means on a CPU."** HotSpot reports a thread parked in a native call — a socket read, a
   selector wait — as `RUNNABLE`. On a live backend this was measured, not assumed: twelve
   `reactor-http-nio-*` threads were `RUNNABLE` while sitting in `sun.nio.ch.WEPoll.wait(Native
   Method)`. Rendering that count as CPU occupancy would show a saturated service that was actually
   idle.
2. **"Search the lock graph for cycles ourselves."** A cycle in a snapshot is not a deadlock: the
   holder can release the monitor a microsecond later. The JVM's detector already ran, at a
   safepoint, with the actual wait graph — and it wrote its verdict into the file. Running a second
   detector would produce a number that disagrees with the JVM's own and cannot say which is right.
3. **"The SMR list length is the thread count."** `_java_thread_list=…, length=42` is the JVM's own
   list length, snapshotted *before* it walks the threads. The number of thread blocks in the file is
   the ground truth, and the two can differ.
4. **"Store the stacks so the evidence is complete."** The dump is already stored, byte for byte, by
   content hash. Copying thousands of frames into a database column duplicates the application's own
   source into the evidence store and makes every query heavier for no new claim.
5. **"Grade it."** A deep stack or a queue of waiters is a fact about a workload. Nothing in
   RepoPilot knows what this application promised, so there is no threshold it could apply honestly.
6. **"Compare it to the last dump."** Two dumps are two instants, taken by an operator at whatever
   interval was convenient. A difference between them is not a rate.

## Decision

**RepoPilot reads the rich plain-text dump, reports the JVM's own deadlock verdict, and applies no
threshold, no severity and no search of its own.**

1. **Only the `Thread.print` / `jstack` / `kill -3` form is read.** The two `Thread.dump_to_file`
   shapes are recognised by name and **refused at upload**, before any artefact is stored or any run
   exists, with the exact command that produces a supported dump. The refusal is a `422
   UNSUPPORTED_INPUT`, and it names why: no states, no lock ownership, no deadlock section.

2. **Measurements, not findings.** Thread-dump output goes to `runtime_measurement` on the contract
   of [ADR 0008](0008-runtime-evidence.md): unit `count`, kind `COUNTER`, reduction `COUNT` or `MAX`
   (a depth, a maximum holder count). There is no severity column to put a dump into. The run
   summary states in prose that no threshold was applied and no severity assigned.

3. **The `threaddump.` namespace.** Every key starts with it (`threaddump.threads.state.count`,
   `threaddump.monitors.waiters`). The leading segment is load-bearing twice: the backend derives a
   metric family from it and the UI filters on it, so a thread dump and a JFR recording in the same
   repository cannot be mistaken for each other's rows.

4. **All four states are written, including empty ones.** `RUNNABLE`, `BLOCKED`, `WAITING` and
   `TIMED_WAITING` are four rows with the state in `scope`, every time. "0 blocked" is an answer;
   an absent row is a question. A state line RepoPilot does not recognise is counted separately as
   `threads.unknownState.count`, and the raw state text is kept on the thread so an unrecognised
   state is distinguishable from no state line at all.

5. **The RUNNABLE caveat travels with the row.** The `RUNNABLE` measurement carries a `caveat`
   attribute stating that HotSpot reports a thread blocked in a native call as runnable, so the
   figure is not a count of threads using a CPU. The UI renders it directly under the table, and the
   Concurrency Inspector's footer repeats the "one instant" claim rather than relying on the reader
   having seen it.

6. **Deadlocks are reported, never found.** `threads.deadlock.count` is the number on the dump's own
   `Found N deadlock.` line. When that line is absent the count is zero and the attribute says so in
   the JVM's terms — *"no section means the detector printed none, which is not the same claim as
   the process having no deadlock of any kind"*. The cycles RepoPilot lists are connected components
   of the edges the dump itself printed (thread → monitor → holder), and the thread names travel in
   that measurement's own attributes because the deadlock section is the one place where names are
   the point.

7. **No stacks and no bulk thread names in the provenance.** The provenance JSON records the
   environment (`jvmName`, `jvmVersion`, `jvmMode`, `processId`), the dump's own timestamp **as text**
   (the JVM writes local time and records no zone, so converting it would invent an offset), counts
   (`threads`, `javaThreads`, `threadsWithoutJavaState`, the JVM's list length, deadlocks, contended
   monitors), and what was produced. The frames stay in the file. A dump names thousands of threads
   and frames; copying them into the evidence store would put the application's structure into a
   second place with no new claim attached.

8. **A file with no threads, or with no Java state anywhere, is refused.** Zero thread blocks means
   the file is not a dump. Zero blocks carrying `java.lang.Thread.State` means the file is a dump
   this build cannot read — the guard against a non-HotSpot format silently producing an all-zero
   report.

9. **The encoding is part of the file, not of the format.** A dump captured with `>` in PowerShell
   is UTF-16LE with a byte-order mark, because the shell transcodes the tool's output on the way to
   the file; `Thread.dump_to_file` writes UTF-8 itself. Both are the same dump. The byte-order mark
   is read and honoured, and a file without one is read as UTF-8. Decoding a UTF-16 file as UTF-8
   does not fail loudly — it interleaves NUL bytes through every line — so without this the reader
   would refuse a perfectly good dump as "not a thread dump", which is a refusal of evidence caused
   by the operator's terminal.

10. **A thread dump is not process memory.** `RuntimeArtifactType.THREAD_DUMP` reports
    `containsProcessMemory = false`, unlike a heap dump. A dump names threads, classes and frames —
    it describes your code — but it is not a copy of the heap, and a warning that says otherwise
    teaches the operator to ignore the warning where it is true (the heap dump).

11. **The run lifecycle is shared, not copied.** The second reader is what made the shared shape
    visible: create the run *before* reading (so a crash mid-read leaves a failed run rather than a
    silent gap), attach the artefact, persist the drafts in one batch, write the provenance, and
    complete on every path — `SUCCEEDED`, `CANCELLED`, `IOException → FAILED + unsupported`,
    `RuntimeException → FAILED + rethrow`. That sequence now lives in
    `AbstractRuntimeAnalysisHandler`; `JfrAnalysisJobHandler` and `ThreadDumpAnalysisJobHandler`
    supply only the file type, the run type, the reader version, the subject and the map from report
    to drafts.

## Consequences

- "Is this process deadlocked?" has an answer exactly when the JVM said so. Where it said nothing,
  the screen says the detector printed nothing rather than colouring the absence green.
- A thread dump and a JFR recording coexist in one repository as separate runs with separate
  families, and the repository's "current runtime evidence" is the newest of either. This is
  declared once, in `AnalysisRunType.producesRuntimeMeasurements()`, rather than by naming a
  pipeline: the first version of that query hard-coded `JFR_ANALYSIS` and silently stopped finding
  thread-dump runs the moment the second reader existed. An integration test now asserts it.
- Adding a third reader (heap dumps next) is a change in one enum, one handler subclass and one
  reader — not another copy of the run lifecycle.
- The measurements are reproducible: same file, same reader version, same numbers. The reader
  version (`thread-dump-reader=1.0.0`) is recorded on the run.
- The reader is testable without a database: `ThreadDumpReader` is a pure function from a path to a
  report, and `ThreadDumpMeasurementMapper` from a report to drafts. A real dump is taken in the
  test with `jcmd`, so the assertions run against HotSpot's actual wording rather than a fixture's.
- **No trend, no sampling, no attribution.** One dump is one instant: there is no rate, no
  per-thread CPU time, and no "this thread is the problem". Comparing dumps, sampling stacks over
  time, and attributing a slowdown to a frame are all different features, and none of them is
  claimed here.
- **The lock graph is the dump's own.** RepoPilot does not infer monitors from frames, does not
  resolve lock objects to fields, and does not know who *would* acquire a monitor next. A holder is
  named only where the dump printed `- locked`.
- **Windows text encodings are handled, not guessed.** A UTF-16 file *without* a byte-order mark is
  refused as unknown rather than detected by a heuristic, because a second format detector
  disagreeing with the first is worse than one clear refusal.
