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
| `dev.repopilot.analysis` *(planned)* | Static analysis, runtime artefact analysis (JFR/heap/thread), and their normalized findings. No code yet. |
| `dev.repopilot.ai` | Provider abstraction and registry (Ollama default). |
| `dev.repopilot.investigation` *(planned)* | AI investigation lifecycle and evidence handling (`investigation*` tables exist; no code yet). |
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

Only `REPOSITORY_INDEX` is implemented in the current increment; the registry reports the
supported set and the API refuses the rest.

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

---

## 7. Evidence model

Everything RepoPilot shows is one of four kinds, and the kind is stored, not inferred at
render time:

| Category | Where it comes from | Trust |
| --- | --- | --- |
| **Observation** | A confirmed fact read from the repository: a file exists, a symbol is declared, an endpoint is mapped. | Highest |
| **Static finding** | An analyzer conclusion about code (PMD, SpotBugs, custom checks). | High, with a named rule and location |
| **Runtime measurement** | Data from a JFR recording, heap dump, thread dump, or benchmark run. | High, as a measurement of a specific run/environment |
| **AI hypothesis** | Model-generated interpretation. | Lowest; must cite validated evidence |

The `finding` table stores `evidence_category` as a mandatory column, so an AI hypothesis
cannot be rendered as a measurement by accident.

**Citation validation is a backend responsibility.** When the model cites a file and line,
the backend re-checks that the file exists in the revision and that the line is in range
before the citation is stored or displayed. The model never gets to assert a location the
index does not confirm.

**Implementation status.** Only the first category is produced today: observations derived
from the code index. The `finding`, `investigation*`, `runtime_artifact` and
`benchmark_run` tables are created by `V1__initial_schema.sql` because an additive schema is
cheaper than a migration later, but **no code writes them yet**, and the AI citation check
above is a design rule that has not been exercised against a live model. Those screens
report the capability as unavailable rather than rendering empty tables as if they were
results. This is stated here so the schema's shape is not mistaken for delivered
functionality.

---

## 8. Data model

The schema is forward-looking: it defines the tables every planned capability needs now,
so migrations stay additive. Key tables:

- `workspace` → `repository` → `repository_revision` is the spine. Every derived row
  points at a revision, which is what makes "this finding refers to this code" verifiable.
- `source_file`, `code_symbol`, `dependency_edge`, `java_mapping` are the code index.
- `analysis_job` is the durable queue. `analysis_run` records one analyzer execution;
  `runtime_artifact` stores an uploaded JFR/dump/benchmark file by reference.
- `finding` normalises every analyzer's output, with `evidence_category`.
- `investigation`, `investigation_evidence`, `investigation_message` model the AI
  investigation and the exact evidence handed to or cited by the model.
- `recommendation` holds a proposed patch; it is never applied automatically.
- `benchmark_run` stores performance results as measurements with environment metadata.
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
| Backend test suite | 35 tests, all passing (JUnit 5, H2 for the `local` profile, real Spring contexts) |
| Frontend | `tsc --noEmit` clean under strict settings; production build succeeds |
| Live end-to-end | Indexing, Code Explorer, Dependency Graph, Analysis History, Settings and the command palette exercised against a running backend on Windows |
| Benchmark corpus | 3 002 Java files indexed; 16 205 symbols, 105 001 references; spans recorded in [`corpus/RESULTS.md`](../corpus/RESULTS.md) |
| PostgreSQL | **Not verified.** Migrations are written to be portable and are exercised on H2 in PostgreSQL-compatibility mode only |
| `docker compose up --build` | **Not verified.** There is no Docker daemon in the environment this snapshot was built in; the images and Compose file are written and reviewed but never executed |
| Static analysis, JFR/heap/thread-dump ingest, AI investigation | **Not implemented.** Job types are declared; submitting one returns `UNSUPPORTED_INPUT` |

Claims in this document are limited to the rows that say "verified".
