# RepoPilot — Architecture

RepoPilot is a local-first, open-source AI code-intelligence and performance-analysis
platform for Java codebases. It indexes a repository into a symbol graph, runs
deterministic analyzers over it (static analysis, JFR/heap/thread-dump analysis), and
optionally asks a local model to help interpret the evidence — while never letting the
model's output masquerade as a measurement.

This document describes how the running system is put together and why. The decisions
that shaped it are recorded individually under [`docs/adr/`](./adr).

---

## 1. Goals and non-goals

**Goals**

- Works out of the box on one machine: one command for Docker, or one command locally
  with no external services.
- Every claim is traceable to an artefact: a file and line, a symbol, a profiler sample,
  or a benchmark run.
- Read-only with respect to the analysed repository. RepoPilot never builds, mutates, or
  executes the target's code.
- Honest about what it could not do: coverage, unresolved references and analyzer
  limitations are first-class data, not omissions.

**Non-goals**

- Multi-tenant or multi-user operation. The auth model is a single operator token
  (ADR 0005).
- Horizontal scaling. The design is one process against one database.
- Requiring a cloud model or any paid API (ADR 0002).

---

## 2. Runtime topology

### Local (default)

```
┌────────────────────────────┐        ┌──────────────────────────────┐
│  Browser (React SPA)       │        │  RepoPilot (Spring Boot)     │
│  http://localhost:5173     │──HTTP─▶│  WebFlux API on 127.0.0.1:8080│
└────────────────────────────┘ bearer └──────────────┬───────────────┘
                                                     │ JDBC
                                        ┌────────────▼───────────────┐
                                        │ H2 file DB (local profile) │
                                        └────────────────────────────┘
                                                     │ HTTP (optional)
                                        ┌────────────▼───────────────┐
                                        │ Ollama on 127.0.0.1:11434  │
                                        └────────────────────────────┘
```

- Spring profile `local` (default). H2 file database under the data directory. No
  external services; Ollama is optional and its absence is a supported state.
- The API binds loopback by default.

### Deployment / Docker Compose

- Spring profile `postgres`. The same application against PostgreSQL. See
  `docker-compose.yml` and ADR 0004 for why one migration set targets both.

Both topologies run the *same* code; only the datasource and profile differ.

---

## 3. Module map

One deployable, organised by feature package (ADR 0001):

| Package | Responsibility |
| --- | --- |
| `dev.repopilot.workspace` | Workspaces and the default workspace bootstrap. |
| `dev.repopilot.repository` | Repository registration, sandbox validation, file enumeration, revision identity. |
| `dev.repopilot.codeindex` | Parse Java, extract symbols/references/mappings, persist the index, resolve edges, answer code-intelligence queries. |
| `dev.repopilot.analysis` | Static analysis: analyzers as pure functions over handed-in sources, the run/story of findings, citation verification, and the finding queries. |
| `dev.repopilot.runtime` | Runtime evidence: artefact ingestion and content-addressed storage, the four readers (JFR, thread dumps, heap dumps, benchmark results), measurement mapping, their job handlers, retention, and the runtime query views. |
| `dev.repopilot.ai` | Provider abstraction and registry: Ollama over its wire protocol, an optional OpenAI-compatible provider, and the deterministic extractor used for offline verification. |
| `dev.repopilot.investigation` | Investigation lifecycle and evidence handling: deterministic retrieval, prompt assembly, answer parsing, citation verification, grounding, and the investigation query views. |
| `dev.repopilot.jobs` | Durable job lifecycle, dispatch, cancellation, retries, recovery. |
| `dev.repopilot.security` | Bearer-token authentication and the token service. |
| `dev.repopilot.api` | HTTP layer. The only boundary that talks to the outside. |
| `dev.repopilot.config` | Property binding, storage initialisation, startup tasks. |
| `dev.repopilot.shared` | Cross-cutting primitives: persistence base classes, error model, reactive bridge. |

Boundary rules (ADR 0001): no JPA associations across modules (foreign keys are raw
`UUID` columns); cross-module reads go through a service or a purpose-built query
interface.

---

## 4. Request lifecycle

The API is WebFlux and persistence is blocking JPA (ADR 0003). The two are joined by
one bridge, `dev.repopilot.shared.reactive.Blocking`:

```
HTTP request
   │  Netty event-loop thread
   ▼
@RestController  ── Blocking.deferOffload(() -> blockingServiceCall())
   │  hand-off
   ▼
boundedElastic scheduler thread
   │
   ├─ @Transactional service method (short, DB-only)
   └─ JPA repository
```

Rules:

- Any handler that touches the database, the filesystem, or a CPU-bound analyzer goes
  through `Blocking.*`. Nothing blocking runs on an event loop.
- Long work is never executed on the request path. The handler *submits a job* and
  returns `202 Accepted` with the job id. See §5.

---

## 5. Job lifecycle

The database is the queue; there is no in-memory queue that could be lost on restart
and no unbounded in-memory structure.

```
            submit                claim                handler.execute
 (POST) ─────────────▶ QUEUED ─────────────▶ RUNNING ──────────────────▶ SUCCEEDED
                        │  ▲                   │  │                        │
              cancel ───┘  │                   │  └─ transient error ──▶ FAILED
                            │                   │        + attempts left    │
                            │                   │        └──▶ QUEUED (backoff)
                            │                   └─ cancel/abort ─────────▶ CANCELLED
                            └─ idempotency key returns the existing job
```

Properties that make this correct rather than merely present:

- **Durability.** A submitted job is a committed row before the HTTP response is sent
  (and the id is generated before serialization — see `AbstractAuditedEntity`).
- **Single execution.** Claiming is a conditional `QUEUED → RUNNING` transition in its
  own short transaction, so two dispatchers cannot both run a job.
- **Bounded.** A fixed worker pool claims work only when a slot is free; backpressure is
  the absence of claiming.
- **Cancellable.** A durable `cancel_requested` flag is observed at
  `JobContext.checkpoint()` calls, which sit *outside* transactions (ADR 0006).
- **Recoverable.** On startup, jobs left `RUNNING` by an unclean shutdown are reconciled:
  idempotent types are re-queued, non-idempotent types are failed for a human to decide.
- **Honest about unavailability.** A job type with no registered handler is rejected at
  submission with a clear error rather than silently doing nothing.

`REPOSITORY_INDEX` and `STATIC_ANALYSIS` are implemented in the current increment; the
registry reports the supported set at startup and the API refuses anything else. A rejected
submission says so, instead of queueing work that can never run.

---

## 6. Indexing pipeline

`RepositoryIndexer` runs a deliberately **two-pass** pipeline. It is documented as a
trade-off: **bounded memory over doubled local I/O**.

```
Pass 1 — fingerprint
  enumerate (sandbox + exclusions) ─▶ for each file: read + SHA-256 ─▶ content fingerprint
                                        (no parsing, no persistence of file bodies)

  create/reuse repository_revision (content:<fingerprint>)   ← must exist before derived rows (FKs)
  delete derived rows for that revision                      ← idempotency: a retry converges

Pass 2 — parse and persist (short batches, no DB transaction held while parsing)
  read + parse (JavaParser) ─▶ batch of parsed results
      └─ TransactionTemplate: write source_file + symbols + mappings + edges (one batch)
  resolve edges against the revision's symbol index (paged)
  completeIndexing(...)
```

Why two passes: the revision identity is derived from the *content* of the whole file set,
and derived rows carry a foreign key to the revision. The revision row must therefore
exist before any derived row, which means the file set must be read once to compute its
fingerprint before anything can be written. Reading twice is the cost of not holding an
entire repository in memory; on a local disk the second read is cheap and cache-warm.

**Parsing is lenient and honest.** JavaParser runs without a symbol solver, so type
resolution is genuinely partial. The parser is also a *recovering* parser: a file with
syntax errors still yields a tree, is stored with `PARSED_WITH_PROBLEMS`, and records its
problems. It is counted in `coverage.problemFiles`, never silently as `parsed`, and never
dropped. See `ParseStatus` and `CoverageView`.

**Only Java goes to the Java parser.** Files in languages RepoPilot records but cannot yet
analyse (`.properties`, `.yml`) are read, hashed and stored with
`ParseStatus.RECORDED` — no symbols, no references, and a status that says exactly that. They
are *not* run through JavaParser, because doing so produced a Java syntax verdict about a
file that is not Java: every `application.properties` in a repository came back as "parsed
with problems", which claimed a parse that never happened. Recorded is not parsed, and the
coverage view counts the two separately.

### What a scan refuses to read, and why it says so

`SourceFileEnumerator` decides what it is willing to read and records the reason for
everything it refuses, in a `ScanManifest`:

| Reason | Meaning |
| --- | --- |
| `SECRET_BEARING` | A credential file, excluded by name **before any read**. This is a security boundary, not an optimisation: the content never enters memory, the database or a prompt. |
| `VERSION_CONTROL`, `BUILD_OUTPUT`, `DEPENDENCY_CACHE`, `IDE_METADATA` | Directory trees that are never walked. |
| `UNSUPPORTED_LANGUAGE`, `GENERATED`, `BINARY`, `SIZE_LIMIT`, `REPOSITORY_LIMIT`, `UNREADABLE` | Files inspected and deliberately skipped. |
| `SYMLINK` | A symbolic link, never followed. Symlinked **directories** are counted as pruned directories, not as skipped files: their contents were never seen, and reporting a file count for them would be inventing one. |

The manifest's counts are persisted on the revision (`V2__revision_scan_accounting.sql`), not
just logged. Rows can only describe files that were read, so a scan that skipped three
credential files used to report `excludedFiles: 0` — the most misleading possible answer. The
coverage view now states two separate partitions, and they answer different questions:

- **What was read:** `totalFiles = parsedFiles + problemFiles + failedFiles + recordedFiles`.
- **What was inspected:** `enumeratedFiles = totalFiles + excludedFiles`, where
  `excludedFiles` comes from the manifest and carries an `excludedByReason` breakdown
  (`SECRET_BEARING=3,UNSUPPORTED_LANGUAGE=2`).
- **What was never walked:** `prunedDirectories`, reported outside both partitions.

`compiledClassesPresent` is recorded for the same reason: "SpotBugs was not applicable" is a
claim about the revision, so the precondition for bytecode analysis is stored with the
revision rather than asserted by the analyzer that skipped it.

**Every reference carries a resolution.** There is no "resolved" boolean; there is an
`EdgeResolution`:

| Value | Meaning |
| --- | --- |
| `RESOLVED` | An exact qualified-name match against a type declared in this revision, or an exact same-package match verified against that revision's declared types. |
| `INFERRED` | A unique simple-name match (target id recorded, but unprovable without a classpath), or an ambiguous match with **no** target id — the ambiguity is preserved, never broken by an arbitrary tie-break. |
| `EXTERNAL` | No match in this revision: a JDK or third-party type, or a Kafka topic / REST path, which is not a symbol at all. |
| `UNRESOLVED` | Not yet attempted. Edges are inserted unresolved and classified once, after every symbol exists, so the result cannot depend on file order. |

Two invariants hold in the resolver, which is the only writer of these columns:

- Generics and array suffixes are stripped for lookup (`List<Order>` indexes as `List`) but a
  name is never *invented*: `List<String>` does not become `com.example.List` or
  `java.util.ArrayList`. The same-package rule only returns a target the revision actually
  declares.
- `dependency_edge.target_symbol_id` stays **updatable**. It was briefly `updatable = false`,
  which silently discarded every resolved target id: the resolver runs after insert, so the
  column must accept a later write. `/symbols/{id}/references` returned nothing while the
  coverage counts looked healthy.

Consumers must treat `INFERRED` as weaker evidence than `RESOLVED`, and `EXTERNAL` as "not
part of this repository". Even `RESOLVED` means "proven from the source text and this
revision's own declarations", not "proven against a real classpath": RepoPilot does not
compile the repository, and the same-package rule assumes the revision is compilable.

### The read path, and the one endpoint that had to be bounded

Queries are short read transactions over the revision's rows, bridged from the reactive
layer with `Blocking.deferOffload(...)`. Most of them return a `PageResponse` and are bounded
permanently by paging.

The dependency graph is the exception, and it is worth stating because an unbounded read
almost shipped. Cycles and fan-out make a graph awkward to page through, so
`GET /repositories/{id}/graph` originally returned **every** edge of the selected types. On
the generated corpus that was 100 801 edges: **22 MiB of JSON in 50 seconds** for a single
request, while the UI displayed the first 500 of them. The screen looked broken on exactly
the repositories where a dependency graph is worth having, and nothing in the response said
that anything had been omitted.

It now returns a `GraphView`:

| Field | Meaning |
| --- | --- |
| `items` | At most `limit` edges (default 1 000, hard cap 20 000), ordered deterministically by file, line and edge id. The id tie-break is required for pagination: two edges on the same line otherwise have no defined order, so successive pages could repeat or skip one. |
| `totalEdges` | How many edges matched the filter in the revision. |
| `limit` | The effective limit, after the cap. |
| `truncated` | True when `totalEdges > items.size()`. |

The UI renders the slice and says so: what the counts describe, how many edges matched, and
that a narrower edge-type selection will show a different part of the graph. A view that
cannot distinguish "the whole graph" from "the first page of it" is a misleading view, not a
fast one.

The default edge types are also narrower than the index on purpose — `EXTENDS`, `IMPLEMENTS`,
`TYPE_REFERENCE`, `INSTANTIATES`, `CALLS`. Annotation, thrown-type, injection and messaging
edges are indexed and listed per symbol, but drawing them turns a dependency graph into a
hairball. The page states that scope rather than leaving the difference to be discovered by
counting rows.

### The Code Universe: a bounded projection

The Code Universe is a second, deliberately different read over the same edges. A paged
edge list answers "what does this symbol reference"; a graph is only useful when the
neighbouring structure is present, so `GET /repositories/{id}/universe` returns nodes and
edges together, inside one budget:

| Field | Meaning |
| --- | --- |
| `revisionId` | The revision the projection was read from. Findings and evidence overlays are only applied when this matches their revision. |
| `nodes` | Indexed symbols touched by the returned edges, with kind, qualified name, package, path, line range, external flag and degree. |
| `edges` | At most `edgeLimit` edges, ordered deterministically. An edge to an external target that was aggregated out of the node set is not returned. |
| `totalSymbols` | Indexed symbols in the revision. External aggregate nodes are not counted. |
| `totalEdges` | Every edge that matched, before the cap. |
| `edgeLimit` | The effective cap: 1,400 by default, 6,000 at most. The current UI asks for 2,400. |
| `truncated` | True when the response is a slice rather than the whole edge set. |

External references are grouped by target name (the 220 most-referenced names, ids
prefixed `external:`). They are names, not source locations. A node is never emitted
without at least one edge in the response, so the picture has no decorative nodes.

The browser draws this on a Canvas 2D surface driven by `d3-force`, with viewport culling,
zoom-scaled labels, a minimap and a keyboard-navigable search and inspector. React owns
the controls and panels, not one element per symbol. Kind, package, edge type, resolution,
external and findings-only are hard filters; a finding-severity selection is a lens that
dims rather than removes, because "show me critical" and "remove everything else" are
different questions.

Findings and investigation evidence are joined client-side and guarded by revision. A
finding overlay whose run was recorded against another revision is withheld, and an
evidence trail from another revision is replaced with an explanation. The server does not
invent a severity for a node, and the graph does not present the slice as the repository.
The full decision is recorded in [ADR 0014](adr/0014-bounded-code-universe.md).

---

## 7. Evidence model

Everything RepoPilot shows is one of four kinds, and the kind is stored, not inferred at
render time:

| Category | Where it comes from | Trust |
| --- | --- | --- |
| **Observation** | A confirmed fact read from the repository: a file exists, a symbol is declared, an endpoint is mapped. | Highest |
| **Static finding** | An analyzer conclusion about code (PMD, SpotBugs, custom checks). | High, with a named rule and location |
| **Runtime measurement** | Data from a JFR recording, a thread dump, a heap dump, or a benchmark run: a quantity observed while a process ran, or read out of a file it wrote, with its unit, its reduction and its sample count. | High, as a measurement of one run in one environment |
| **AI hypothesis** | Model-generated interpretation. | Lowest; must cite validated evidence |

The `finding` table stores `evidence_category` as a mandatory column, so an AI hypothesis
cannot be rendered as a measurement by accident. Measurements do not go in that table at all:
a measurement has no severity, and putting one in a row with a `severity` column would require
a threshold policy nobody has written ([ADR 0008](adr/0008-runtime-evidence.md)).

**Citation validation is a backend responsibility.** When the model cites a file and line,
the backend re-checks that the file exists in the revision and that the line is in range
before the citation is stored or displayed. The model never gets to assert a location the
index does not confirm. For static findings this is implemented today and applies to every
finding: the runner compares the reported line range against the line count of the text that
was analysed and stores `SOURCE_CONFIRMED`, `LOCATION_STALE` or `NO_LOCATION_CLAIMED`
(`CitationVerifier`). A stale citation is kept and displayed, because an out-of-range line is
a defect in the rule and suppressing it would hide the defect.

### How a static finding is produced

```text
POST /repositories/{id}/analyze
  │  validation: repository is indexed, no job already active for it
  ▼
job row (STATIC_ANALYSIS, QUEUED)              ← short transaction, then released
  │  JobDispatcher claims it
  ▼
StaticAnalysisJobHandler → AnalysisRunner
  │  1. resolve the revision to analyse (the newest indexed revision)
  │  2. analysis_run row: RUNNING, analyzer versions recorded
  │  3. list source_file rows for the revision
  │  4. for each batch of 200 files:
  │       re-read each file through the sandbox, hash it, reconcile with the index
  │       hand the batch to every analyzer: List<AnalysisSource> → AnalysisBatch
  │       persist findings for that batch (short transaction)
  │       check cancellation between batches; a batch is the commit granularity
  │  5. stamp framework-owned fields: evidence category, verification status, fingerprint
  │  6. analysis_run → SUCCEEDED | PARTIAL | FAILED, with a summary and notes
  ▼
GET /repositories/{id}/findings            ← filtered, paged, run-scoped
GET /repositories/{id}/findings/summary    ← counts and the run header
```

Five properties of this flow are load-bearing:

1. **Analyzers are pure functions of the files they are handed.** They do not read the disk,
   query the database, or consult the clock. `SourceAnalyzer.analyze(List<AnalysisSource>)`
   returns findings keyed by path; the runner owns batching, persistence and identity. The
   full contract is [ADR 0007](adr/0007-static-analysis-contract.md).
2. **Reads follow the sandbox.** Source text is re-read through the same sandboxed path
   resolution as indexing, and files the scanner excluded (credential-bearing files) are not
   in the list to begin with. PMD is never handed a path to open: content is passed in as
   `TextFile.forCharSeq(...)`.
3. **The framework owns the honest half of each finding.** Severity ranking, evidence
   category, analyzer id, run id and verification status are set by the runner, never by an
   analyzer. An analyzer declares a rule, a location, prose, and what it cannot see —
   `limitations` is mandatory and non-blank.
4. **Findings are append-only per run, and the run is the unit of comparison.** A re-run
   writes a second `analysis_run`; reads are scoped by `analysis_run_id`, and the summary
   resolves the current run for a revision. Re-analysing never erases the previous result.
5. **`PARTIAL` means the result is incomplete, and is said so.** A readable file that was not
   analysed, or an analyzer that failed, makes the run `PARTIAL`. A language no analyzer
   supports does not: it is reported as a skipped-language count next to the totals, because
   it is a fact about coverage rather than a failure of the run.

### How a runtime measurement is produced

```text
POST /repositories/{id}/runtime/artifacts          ← multipart, streamed to a scratch file
  │  1. resolve the artefact type, or infer it from the extension
  │  2. stream the part to <data-dir>/tmp, hashing as it goes
  │     (bounded above by spring.webflux.multipart.max-disk-usage-per-part, which stops an
  │      oversized stream while it is still arriving; a failure here deletes the scratch file)
  │  3. check the landed size against repopilot.analysis.max-artifact-size-bytes → 413 with the
  │     exact size and the exact limit; refuse a file without the JFR header ('FLR'), by name,
  │     before any JDK object exists
  │  4. atomically move it to <data-dir>/artifacts/<repoId>/<hash[0..2]>/<sha256>.<ext>
  │     (identical bytes already stored → the first artefact is returned, nothing is written)
  ▼
runtime_artifact row: type, original filename, hash, size, supported?, unsupported reason
  ▼
POST /repositories/{id}/runtime/artifacts/{artifactId}/analyze
  │  an unsupported type is refused here — 422, before any run row exists
  ▼
job row (JFR_ANALYSIS, QUEUED)                    ← short transaction, then released
  ▼
JfrAnalysisJobHandler → JfrReader → JfrMeasurementMapper
  │  1. analysis_run row: RUNNING, reader version recorded
  │  2. read the recording with the JDK's Flight Recorder API, in process
  │  3. map the parsed report to MeasurementDrafts — pure, no database
  │  4. persist measurements with run and artefact ids (short transaction)
  │  5. analysis_run → SUCCEEDED | FAILED, with a summary and the provenance JSON
  ▼
GET /repositories/{id}/runtime/measurements   ← filtered by run, metric prefix, kind
GET /repositories/{id}/runtime/summary        ← run header, counts by kind and family, provenance
```

Four properties of this flow are load-bearing:

1. **The reader is the only thing that touches `jdk.jfr`.** `JfrReader` produces a plain
   `JfrRecordingReport`; `JfrMeasurementMapper` is a pure function from that report to drafts.
   The mapping from bytes to numbers is therefore testable without a database, and a recording
   generated in a test yields assertable values.
2. **Nothing about the run is stored as a verdict.** There is no threshold column, no severity,
   and no percentile: the transformations available are `COUNT`, `SUM`, `MEAN` and `MAX`. The
   run summary states in prose that no threshold was applied and no severity assigned, and the
   provenance records `thresholdsApplied: false`.
3. **Every measurement carries its context, and the context is what makes it readable.** Unit,
   kind, reduction, sample count and scope are columns, not decoration; the metric's own caveats
   (`thresholdGated`, the basis of a figure, a frame's share of samples) travel in
   `attributes_json` and the UI renders them next to the value.
4. **Nothing credential-bearing is read.** Command-line arguments, system properties and
   environment variables are in the file and are not read; per-event settings are not attributed
   because JDK 21's `ActiveSetting` cannot say which event type a setting configured. A guessed
   sampling period would make every count derived from it wrong.
5. **The bytes are owned by the repository that uploaded them, and a refusal leaves nothing.**
   A stored artefact is reachable only through its content-addressed path, and deleting a
   repository removes its directory after the rows are gone — filesystem work outside the
   transaction, per [ADR 0006](adr/0006-transaction-boundaries.md). A startup sweep then removes
   any artefact directory whose repository is not in the database, so a deletion interrupted
   between the two steps is still completed. Neither path is a retention policy: nothing prunes
   by age, and the documentation says so.

### How a thread dump is read

The same run lifecycle, a different reader and a different set of judgements. The path is
deliberately the same shape, because the second reader is what made the shape explicit:

```text
POST /repositories/{id}/runtime/artifacts          ← same endpoint, same content-addressed store
  │  the format check happens here, at upload: a file must look like a plain-text dump
  │  (`Full thread dump …`) or a thin Thread.dump_to_file shape that is refused by name
  ▼
POST /repositories/{id}/runtime/artifacts/{artifactId}/analyze
  ▼
job row (THREAD_DUMP_ANALYSIS, QUEUED)
  ▼
AbstractRuntimeAnalysisHandler.execute()           ← the lifecycle, in one place
  │  1. repository id → artefact → type → supported?, or refuse before any run exists
  │  2. analysis_run row: RUNNING, reader version ('thread-dump-reader=1.0.0')
  │  3. ThreadDumpAnalysisJobHandler.analyse(path, …)
  │       ThreadDumpReader    → ThreadDumpReport   (streaming; checkpointed for cancellation)
  │       ThreadDumpMeasurementMapper → List<MeasurementDraft>   (pure function)
  │  4. persist the drafts in one batch, with the run and artefact ids
  │  5. analysis_run → SUCCEEDED | FAILED | CANCELLED, with the summary and provenance
  ▼
GET /repositories/{id}/runtime/measurements?metric=threaddump&runId=…    ← the Concurrency Inspector
```

What is specific to a dump, and why:

1. **The format is decided before the run exists.** A thin `Thread.dump_to_file` file is refused
   with `422`, naming the command that wrote it and the command that produces a readable dump. There
   is no run row, so there is no "failed run" for a file this build was never going to read.
2. **The reader reports, and the mapper judges.** `ThreadDumpReader` knows HotSpot's wording
   (`java.lang.Thread.State`, `- locked`, `- waiting to lock`, `Found N deadlock.`,
   `JNI global refs:`); `ThreadDumpMeasurementMapper` decides what those words are worth. Every row
   carries a `basis` (where the number came from) and, where one is needed, a `caveat` — the
   `RUNNABLE` row names the native-call case explicitly.
3. **The deadlock verdict is the JVM's.** The count is the number on the dump's own
   `Found N deadlock.` line; the cycles are connected components of the edges the file printed. No
   cycle search of RepoPilot's own, and no severity.
4. **The stacks stay in the artefact.** The provenance records the environment, the dump's own
   timestamp (as text — the JVM writes local time and no zone), and counts. Thread names appear only
   in the deadlock cycle's own measurement, where naming them is the point.
5. **One instant, never a rate.** No trend, no comparison with another dump, no per-thread CPU. The
   Concurrency Inspector says so on every render rather than in a tooltip.

### How a heap dump is read

The third reader, and the same lifecycle a third time — which is the payoff for having extracted it:

```text
POST /repositories/{id}/runtime/artifacts          ← same endpoint, same content-addressed store
  │  the format check happens here, at upload: the file must begin with the HPROF header
  │  ('JAVA PROFILE 1.0.1'/'1.0.2'). A gzip stream, a class histogram, a recording and a
  │  thread dump are each refused by name, with the command that produces a readable file.
  ▼
POST /repositories/{id}/runtime/artifacts/{artifactId}/analyze
  ▼
job row (HEAP_ANALYSIS, QUEUED)
  ▼
AbstractRuntimeAnalysisHandler.execute()           ← unchanged: the lifecycle, in one place
  │  1. repository id → artefact → type → supported?, or refuse before any run exists
  │  2. analysis_run row: RUNNING, reader version ('heap-dump-reader=1.0.0')
  │  3. HeapDumpAnalysisJobHandler.analyse(path, …)
  │       HeapDumpReader    → HeapDumpReport      ← two streaming passes, checkpointed
  │       HeapDumpMeasurementMapper → List<MeasurementDraft>   (pure function)
  │  4. persist the drafts in one batch, with the run and artefact ids
  │  5. analysis_run → SUCCEEDED | FAILED | CANCELLED, with the summary and provenance
  ▼
GET /repositories/{id}/runtime/measurements?metric=heapdump&runId=…      ← the Memory screen
```

What is specific to a heap dump, and why:

1. **The reader makes two passes over the file.** Pass one walks the top-level records and keeps the
   class table (`LOAD_CLASS` → object id, name string id). Pass two walks the heap records *and* keeps
   the text of only the strings pass one asked for. A single pass would have to hold the whole string
   table — 68 673 records in a 10 MB dump — to resolve names that most of it does not need. A name
   that never arrives is counted as an unnamed object rather than invented.
2. **The record sizes come from the file, not from a specification.** `INSTANCE_DUMP` states how many
   bytes follow it and that count already includes inherited fields, so the reader resolves no field
   layout and no superclass chain to advance. Where the format is documented inconsistently —
   `ROOT_THREAD_OBJECT` writes eight bytes after the identifier, not the four some descriptions give —
   the width was settled by probing HotSpot and is commented in the reader. **An unknown record tag or
   an unknown field type code is a hard refusal**: a guessed width produces a walk that stays inside
   the file and returns numbers nothing downstream can check.
3. **A record that names a type is a scope, and the type name is the only application data stored.**
   The bytes of the census are what the dump wrote for each object, with no header and no metadata, so
   every byte row carries its `basis` and the sentence that it is not a retained size. There is no
   dominator tree, so there is no retained size, no leak verdict and no severity — the mapper is where
   that is decided, once.
4. **The tables are cut at twenty and the cut travels with them.** `types.bytes.observed` is how many
   types the dump held, `types.bytes.reported` how many are listed, and the share on each row is a
   share of the whole census — stated in the row's own attributes, not only in the page's copy.
5. **A compressed dump is refused, not decompressed.** A 2.6 MB gzip file expanded to 10 MB in the
   test case; the ratio is the point. Decompressing inside a job would let a file that passed the
   upload's size limit hand a worker an unbounded amount of work, so the refusal names the one-line
   fix instead.

### How a benchmark result is read

The fourth reader, and the first artefact that RepoPilot did **not** observe. The lifecycle is
identical; what changes is the claim each row makes:

```text
POST /repositories/{id}/runtime/artifacts          ← same endpoint, same content-addressed store
  │  the format check is deliberately weak: JSON has no header, so the claim available at
  │  upload is "first non-space byte is '['" over the first 4 KiB, plus the 64 MiB bound.
  │  CSV, text and gzip are refused by name with '-rf json -rff results.json'.
  ▼
POST /repositories/{id}/runtime/artifacts/{artifactId}/analyze
  ▼
job row (BENCHMARK_IMPORT, QUEUED)
  ▼
AbstractRuntimeAnalysisHandler.execute()
  │  1. repository id → artefact → type → supported?, or refuse before any run exists
  │  2. analysis_run row: RUNNING, reader version ('benchmark-reader=1.0.0')
  │  3. BenchmarkImportJobHandler.analyse(path, …)
  │       BenchmarkReader          → BenchmarkResultReport   ← structural check, names the entry
  │       BenchmarkMeasurementMapper → List<MeasurementDraft> (pure function)
  │  4. persist in a batch, checkpointing per entry
  │  5. analysis_run → SUCCEEDED | FAILED | CANCELLED, with the summary and provenance
  ▼
GET /repositories/{id}/runtime/measurements?metric=benchmark&runId=…      ← the Performance Lab
```

What is specific to a benchmark result:

1. **Every value is `AS_REPORTED`.** The harness computed the score, the error, the confidence bounds
   and the percentiles; the reader repeats them with the harness's own unit. A score labelled `MEAN`
   would claim RepoPilot had averaged something it never saw, so `MeasurementAggregation` gained the
   value that means "not mine".
2. **`"NaN"` is a string, and a missing error is not a zero.** A single-fork rate benchmark writes
   `"scoreError": "NaN"`; storing `0.0` would be the strongest precision claim a measurement can
   make. No row is written, and the provenance counts the entries that lack an estimate.
3. **The one check is internal to the file.** Where `rawData` exists, the stated score is compared
   with the mean of the per-iteration values (flattened across forks) and the outcome becomes the
   row's `check` attribute. Sample-mode entries record a histogram and say `not checkable`. The check
   stores no second number and corrects nothing.
4. **The command line is counted, never carried.** `jvmArgs` is where a `-D` credential lives; the
   JVM binary path is not read at all. Declared parameters are stored, because they identify the
   measurement rather than describe the machine.
5. **There is no comparison, so there is no regression view.** `produced.comparisonsMade` is `false`,
   the scoring rows carry a `note` saying one run on one machine says nothing about faster or slower,
   and no threshold or severity exists to assign.
6. **An array that is not a result file fails in the job.** Upload cannot tell one array from another;
   the reader can, and its message names the entry and the field it lacked rather than storing an
   empty run.

### Retention: what a purge removes

Retention is the only deletion path bounded by age, and it is bounded on purpose to the bytes:

```text
GET  /repositories/{id}/runtime/retention[?days=N]     ← computed live; reading the plan is the dry run
POST /repositories/{id}/runtime/retention/purge        ← {"olderThanDays": N}, a job
  ▼
job row (ARTIFACT_RETENTION, QUEUED)                   ← excluded from the one-job-per-repository guard
  ▼
ArtifactRetentionJobHandler
  │  1. resolve the window (parameter; ≤ 0 is refused before anything runs)
  │  2. list candidates: artefacts past the cutoff whose file is still present
  │  3. delete each file, then mark its row purged_at       ← this order, so a failure is visible
  │  4. summary: files purged, bytes reclaimed, measurements kept
  ▼
no analysis_run: nothing was read
```

1. **Rows and measurements survive; only the file goes.** `runtime_artifact.purged_at` marks the row,
   the run and every measurement stay, and the API reports `purgedAt` so the UI can render "file
   purged" instead of an error.
2. **Zero means off.** With `repopilot.storage.retention-days = 0` the scheduler queues nothing and
   the plan names no candidate; an explicit purge with no window is a `400` that says it will not
   treat a missing window as everything.
3. **Re-upload restores.** Storage is content-addressed and unique per `(repositoryId, contentHash)`,
   so uploading the same bytes writes the file again and clears the mark. Analysing a purged artefact
   before that is a `409`, not a job that fails on a missing file.
4. **The scheduler only queues.** One hourly pass, initial delay 120 s, idempotency key
   `retention:<repoId>:<yyyy-MM-dd>`, per-repository `try`/`catch`, and the window written into the
   job's parameters so the pass records the policy it ran under.

### How an investigation is answered

An investigation is the only path where a model writes prose, so it is also the only path that
checks a sentence. The pipeline is fixed; the provider is the only swappable part:

```text
POST /repositories/{id}/investigations               ← question, optional scope
  ▼
400: fewer than 8 chars, or every token a stop word   ← no row is created
409: no indexed revision
503: no usable provider                                ← before the revision check
  ▼
job row (INVESTIGATION, QUEUED) → InvestigationJobHandler
  │  1. retrieve evidence deterministically: findings (only if the newest
  │     STATIC_ANALYSIS run analysed this revision), symbols, files, repository
  │     summary; max 12 items, ranked, numbered E1…En
  │  2. read excerpts through the sandbox and the recorded revision (24 lines / 1 200
  │     chars); keep SHA-256 + length, hand the text to the provider
  │  3. build a prompt ≤ 24 000 chars (included items only); hash it
  │  4. call the provider outside any transaction (3 min ceiling, 1 500 tokens, JSON mode)
  │  5. parse claims; for each: key in prompt, cites something, cited excerpt re-read
  │     and re-hashed (CONFIRMED / CHANGED / MISSING / NOT_LOCATED), named path:line
  │     covered by its own citation
  │  6. grounded = ≥1 claim ∧ all VERIFIED ∧ no dangling [En]
  ▼
investigation + investigation_evidence + investigation_claim
```

1. **No model call happens without evidence.** If retrieval returns nothing usable — including the
   case where the repository summary is the only candidate — the job fails before the provider is
   called, and the failure says the index had nothing to search.
2. **The answer is stored as written; the verdicts are stored beside it.** `investigation_claim`
   holds the claim text and RepoPilot's verification status in separate columns. Nothing rewrites a
   sentence that failed a check.
3. **Parsed-but-ungrounded is a success.** The job did its work and the answer did not hold up, so
   the investigation is `SUCCEEDED` with `grounded=false` and the per-claim verdicts explain why. A
   provider that answers with non-JSON is also `SUCCEEDED`: its raw text is stored, no claims are
   parsed, and the answer is ungrounded.
4. **Cancellation is decided where it can be honoured.** A `QUEUED` investigation is marked
   `CANCELLED` immediately; a `RUNNING` one stops at the handler's checkpoint; `begin()` refuses a
   cancelled investigation and `complete()` discards late answers, so a cancelled investigation is
   never resurrected.
5. **Nothing about the prompt is stored except its identity.** `prompt_sha256`, `prompt_chars` and
   `context_chars` are kept; the prompt itself is not, because it contains source excerpts.

---

## 8. Data model

The schema is forward-looking: it defines the tables every planned capability needs now,
so migrations stay additive. Key tables:

- `workspace` → `repository` → `repository_revision` is the spine. Every derived row
  points at a revision, which is what makes "this finding refers to this code" verifiable.
- `source_file`, `code_symbol`, `dependency_edge`, `java_mapping` are the code index.
- `analysis_job` is the durable queue. `analysis_run` records one analyzer execution — static or
  runtime — with its own `finding_count` and `measurement_count`, so a runtime run cannot read as
  "0 findings, all clear".
- `runtime_artifact` stores an uploaded recording or dump by content hash, together with the
  client's filename (a display label, never a path), its type, whether this build can read it,
  and the provenance JSON of the recording's environment. `V5__artifact_retention.sql` adds
  `purged_at`: the row outlives the file, so a retention pass leaves a visible artefact whose
  measurements are still queryable rather than a hole where a file used to be.
- `runtime_measurement` (`V4__runtime_measurement.sql`) is one measured quantity: `metric_key`,
  `metric_kind`, `aggregation`, `metric_value`, `unit`, `sample_count`, `scope`,
  `attributes_json`, and the run and artefact it came from. It has no severity column, by design.
  The column is `metric_value` rather than `value` because `VALUE` is a reserved word in H2 and
  recent SQL standards.
- `finding` normalises every analyzer's output, with `evidence_category`, the analyzer id, the
  rule id, the citation and its verification status, and a `severity_rank` column
  (`V3__finding_severity_rank.sql`) so ordering by severity is a comparison of a stored number
  rather than of an enum's ordinal. A fingerprint unique per `(analysis_run_id, fingerprint)`
  makes the same rule and location deduplicated within a run, and a re-run a new row set under
  a new run.
- `investigation`, `investigation_evidence` and `investigation_claim` model one answer:
  what was asked, the numbered evidence handed to the model (with the excerpt's hash and
  length, never the excerpt), the prompt's identity, the diagnostics, and one row per
  claim carrying the sentence as written beside RepoPilot's verdict. `V6__investigation_citations.sql`
  adds the key, the excerpt hash, the source status and the claims table;
  `investigation_message` remains unused — a conversation history was never needed, and the
  migration is additive rather than rewritten.
- `recommendation` holds a proposed patch; it is never applied automatically.
- `benchmark_run` is **unused**: benchmark results are stored as `runtime_measurement` rows under
  the `benchmark.` namespace, like every other reader's output, so one measurements table answers
  "what did this run record" regardless of which reader produced it. The table is kept because the
  migration is additive, not because a second path is planned.
- `code_embedding` is optional and off by default; `model_configuration` and `audit_event`
  support provider settings and traceability.

Foreign keys are declared explicitly and cascade on delete so removing a repository
removes everything derived from it.

---

## 9. Observability

- **Health groups.** `liveness` = `livenessState,ping`; `readiness` =
  `readinessState,db,diskSpace,repoPilotWorkers,aiProvider`. The readiness group includes
  the AI provider indicator, which reports `UP` with `usable=false` when no model runtime
  is present — absent is not unhealthy.
- **Metrics.** Micrometer, with Prometheus exposed at `/actuator/prometheus` and the metric
  index at `/actuator/metrics`. Both require the operator authority, which the bearer token
  grants. They are deliberately *not* public: `health` and `info` are the only actuator
  endpoints reachable without a credential, and neither exposes repository data.
  (The authentication filter is scoped to `/api/v1/**` plus these two actuator paths. It was
  originally scoped to `/api/v1/**` alone, which left both endpoints authorised by a rule no
  request could satisfy — every call, token or not, was rejected. Unreachable is not the same
  as protected, and `HttpSecurityBoundaryIntegrationTest` now pins the difference.)
- **Tracing.** OpenTelemetry OTLP export when `OTEL_EXPORTER_OTLP_ENDPOINT` is set.
- **Correlation.** Every request carries a correlation id (`X-Correlation-Id`), echoed in
  responses and in every `ApiErrorResponse`, so a user-visible error can be tied to logs.

---

## 10. Error model

One envelope for every failure (`ApiErrorResponse`): stable `errorCode`, human `message`,
HTTP `status`, `timestamp`, `correlationId`, `path`. Errors carry a code rather than
prose-parsing because clients branch on codes. Domain code raises `RepoPilotException`
with an `ErrorCode`; a global handler maps it, and unexpected exceptions become a generic
`INTERNAL_ERROR` without leaking internals.

---

## 11. Configuration

All configuration is bound to `RepoPilotProperties` (`repopilot.*`) and every field has a
default, so the application starts with no external configuration. Secrets are supplied
through environment variables, never bound from a committed file. See `.env.example` for
the full list. Notably:

- Hard limits are always present (file counts, file/repository sizes, worker counts,
  timeouts, artifact sizes). None are unbounded.
- `repopilot.workspace.roots` is the allow-list the sandbox enforces.
- `repopilot.ai.enabled=false` disables model access entirely, which is what the tests run
  under.

---

## 12. Verification status

What has actually been run against this snapshot, and what has not:

| Area | Status |
| --- | --- |
| Backend test suite | 230 tests, 35 classes, all passing (JUnit 5, H2 for the `local` profile, real Spring contexts) |
| Frontend | `tsc --noEmit` clean under strict settings; 30 Vitest tests for the universe transforms pass; production build succeeds (578 modules, 608.48 kB JS / 187.03 kB gzip, plus 97.76 kB CSS / 18.76 kB gzip) |
| Live end-to-end | Indexing, Code Explorer (including findings and `?path=&line=` deep links), Code Universe (search, kind filters, trail overlay, inspector, minimap), Performance Lab, Concurrency Inspector, Memory Analysis, AI Investigation (including evidence path into the universe), Analysis History, Settings (including artefact retention) and the command palette exercised against a running backend on Windows |
| Code Universe | **Verified on the benchmark corpus and the quality fixture.** The endpoint returns a stated slice (`309` nodes, `2,400` of `100,801` edges; `290` indexed symbols plus external aggregates) and the UI labels the slice. Search, hard filters, severity lens, selection, inspector source excerpt, minimap, evidence-trail overlay and the revision guard were exercised in the browser. `fileByPath` resolves a file outside the first 500-row page, returns `404` for an unknown path and is asserted in `RepositoryIndexingIntegrationTest` |
| Smoke test | 355 checks against a live server with the deterministic extractor, 0 failures — and 351 with a real Ollama model, 0 failures (readiness, registration, indexing, code intelligence, static analysis, the finding evidence contract, runtime evidence from four readers including all of their refusals, a real thread dump and a real 179 MB heap dump taken from the backend under test, a recorded two-fork JMH result, the retention plan and the refusals around it, 44 investigation checks including an answer read back claim by claim and a question cancelled while still queued, and — when the data directory is known — six checks that inspect the artefact store on disk). The investigation section asserts the contract for any provider and keeps "every claim is VERIFIED" as an extractor-only assertion |
| Benchmark corpus | 3 002 Java files indexed (16 205 symbols, 105 001 references) and analysed in 118 s for 600 findings; spans recorded in [`corpus/RESULTS.md`](../corpus/RESULTS.md) |
| Static analysis | **Verified.** Built-in rules and PMD run as a durable job; every stored finding passed its citation check; filters, tallies, run scoping and append-only re-runs asserted in the smoke test and the integration test |
| Runtime evidence (JFR) | **Verified.** A live recording was uploaded, stored by hash, read by a durable job and queried back: 101 measurements with units, kinds, reductions and provenance. Refusals (bad header, unreadable type, pathological filename, both upload limits), deduplication, reproducibility and the removal of a deleted repository's bytes are asserted in the smoke test (69 new checks) and `RuntimeAnalysisIntegrationTest` / `RuntimeUploadLimitIntegrationTest` |
| Runtime evidence (thread dumps) | **Verified.** Two real dumps of live JVMs (taken with `jcmd`, one of them re-encoded as UTF-16 by a Windows shell) were uploaded, stored by hash, read by a durable job and queried back: 11 and 17 measurements with their states, contention and the JVM's own deadlock verdict. The two `Thread.dump_to_file` formats are refused by name at upload, and the cross-reader "newest runtime evidence" query is asserted in the smoke test and `ThreadDumpAnalysisIntegrationTest` |
| Runtime evidence (benchmark results) | **Verified.** Two real JMH runs (one fork and two forks, 14 entries each, three modes, a two-valued parameter) were uploaded, stored by hash, imported by a durable job and queried back: 254 and 277 measurements, every value `AS_REPORTED` with the harness's own unit, the score checked against the 6 recorded per-iteration values where the file has them and `NOT_CHECKABLE` where it does not, `jvmArgs` counted and not stored, no baseline and no comparison. A JSON array that is not a result file uploads and then fails naming the missing field; CSV and text output are refused at upload with the command that produces a readable file. Asserted in `BenchmarkImportIntegrationTest`, three unit-test classes and the smoke test |
| SpotBugs | **Not implemented.** Needs bytecode; `compiledClassesPresent` is recorded per revision so it will report *not applicable* rather than a pass |
| Runtime evidence (heap dumps) | **Verified.** Two real HPROF dumps (a purpose-built 10 MB subject and a 179 MB `GC.heap_dump` of this backend taken by the smoke test) were uploaded, stored by hash, read by a durable job and queried back: 61 measurements per dump, with the census, the `TOP_N = 20` type tables, the GC-root kinds and the largest single object. Object counts for `byte[]` and `java.lang.String` agree exactly with `jcmd GC.class_histogram`; the byte figures deliberately do not, and every byte row carries its basis. A class histogram, a gzip stream and a thread dump are refused by name at upload. The census arithmetic, the basis on every byte row and the cross-reader "newest runtime evidence" query are asserted in the smoke test (73 checks) and `HeapDumpAnalysisIntegrationTest` |
| AI investigation | **Verified, with a real model and with the deterministic extractor.** A question retrieves a numbered evidence set deterministically (up to 12 items), the provider's claims are checked against the index, and every citation is re-read and re-hashed. Extractor run on the quality fixture: 12 of 12 claims `VERIFIED`, grounded, prompt hashed at 8 690 characters. Real-model run (Ollama 0.40.7, Qwen2.5-Coder 3B, CPU-only): grounded answers with every written claim verified, 54–79 s per investigation, and the 351-check live smoke test green. The ungrounded path — a key never sent, a claim citing nothing, a `path:line` its evidence does not cover and a dangling `[En]` — is driven by a scripted provider in `InvestigationCitationIntegrationTest`; the no-evidence and no-provider paths fail without calling a model. A question that names nothing is refused `400` before any row exists, and a queued investigation can be cancelled. **The shipped `qwen2.5-coder:7b` default was not run**: a 3B answer already used 54–79 s of the 120 s timeout on this hardware |
| Age-based artefact retention | **Verified.** A pass removes the stored file of an artefact past the window and marks the row; the run and every measurement are kept. The plan is computed live (candidates, bytes, measurements kept), a zero-day window is refused with `400 VALIDATION_FAILED`, a window that nothing is past reports "Nothing to purge", re-uploading the same bytes clears `purged_at`, and analysing a purged artefact is a `409`. The scheduler queues one pass per repository per day and skips repositories with active jobs. Asserted in `ArtifactRetentionIntegrationTest`, `RetentionSchedulerTest` and the smoke test |
| PostgreSQL | **Not verified.** Migrations are written to be portable and are exercised on H2 in PostgreSQL-compatibility mode only |
| `docker compose up --build` | **Not verified.** There is no Docker daemon in the environment this snapshot was built in; the images and Compose file are written and reviewed but never executed |

Claims in this document are limited to the rows that say "verified".
