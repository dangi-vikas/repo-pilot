# Phase report — memory analysis (heap dumps)

**Phase 5 — the Memory Inspector.** One heap dump, read as a census: the objects the file recorded,
the types they belong to, and the bytes their fields and elements hold. **No retained size, no leak
verdict, no threshold and no severity** — because a dump cannot answer the first and RepoPilot has not
been told the last.

## 1. What works now

Take a dump of a running JVM, upload it, and read what it recorded:

```text
jcmd <pid> GC.heap_dump heap.hprof            # or: jmap -dump:format=b,file=heap.hprof <pid>
```

The Performance Lab accepts it (stored by SHA-256, deduplicated by content), the `HEAP_ANALYSIS` job
reads it, and the Memory Analysis screen renders the result. **No migration was added**: Phase 3's
`runtime_measurement` table has now carried three different readers without a schema change.

Measured live, on this machine, with real dumps:

| Dump | Result |
| --- | --- |
| `probe-matched.hprof` — a **pre-compiled** subject with a population built to be counted (1 000 × `byte[1024]`, 250 × `int[256]`, 500 `Node`, 120 `Bucket`, each held from a `static` list), 4.6 MB | **61 measurements.** 18 501 objects holding 1 986 030 recorded bytes (11 081 instances, 7 420 arrays) across 352 types; 890 classes loaded and dumped; 819 GC roots; largest type `byte[]` 1 147 721 recorded bytes over 4 375 objects; largest single object 22 208 bytes of `java.lang.Object[]`; `Node` **exactly 500 objects × 20 recorded bytes** |
| `probe.hprof` — the same subject run in single-file source mode, so the JVM also holds **javac's own objects** (a `SharedNameTable$NameImpl` of 7 622 and 13 545 `byte[]` from the class files it read), 9.6 MB — the dump the screenshots show | **61 measurements.** 71 754 objects holding 4 301 737 recorded bytes (48 844 instances, 22 910 arrays); 827 types, **0 unnamed**; 2 905 classes loaded, 2 905 dumped; 1 342 GC roots (`sticky-class` 1 193, `jni-global` 73, `java-frame` 69, `thread-object` 7); `HeapProbe$Node` 500 objects |
| `heap-dump.hprof` — a dump of this backend taken by the smoke test itself, **178.6 MB** | **61 measurements.** 1 516 519 objects holding **119 225 663 recorded bytes**; 1 156 815 instances and 359 704 arrays across 10 585 types; largest type `byte[]` with 62 964 506 recorded bytes over 175 171 objects; largest single object 4 194 304 bytes of `byte[]`; 4 418 GC roots; header timestamp `2026-10-19T08:38:51.386Z` |

And the counts that matter most, because they are the ones an operator will compare against. The
histogram and the dump below were taken of the **same process, one second apart**, from the
pre-compiled subject:

| Type | `jcmd GC.class_histogram` | This reader | `-histo` bytes (estimated) | Recorded bytes |
| --- | ---: | ---: | ---: | ---: |
| `byte[]` | 4 375 objects | **4 375 objects** | 1 227 192 | 1 147 721 |
| `java.lang.String` | 3 172 objects | **3 172 objects** | 76 128 | 44 408 |
| `HeapProbe$Node` | 500 objects | **500 objects** | 12 000 | 10 000 |

The object counts agree exactly and the byte figures deliberately do not — the dump's field data
carries no object header and the dump writes identifiers at its own width (20 bytes recorded for a
`Node` the JVM sizes at 24). The totals do not agree either, and that one is worth knowing: the
histogram listed **19 403** objects and the dump's census is **18 501**, because taking a dump
collects first — which is why `GC.heap_dump` has an `-all` flag. A census of a dump is a census of
what the process was *holding*, not of everything it had allocated.

Three files an operator is likely to have *instead* of a dump are each refused at upload, before an
artefact row or a run row exists, with `422 UNSUPPORTED_INPUT` and the command that produces a
readable file:

| Uploaded as `HEAP_DUMP` | Refusal names |
| --- | --- |
| `jmap -histo` / `GC.class_histogram` text | both commands, and says a histogram has "no object graph behind those numbers" |
| A gzip stream (`GC.heap_dump -gz=<level>`) | `-gz=`, `gzip -d`, and that RepoPilot does not decompress inside the job |
| A plain-text thread dump | `Full thread dump`, and `THREAD_DUMP` as the type that reads it |
| A JFR recording (`FLR`) | `JFR_RECORDING`, and why a timeline is not a snapshot |
| `JAVA PROFILE 1.0.3` | the versions this build reads (`1.0.1`, `1.0.2`) and that ART's records differ |
| An empty file | nothing to read |
| Anything else | the HPROF header it expected, and the hex of what it found instead |

## 2. What was built

**The reader.** `HeapDumpReader` (790 lines) is the only class that knows HPROF. It streams the file —
a 179 MB dump never has to fit in memory — calls a `checkpoint` as it goes so a cancelled job stops
promptly, and produces a `HeapDumpReport`. Five format facts were **established by probing JDK 21.0.5
rather than taken from a specification**, and each one changed the code:

- **The header is 19 bytes, then a `u4` identifier width, then a `u8` timestamp in epoch
  milliseconds, which the format defines as UTC.** A 64-bit dump declares `idSize = 8` even with
  compressed references, and every reference inside an object is written at that width.
- **An `INSTANCE_DUMP` states how many bytes follow it, and that count already includes the fields the
  class inherits.** No field layout, no superclass walk and no symbol resolution is needed to advance:
  `classObjectId` plus that count is the whole record. Resolving class metadata to walk instances
  would have been work with nothing to show for it.
- **A `STRING` record's body is an identifier followed by the text**, not a length-prefixed string, and
  `LOAD_CLASS` names a class by *pointing at one of those identifiers*. Class names are therefore only
  reachable through the string table, which is why the reader makes two passes.
- **`ROOT_THREAD_OBJECT` writes eight bytes after the identifier, not the four-byte thread serial that
  summaries of the format give.** This is not a detail: reading four bytes desynchronises the walk, and
  the desynchronisation was reproduced deliberately — with four, the parser hits record tag `0x58`
  inside a valid dump within one megabyte. Because the reader refuses an unknown record tag rather than
  guessing a width, what that reproduced was a *refusal*, not a wrong number.
- **A dump contains only what was reachable.** A histogram of the same JVM taken one second before its
  dump listed 19 403 objects where the dump holds 18 501: taking a dump collects first. This is a fact
  about the *evidence* rather than about the reader, and it is not left to the reader to notice — the
  census row carries it as a note, the run summary states it, and the screen repeats it under the
  tables.

**The two passes.** Pass one reads the class table (`LOAD_CLASS` → object id, name string id). Pass two
walks the heap *and* keeps the text of only the strings pass one asked for, so the string table — 68 674
records in the 10 MB dump, 500 000-plus in the 179 MB one — cannot bound the reader's memory. Names are
resolved after the walk, which is also why a name that never arrives is *counted*
(`heapdump.objects.unnamed`) rather than invented.

**The mapper.** `HeapDumpMeasurementMapper` is a pure function from report to `MeasurementDraft`s, and
it is where every judgement is written down:

| Key | What it is |
| --- | --- |
| `objects.count` / `.bytes` | The whole census, and the whole recorded data |
| `objects.unnamed` | Objects with no `LOAD_CLASS` record (only when > 0) |
| `instances.count` / `.bytes` | Objects with fields, and the field values the dump wrote |
| `arrays.count` / `.bytes` | All arrays |
| `arrays.object.*` / `arrays.primitive.*` | The two kinds apart, because they behave differently |
| `types.count` | Named types in the dump |
| `classes.loaded` / `classes.dumped` | From the header's class records |
| `types.bytes` (scope = type) | The largest types by recorded bytes, `TOP_N = 20`, plus `observed` / `reported` and a `share` of the whole census |
| `types.objects` (scope = type) | The largest types by object count — a different question |
| `roots.count` / `roots.kind.count` (scope = kind) | Where a collector starts from |
| `object.bytes.max` (scope = type) | The largest *single* object, because a byte total cannot answer "is something enormous in here" |

Every byte row carries `basis`, the sentence that it excludes object headers and class metadata, and the
sentence that it is **not a retained size**. The whole-census count carries `note` instead: the objects
in the file are the ones the process was still holding. Every type row carries `kind`, `objects`, and
`shareBasis` (a share of the whole census, not of the twenty rows shown).

**The handler.** `HeapDumpAnalysisJobHandler` (206 lines) is the third subclass of
`AbstractRuntimeAnalysisHandler`, and the lifecycle itself did not change: repository check, artefact
lookup, type check, resolve, **create the run before reading**, `analyse()`, persist the drafts in one
batch, write provenance, complete on every path. ADR 0009 predicted that adding this reader would be
"a change in one enum, one handler subclass and one reader", and it was — plus, this time, two lines in
`RuntimeService` for the upload check.

**The UI.** A new Memory Analysis screen, and the Performance Lab taught about the third reader:

- The namespace map gained `HEAP_DUMP → "heapdump"`, so the family filter sends `heapdump.objects`
  rather than `jfr.objects`, and `HEAP_ANALYSIS` joined the job types the screen polls.
- Provenance rows for a heap dump: the format version, the identifier width and why that is not the
  width of a reference, the string count, the object count, the recorded bytes, the types, and
  *"Retained sizes computed: no"*.
- One provenance label had to be fixed rather than extended: the thread dump's row was
  *"Dump taken (local time)"*. A heap dump's header timestamp is UTC, so the value is now shown under
  a neutral label with **the reader's own `takenAtBasis` sentence next to it**, which is right for both
  readers and cannot drift as a third one arrives.
- The Memory screen shows the dump picker (every analysed heap dump, newest first), the four headline
  readings, the census table with each part's share, the two type tables with their `TOP_N` disclosure,
  the GC roots by kind, the unnamed-object warning when there is one, and a footer that repeats what the
  screen is not. Every empty state names the two commands that write a dump and says that a gzip stream
  and a class histogram are refused.

## 3. What the system refuses to claim

- **No retained size, and no leak verdict.** What would be freed if a type became unreachable is a
  reachability computation over the whole object graph; this build does not build a dominator tree.
  `produced.retainedSizesComputed` is `false` in the provenance, the run summary says "no retained size
  computed", and the screen says it under the tables. A type near the top of the table is a type whose
  objects *hold a lot of recorded data*.
- **Recorded bytes are not occupied memory.** Every object also has a header and the JVM pads it, and
  the dump's field data contains neither. That is the entire content of the `byte[]` disagreement
  above, and the reason the basis is on the row rather than in a tooltip.
- **The census is of the objects the process was still holding, and it says so.** Taking a dump collects
  first, so anything that had already become garbage is not in the file: a histogram of the same JVM
  taken a second before its dump listed 19 403 objects where the dump holds 18 501, which is also why
  `GC.heap_dump` has an `-all` flag. "How many objects are in this heap" therefore has two defensible
  answers, and the one in front of the operator is stated three times: on the census row, in the run
  summary, and under the tables.
- **One instant, no trend.** Nothing here is a rate, a growth curve or a comparison with an earlier
  dump; there is no heap-over-time view and no `BENCHMARK_IMPORT` behind one.
- **No threshold and no severity.** Whether a heap is too full is a policy about a process's purpose and
  its `-Xmx`, and the reader has been told neither. Every row is a `SIZE`/`SUM`, `COUNTER`/`COUNT` or
  `MAX` measurement on ADR 0008's contract.
- **The type tables do not sum to the whole.** They are cut at twenty, and when a dump has no
  `LOAD_CLASS` record for some objects those objects are in the totals, absent from both tables, and
  reported separately with a sentence saying why.
- **Only a type name is stored.** The provenance holds the header, the file size, the string count and
  the census — no type names, no field values and no object identities. A heap dump cannot be
  reconstructed from RepoPilot's database, not even partially.
- **A compressed dump is refused, not expanded.** A 2.6 MB gzip file expanded to 10 MB in the test
  case; the ratio is the point. Decompressing inside the job would let a file that passed the upload's
  size limit hand a worker an unbounded amount of work.
- **A class histogram is refused, not read as a cheap dump.** It has counts and estimated totals and no
  object graph behind them, so nothing in it could be verified.

## 4. Defects found and fixed during verification

1. **No class name resolved on any real dump.** `HeapDumpReader.read` built the walk with
   `classTable.nameStringIds().keySet()` — the *class object ids* — instead of
   `Set.copyOf(classTable.nameStringIds().values())`, the string ids. Every real dump came back with
   only the eight synthesised primitive-array types and tens of thousands of unnamed objects. It was
   found by reading a real 10 MB dump, and it is the reason the unit fixture was not enough: the
   hand-built dump happened to use string identifiers that coincided with its class object identifiers,
   which made the wrong map a no-op *for that fixture only*. The integration test now asserts an exact
   census against a `jcmd`-captured dump, so the shape of the map is checked against HotSpot's output
   rather than against a fixture's convenience.
2. **The test fixture wrote four trailing bytes for root tag `0x08`, where HotSpot writes eight.**
   Found the same way — by probing what `GC.heap_dump` actually emits — and fixed in the fixture, with
   the width and the evidence for it recorded in both the reader and the fixture.
3. **The probe's "known population" was not the whole population.** The subject was run in single-file
   source mode, so `javac` compiled it *in the same JVM*: the dump held 827 types, including 7 622
   `com.sun.tools.javac.util.SharedNameTable$NameImpl` and 13 545 `byte[]`, of which the subject's own
   thousand `byte[1024]` were a minority. The subject's objects were still there and still countable —
   `Node` 500, and `byte[]` counts matching the JVM's histogram — but "the numbers the reader produced
   were checked against a known population" was weaker than it sounded. The probe is now compiled
   before it starts, and the check is a **matched pair**: a histogram and a dump of the same process, a
   second apart, compared type by type.
4. **The census did not say what it was a census of.** Taking the matched pair above produced 19 403
   objects in the histogram and 18 501 in the dump, because taking a dump collects first. The reader's
   number was right and the product never said what it excluded, which left "how many objects are in
   this heap" with two defensible answers and only one of them in front of the operator. Fixed: the
   census row carries the note, the run summary states it, and the Memory screen's footer repeats it.
5. **A benchmark result was flagged as process memory.** `RuntimeArtifactType.BENCHMARK_RESULT` had
   `containsProcessMemory = true`, which the artefact list renders as "this file contains process
   memory" — false for a JSON table an operator's own harness wrote. Found while writing this phase's
   smoke checks, which needed a type that is still *stored but unsupported* (the role the heap dump
   used to play): the flag was corrected to `false`, and the same checks now run against
   `BENCHMARK_RESULT`, which keeps the Phase 3 contract covered instead of deleting it. A warning that
   is false where it is easy to check stops being read where it is not.
6. **The new smoke checks threw on a row with no attributes.** The backend omits null-valued properties
   from its JSON, so a plain count (`heapdump.classes.loaded`) has no `attributesJson` key at all, and
   `Set-StrictMode -Version Latest` makes reading a missing property an error rather than `$null`. The
   two "no row carries a severity / a threshold" checks now read through the script's `Get-Prop`, which
   is exactly what the absence of the property means.

## 5. Known limitations, stated rather than papered over

- **No dominator tree, therefore no retained size, no leak detection and no classloader attribution.**
  This is the largest gap in the phase and it is deliberate: a wrong retained size is worse than none,
  and the honest version of the feature needs a second pass over the object graph that this build does
  not make. Without reachability, instance *counts* per type are what the census gives, and the screen
  says which table answers which question.
- **No comparison of two dumps.** A heap-over-time view needs a diff of two censuses and a retention
  policy for the dumps themselves; neither exists.
- **The dump is read twice, and the whole file is walked.** A 179 MB dump is read in two streaming
  passes with no memory proportional to the file, but there is no partial read and no "just this type"
  query — the census is the unit of work.
- **Only `1.0.1` and `1.0.2` HPROF are read.** ART's `1.0.3` is refused by name rather than guessed at.
- **A gzip dump has to be uncompressed by hand** (`gzip -d`), and a 179 MB dump will produce a 179 MB
  file: progress is reported, and a cancelled run keeps the measurements it already persisted with the
  run row saying `CANCELLED`.
- **Retention is still not implemented by age.** `ARTIFACT_RETENTION` has no handler, so a 179 MB dump
  stays on disk until its repository is deleted. This is more visible now than it was in Phase 3,
  because heap dumps are the largest artefact kind by an order of magnitude. It is the first item of
  the next phase's non-feature work.
- **`jmap -histo` byte totals and this reader's differ by design**, and the platform's own tools
  disagree with each other too: `GC.class_histogram` estimates headers and padding, this reader reports
  what the file contains.
- **Docker is still unavailable in this environment**, so `docker compose up --build` remains
  unverified, and **PostgreSQL has never executed** (the migrations are portable by design and are
  exercised on H2 in PostgreSQL-compatibility mode only).
- **Ollama is not installed here**, so the AI-investigation flow is still unverifiable, and the
  remaining job types (`BENCHMARK_IMPORT`, `INVESTIGATION`, `RECOMMENDATION_*`, `EMBEDDING_INDEX`,
  `ARTIFACT_RETENTION`) still have no handler.

## 6. Files added or changed

**Backend — new** (`dev.repopilot.runtime.heapdump`)

| File | Lines | Purpose |
| --- | ---: | --- |
| `HeapDumpReader.java` | 790 | The only class that knows HPROF: two-pass streaming walk, the verified record table, the refusals |
| `HeapDumpMeasurementMapper.java` | 271 | Report → drafts, pure, with every caveat (`BYTES_BASIS`, `NOT_RETAINED`, `SNAPSHOT`, `LIVE_ONLY`) |
| `HeapDumpAnalysisJobHandler.java` | 206 | The `HEAP_ANALYSIS` handler, its provenance and its summary prose |
| `HeapDumpReport.java` | 170 | The parsed dump: provenance, census, per-type rows, roots |
| `HeapDumpFormat.java` | 110 | Which shape a file is, and the refusal prose for each |

**Backend — changed:** `runtime/RuntimeArtifactType.java` (`HEAP_DUMP` supported and readable; the
`containsProcessMemory` doc now covers three cases; `BENCHMARK_RESULT` is no longer flagged as process
memory), `runtime/RuntimeService.java` (`verifyLooksLike` → `verifyHeapDump` at upload).

**Backend tests — new:** `runtime/HeapDumpAnalysisIntegrationTest.java` (331 lines, 2 tests),
`runtime/heapdump/HeapDumpReaderTest.java` (517, 18), `HeapDumpMeasurementMapperTest.java` (279, 8),
`TestHeapDump.java` (144 — starts a subject, waits for its ready file, runs `jcmd … GC.heap_dump` and
skips with `assumeTrue` when there is no JDK), `HeapDumpSubject.java` (94 — the known population).
**Changed:** `runtime/ThreadDumpAnalysisIntegrationTest.java` (the wrong-artefact test now uploads
non-HPROF bytes as `HEAP_DUMP`), `runtime/RuntimeAnalysisIntegrationTest.java` (the role "stored but
unsupported" moved from `HEAP_DUMP` to `BENCHMARK_RESULT`).

**Frontend — new:** `pages/MemoryPage.tsx`.
**Changed:** `components/AppShell.tsx` (route), `components/destinations.ts` (memory available, with an
honest description — the old one promised "retention analysis"), `pages/CapabilityPage.tsx` (the now-dead
memory plan removed), `pages/PerformanceLabPage.tsx` (`heapdump` namespace, `HEAP_ANALYSIS` job type,
heap provenance rows, the neutral dump-timestamp label, and the two descriptions that listed only two
readers), `lib/types.ts` (`RuntimeProvenance`: `environment.formatVersion` / `identifierSize` /
`identifierSizeBasis`, `dump.fileBytes` / `fileBytesBasis` / `strings` / `jvmName`, the `heap` block and
`produced.retainedSizesComputed`).

**Scripts:** `scripts/smoke-test.ps1` — a heap-dump section (three refusals by name, a real 179 MB dump
of the backend under test taken with `jcmd GC.heap_dump`, deduplication, the census arithmetic, the
`TOP_N` disclosure, the basis on every byte row, the root kinds summing to the roots, and the
cross-reader assertions), the Phase 3 "stored but unsupported" checks moved to `BENCHMARK_RESULT`, and
the header comment corrected. **No migration was added.** The ad-hoc script used while the reader was
being built is not checked in: its 22 checks are a subset of this section, which now runs on every
smoke run against a dump of the backend itself.

**Documentation:** `docs/adr/0010-heap-dump-evidence.md`, this report, `README.md`,
`docs/architecture.md` (the heap-dump read path), `docs/security.md` §9 (what a heap dump reveals and
what is stored from it — only the census and a type name per row), and seven screenshots:
`memory-analysis.png`, `memory-census.png`, `memory-types-bytes.png`, `memory-types-objects.png`,
`memory-roots.png`, `memory-empty.png`, `performance-lab-heap-dump.png`.

## 7. Verification actually run

| Check | Result |
| --- | --- |
| `.\mvnw.cmd -B test` (backend) | **128 tests, 0 failures, 0 errors, 0 skipped** across 22 test classes (100 in Phase 4) |
| `npm.cmd run typecheck` | clean |
| `npm.cmd run build` | 117 modules, **349.15 kB** JS (102.60 kB gzip) |
| `scripts\smoke-test.ps1` against the live backend | **257 checks, 0 failures** — including a real 178.6 MB heap dump of the backend, taken, uploaded, deduplicated, analysed and read back |
| Live upload + analysis, three real dumps | 61 measurements each, no migration and no schema change; the 179 MB dump of this backend read in two streaming passes |
| **Matched pair**: `jcmd GC.class_histogram` and `GC.heap_dump` of the same JVM, a second apart | `byte[]`, `java.lang.String` and `HeapProbe$Node` object counts **identical** (4 375, 3 172, 500); recorded bytes smaller than the estimate by the header and padding; the dump's census 18 501 objects against the pre-dump histogram's 19 403 |
| Live refusals: histogram, gzip, thread dump | `422 UNSUPPORTED_INPUT`, each naming the command that produces a readable file |
| Live summary after three readers in one repository | Points at the newest run (the heap dump) and reports its families, while the JFR and thread-dump runs keep their own measurements |
| Screenshots | 7, from the running UI against the live backend — `memory-roots.png` re-taken after the footer gained the live-only sentence, so no screenshot shows wording the build no longer renders |

The 18 reader tests take a **real** dump of the test JVM with `jcmd` (skipping with an `Assumptions`
failure only when no JDK is present), so the assertions run against HotSpot's actual record layout
rather than against a checked-in fixture that can age — and, as §4 shows, that is not a formality here.

## 8. Next phase

**Benchmark import (JMH)**, then AI investigation, which is the last destination without a backend:

1. `BENCHMARK_IMPORT`: read a JMH JSON result, store it as measurements on the same contract, and give
   the Performance Lab a regression view — which is the first place in the product where two runs of
   the same thing are compared, and therefore the first place a *delta* has to be attributable.
2. **`ARTIFACT_RETENTION`**, before or with it: heap dumps are now the largest thing this product stores,
   the setting is bound and ignored, and "delete the repository" is the only reclamation path.
3. **AI investigation with citation validation**, last, because it depends on everything above: a model
   may propose a hypothesis, but every citation it writes is checked against the index (and, for a
   runtime claim, against a stored measurement) before the operator sees it.
4. Still open from earlier phases and unchanged: SpotBugs on compiled classes, `PostgreSQL`, and
   `docker compose up --build`.
