# Phase report — indexing, evidence discipline, and the console

**Date:** 2026-10-17
**Scope of this increment:** the foundation phases and the first complete vertical slice.
RepoPilot now works end to end on a real repository — register it, index it in a durable
background job, explore the result honestly — on one machine, with no cloud service and no
API key.

---

## 1. What works now

| Capability | State | Evidence |
| --- | --- | --- |
| Workspace + repository registration, sandboxed to allow-listed roots | Working | `RepositorySandboxPathTest` (12 cases), live |
| Durable job queue: lifecycle, bounded workers, cancellation, startup recovery | Working | `EntityIdentityIntegrationTest`, live index runs, Analysis History screen |
| Two-pass repository indexing (fingerprint, then parse and persist in bounded batches) | Working | `RepositoryIndexingIntegrationTest`, 3 002-file corpus |
| Coverage reporting: read vs. deliberately-not-read, per reason | Working | `reportsExcludedFilesFromTheScan`, live API |
| Reference resolution: `RESOLVED` / `INFERRED` / `EXTERNAL` / `UNRESOLVED` | Working | `EdgeResolverNameTest`, `resolvesSamePackageReferences` |
| Code Explorer: files, source with line numbers, symbols, references, API mappings | Working | Live browser, screenshot |
| Dependency Graph: bounded edge view with resolution per edge | Working | `boundsTheGraphView`, live browser, screenshot |
| Analysis History: every job with its outcome summary | Working | Live browser, screenshot |
| Settings: connection, roots, AI status, privacy switches | Working | Live browser, screenshot |
| Bearer-token auth, loopback bind, operator-only actuator | Working | `HttpSecurityBoundaryIntegrationTest` |
| Local AI provider abstraction (Ollama default) | Partial | Health and capability reporting only |
| Static analysis, JFR / heap / thread-dump ingest, benchmark view, AI investigation, embeddings | Not started | Job types declared; submitting one returns `UNSUPPORTED_INPUT`; the four screens say so |

## 2. Files

**Backend** (115 main classes, 7 test classes)

- `dev.repopilot.codeindex` — `parse` (`JavaSourceParser`, `ParsedFile`, `ParseStatus`),
  `resolve` (`EdgeResolver`, `EdgeResolution`), `model`, `persistence`
  (`SourceFile`, `CodeSymbol`, `DependencyEdge`, `JavaMapping` + repositories), `index`
  (`RepositoryIndexer`, `IndexOutcome`, `RepositoryIndexJobHandler`), `query`
  (`CodeIndexQueryService`, `CoverageView`, `GraphView`, view records).
- `dev.repopilot.repository` — registration, revisions, sandbox, `ScanAccounting`, and
  `scan` (`SourceFileEnumerator`, `ScanManifest`, `FileExclusionPolicy`, `SkipReason`,
  `DetectedLanguage`).
- `dev.repopilot.jobs`, `workspace`, `ai`, `security`, `observability`, `shared`, `api.v1`.
- Migrations: `V1__initial_schema.sql`, `V2__revision_scan_accounting.sql`.

**Frontend** (39 source files) — the nine destinations, the shell, the command palette, the
typed API client, and the formatting layer that turns server vocabulary into sentences
(`formatCoverageSentence`, `formatSkipReasons`).

**Docs and scripts** — `README.md`, `docs/architecture.md`, `docs/security.md`, six ADRs,
11 screenshots, `LICENSE`, `scripts/generate-corpus.ps1`, `scripts/measure-index.ps1`,
`scripts/smoke-test.ps1`, `corpus/README.md`, `corpus/RESULTS.md`, `docker-compose.yml`,
both `Dockerfile`s and `frontend/nginx.conf`.

## 3. Tests run, and what they actually did

| Command | Result |
| --- | --- |
| `backend> .\mvnw.cmd -B test` | **35 tests, 0 failures, 0 errors, BUILD SUCCESS** |
| `frontend> npm run typecheck` | Clean (`tsc --noEmit`, strict, `noUncheckedIndexedAccess`, `exactOptionalPropertyTypes`) |
| `frontend> npm run build` | 113 modules, 287.82 kB JS (88.57 kB gzip), 28.67 kB CSS |
| `scripts\smoke-test.ps1` against a live backend | **All checks passed** — register, index, coverage, files, content, symbols, mappings, graph, revisions, job counters |

Test classes: `RepositoryIndexingIntegrationTest` (5), `HttpSecurityBoundaryIntegrationTest`
(3), `JavaSourceParserTest` (6), `EdgeResolverNameTest` (4), `RepositorySandboxPathTest` (12),
`EntityIdentityIntegrationTest` (4), `MappingTypeParityTest` (1).

## 4. Live verification

- Indexed `examples/sample-java` (8 files) and the generated 3 002-file corpus through the
  real API; read coverage, symbols, references, mappings, graph and job history back.
- Walked all nine destinations in the browser; no console errors on any of them.
- Injected a token and confirmed the connection gate, the coverage sentence, and the bounded
  graph banner render from live data.
- Captured 11 screenshots of the running product.

## 5. Measurements

Full method and figures in [`corpus/RESULTS.md`](../corpus/RESULTS.md). Headline numbers on a
3 002-file corpus, three runs, same host:

| Metric | Value |
| --- | --- |
| Symbols / references / mappings | 16 205 / 105 001 / 2 400 |
| Index span | 158 s, 172 s, 190 s (≈16–19 files/s) |
| Peak process working set | 897–946 MiB (OS-observed, includes the JVM baseline) |
| Average CPU | 8.7–10.1 % of 12 logical cores |
| Database size | 73 MiB |

The spread is reported rather than the best number: a 20 % run-to-run difference on identical
input means a single measurement is not evidence of anything.

## 6. Defects found and fixed

Each of these was found by verifying a claim, not by reading code for style. They are the
reason this phase took as long as it did.

1. **Every resolved reference target was discarded.**
   `dependency_edge.target_symbol_id` was mapped `updatable = false`, but the resolver is its
   only writer and runs *after* the row is inserted. `/symbols/{id}/references` returned
   nothing while the coverage counts looked healthy. Fixed by making the column updatable;
   pinned by `resolvesSamePackageReferences`.

2. **`excludedFiles` was always 0 when files were excluded.**
   The count was derived from `source_file` rows with a status the indexer never writes — and
   an excluded file never becomes a row, so no row count could ever be right. A scan that
   refused three credential files reported "0 excluded". Fixed by persisting the scan
   manifest's counts on the revision (`V2__revision_scan_accounting.sql`), with per-reason
   breakdowns, and by asserting the two coverage partitions in tests.

3. **A symlinked directory was counted as a skipped file.**
   Its contents were never seen, so naming a file count for it invented one. Directories are
   now counted as pruned directories, which keeps `inspected = read + skipped` exact.

4. **Re-indexing untouched content kept stale exclusions.**
   Revision identity is the fingerprint of the *analysed* set, so adding a credential file to
   an otherwise unchanged repository reused the revision — and with it the old accounting.
   The accounting is now refreshed on reuse.

5. **`/actuator/metrics` and `/actuator/prometheus` were unreachable by anyone.**
   The authorisation rule required the operator authority, but the authentication filter was
   scoped to `/api/v1/**`, so no request could ever carry it: every call, valid token or not,
   returned 401. Unreachable is not the same as protected, and the configuration claimed
   otherwise. Fixed by extending the filter matcher, pinned by
   `HttpSecurityBoundaryIntegrationTest`.

6. **Non-Java files were parsed as Java.**
   `.properties` and `.yml` files were handed to JavaParser, so every `application.properties`
   in a repository was reported as `PARSED_WITH_PROBLEMS` — a Java syntax verdict about a file
   that is not Java, and a claim that a parse happened. Added `ParseStatus.RECORDED`, skipped
   by the coverage partition and shown as "recorded" in the UI. On the corpus this changed
   `problemFiles` from 2 to 1.

7. **The dependency graph was unbounded.**
   `GET /repositories/{id}/graph` returned every edge: **100 801 edges, 21.95 MiB of JSON,
   49 687 ms** for one request, while the UI rendered the first 500 without saying anything
   was missing. Now a bounded `GraphView` (1 000 of 100 801, 223 KiB, ≈1.1 s) that reports
   `totalEdges` and `truncated`, and a page that states the scope of what it draws. Pinned by
   `boundsTheGraphView`.

8. **The smoke test asserted a partition that no longer existed.**
   It summed `excludedFiles` into the read partition, which is exactly the confusion defect 2
   was about. Replaced with two explicit assertions, one per partition.

9. **New entities were merged instead of persisted** (found earlier, still load-bearing).
   A primitive `getVersion()` returned `0` for an unpersisted entity, so `isNew()` was false
   and Hibernate `merge`d it — producing a different managed instance with a null id. Both
   entity bases now implement `Persistable<UUID>`.

## 7. Not done, and stated as such

- **`docker compose up --build` has never been executed.** There is no Docker daemon in this
  environment. The images, nginx config and Compose file are written and reviewed; the
  frontend production build is verified. The README says so at the top of the quickstart.
- **PostgreSQL has never been run.** Migrations are written to be portable (no `jsonb`, no
  arrays, no partial indexes) and are exercised on H2 in PostgreSQL-compatibility mode only.
- **No static analysis, no JFR/heap/thread-dump ingest, no benchmark view, no AI
  investigation.** The job types, the schema and the evidence rules exist; the handlers do
  not. The four destinations render an explicit capability page.
- **No embeddings.** pgvector was deliberately not started without a demonstrated need.
- **The working tree is uncommitted.** Nothing has been committed in this repository yet.

## 8. Next phase

1. **Static analysis** — custom checks first, then PMD (in-process), then SpotBugs gated on
   `compiledClassesPresent` so "not applicable" is a reported precondition rather than a
   silent pass. Every finding carries its rule id and location.
2. **JFR ingest** — `JFR_ANALYSIS` handler over an uploaded recording, producing runtime
   measurements with the recording's own provenance (host, JVM, duration) attached.
3. **AI investigation with backend citation validation** — the flow that makes the evidence
   model real: the model proposes, the backend verifies every file and line against the
   revision, and unverified claims are shown as unverified.
4. **Benchmark import** and the regression view, built on the corpus and the measurement
   script that already exist.
5. **Postgres and Compose verification** on a host that has both, then promote those rows in
   the README from "unverified" to "verified".
