# Phase report — static analysis, findings, and the evidence contract

**Date:** 2026-10-18
**Scope of this increment:** the first analysis engine. RepoPilot can now run static analysis
on an indexed revision as a durable job, store findings attributed to the rule that produced
them, verify each citation against the text that was analysed, and show the result — with its
limits — in the Code Explorer. No cloud service and no API key are involved: PMD runs in
process and the built-in rules are part of the application.

---

## 1. What works now

| Capability | State | Evidence |
| --- | --- | --- |
| Eight built-in rules (`RP001`–`RP008`) over JavaParser | Working | Each fires exactly once on `fixtures/quality-demo`; asserted in `StaticAnalysisIntegrationTest` and in the smoke test |
| PMD 7.28.0 integration, in-process, no classpath | Working | 600 findings on the 3 002-file corpus; `pmd/EmptyCatchBlock` and `pmd/AvoidCatchingGenericException` fire on the fixture |
| Analysis as a durable job | Working | `POST /repositories/{id}/analyze` → `JobView`; polled to `SUCCEEDED` live, three repositories |
| Citation verification | Working | `CitationVerifierTest` (7 cases); all 615 findings stored by the runs in section 4 are `SOURCE_CONFIRMED`, and the smoke test re-checks the invariant on every finding it reads |
| Findings query: filters, tallies, run scoping | Working | Smoke test asserts severity, analyzer and path filters and run-scoped totals |
| Append-only re-runs | Working | Smoke test asserts a second run gets a new id, reports the same count, and leaves the first run readable |
| Findings in the Code Explorer | Working | Findings tab with filters, per-file count, click-through to the citation; screenshots |
| SpotBugs | Not started | Needs bytecode; `compiledClassesPresent` is already recorded per revision |
| JFR / heap / thread-dump ingest, benchmark view, AI investigation | Not started | Job types declared; submitting one returns `UNSUPPORTED_INPUT` |

Two engines are reported separately rather than merged. On the fixture, PMD and the built-in
rules both fire on the same empty catch and on the same `catch (Throwable)`, and PMD also
reports the credential field as an unused private field while `RP005` reports it as a
credential — two different concerns on one line. The overlap is deliberate: agreement between
independent engines is evidence, and suppressing either copy would destroy it.

## 2. Files

**Backend** — a new `dev.repopilot.analysis` package, plus one controller and one migration:

- `analysis/` — `SourceAnalyzer` (batch contract), `AnalysisSource`, `StaticFinding`,
  `AnalysisBatch`, `AnalysisOutcome`, `EvidenceCategory`, `FindingSeverity`,
  `FindingConfidence`, `FindingCategory`, `VerificationStatus`, `CitationVerifier`,
  `AnalysisRunner`, `StaticAnalysisJobHandler`, `AnalysisRun`, `AnalysisFinding`.
- `analysis/checks/` — `CustomRule` and `CustomCheckAnalyzer`: the eight rules, one JavaParser
  parse per file, with the deliberate-exception name convention (`ignored`, `expected`,
  `suppressed*`).
- `analysis/pmd/` — `PmdAnalyzer`: PMD's bundled `quickstart.xml`, content fed in via
  `TextFile.forCharSeq`, priority mapped to severity, violations keyed back by `FileId`.
- `analysis/query/` — `AnalysisQueryService`, `FindingView`, `FindingSummaryView`,
  `FindingFilter`, and three tally records.
- `api/v1/AnalysisController` — `POST /{repositoryId}/analyze`,
  `GET /{repositoryId}/findings`, `GET /{repositoryId}/findings/summary`.
- `V3__finding_severity_rank.sql` — `finding.severity_rank` plus its index, so ordering by
  severity is a column comparison rather than an enum's ordinal.

**Frontend** — findings surfaced in the Code Explorer: `types.ts` (the analysis unions and
view types), `api.ts` (`analyze`, `findings`, `findingSummary`), `queries.ts`
(`useFindingSummary`, `useFindings`, `useStartAnalysis`), `format.ts` (`severityTone`,
`verificationTone`, `formatFindingLocation`), a new `components/FindingsPanel.tsx`, the
findings tab in `pages/CodeExplorerPage.tsx` with a per-file findings chip, and CSS for the
findings list and the finding detail definition list.

**Tests** — `analysis/StaticAnalysisIntegrationTest` (2 cases) and
`analysis/CitationVerifierTest` (7 cases).

**Fixture, scripts, docs** — `fixtures/quality-demo/` (three files, one clean, plus its
README), `scripts/measure-analysis.ps1`, the analysis half of `scripts/smoke-test.ps1`, and
[ADR 0007](../adr/0007-static-analysis-contract.md).

## 3. Tests run, and what they actually did

| Command | Result |
| --- | --- |
| `backend> .\mvnw.cmd -B test` | **44 tests, 0 failures, 0 errors, BUILD SUCCESS** |
| `frontend> npm run typecheck` | Clean (`tsc --noEmit`, strict) |
| `frontend> npm run build` | 114 modules, 300.32 kB JS (91.71 kB gzip), 29.99 kB CSS |
| `scripts\smoke-test.ps1` against a live backend | **All checks passed** — 61 checks, 0 failures, of which 36 are new analysis checks (and the 25 that predate this phase still pass) |
| `scripts\measure-analysis.ps1` against a live backend | Ran three times: sample repository, quality fixture, 3 002-file corpus |

`StaticAnalysisIntegrationTest` is the load-bearing one. It indexes a fixture containing one
instance of each rule, runs the analysis job, and asserts that all eight rules fire, that
PMD's `EmptyCatchBlock` appears alongside `RP001`, that every finding is `SOURCE_CONFIRMED`
with a non-blank `limitations`, that the severity tallies reconcile with the page, that the
secret literal appears in no finding text, that `application.properties` is counted as one
skipped-language file without making the run partial, and that a second run appends without
erasing the first. A second case asserts that a clean source tree produces zero findings with
both analyzers still recorded — a clean result is a result about the rules, not a silent skip.

## 4. Live verification

Three real runs, through the real API, on this machine:

| Repository | Files analysed | Findings | Span | Peak working set |
| --- | --- | --- | --- | --- |
| `examples/sample-java` | 8 | 1 (`pmd/UncommentedEmptyConstructor`, medium) | 6.41 s | 554 MiB |
| `fixtures/quality-demo` | 3 | 14 (10 built-in, 4 PMD) | 3.28 s | 676 MiB (warm JVM) |
| 3 002-file benchmark corpus | 3 002 (+1 skipped: no analyzer) | 600 (all `pmd/ClassNamingConventions`) | 118.17 s | 770 MiB |

Fixture detail, because it is the run where the expected answer is knowable in advance: 2 high
/ 9 medium / 3 low; security 2, correctness 5, maintainability 3, style 2, concurrency 1,
performance 1; `custom-checks` 10, `pmd` 4; every finding `SOURCE_CONFIRMED`; and the file
that is clean has no findings.

Two things were checked rather than assumed:

- **The round number is real.** 600 findings across 3 002 files is exactly the kind of number
  that turns out to be a truncation. Counting the corpus independently — 600 enum
  declarations whose names are not `[A-Z][a-zA-Z0-9]*` — reproduces the count exactly, so
  PMD reported all of them and nothing was dropped.
- **The credential value is not stored.** The fixture's fake secret is asserted absent from
  the raw findings JSON by the smoke test, while the field name it was assigned to *is*
  present. This is the check that keeps `RP005` from becoming the vulnerability it reports.

The corpus run also reported a note rather than a failure: PMD could not parse the deliberately
broken `_SyntaxBreak.java` (`ParseException` at line 4). The run is `SUCCEEDED` and the note is
stored in the run summary, because the authoritative statement that the file does not parse is
the index's own `PARSED_WITH_PROBLEMS` status — the analysis engine repeating it is a coverage
fact, not a run-level failure.

## 5. Measurements

Method and figures in [`corpus/RESULTS.md`](../../corpus/RESULTS.md); the analysis half is
reproducible with `scripts/measure-analysis.ps1`. Headline comparison on the 3 002-file
corpus:

| Metric | Value |
| --- | --- |
| Analysis span | 118.17 s (≈25 files/s), against 158–190 s for indexing the same corpus |
| Findings | 600, all from PMD's ruleset; the built-in rules reported none |
| Peak process working set | 770.5 MiB (OS-observed) |
| Peak private bytes | 814.5 MiB |
| Average CPU | 8.9 % of 12 logical cores |
| Database size | 76.7 MiB (73 MiB after indexing) |

Two honest notes on this table. The corpus is a *scale* fixture: it was generated for indexing
volume, so its 600 findings are one rule firing on enum naming, and it says nothing about how
the rules behave on real code — that is what `fixtures/quality-demo` is for. And the fixture's
676 MiB peak is not comparable to the sample repository's 554 MiB: the JVM had already loaded
PMD, and a working-set figure without its starting state is not a growth measurement.

## 6. Defects found and fixed

1. **`Thread.sleep` in a loop was never reported.** `RP006` guarded on `getArguments().isEmpty()`,
   which excluded the call it was looking for. Found by writing the fixture and noticing the
   rule did not fire on it; the guard was unnecessary and is gone.

2. **A lambda ended the loop-ancestor walk.** Walking outward from a call site to find an
   enclosing loop stopped at `LambdaExpr`, because a lambda's body is not executed in the
   enclosing loop's iterations. That reasoning was wrong for the shape being matched, and it
   silently exempted `list.forEach(x -> { while (…) { sleep } })`-style code. Removed.

3. **The symbol link was a placeholder.** A stub index looked up a finding's symbol by
   position in a list. Replaced with `symbolAt(List<CodeSymbol>, line)`, which takes the
   innermost symbol whose range contains the line and returns null when none does — so a
   finding outside any declaration is linked to nothing rather than to a neighbour.

4. **The verification logic was unreachable from a test.** It was private to the runner, so
   the only way to exercise `LOCATION_STALE` was a full run. Extracted to
   `CitationVerifier.verify(startLine, endLine, lineCount)` and covered directly by 7 cases,
   including the boundary where `endLine` is past the end of a short file.

5. **Two assertions that could not fail.** One compared a collection to itself
   (`hasSameSizeAs`), and one called a placeholder that asserted nothing. Both were deleted
   rather than repaired: a test that cannot fail is worse than no test, because it is counted.

6. **The findings table did not fit the panel it lives in.** The findings list is rendered in
   the Code Explorer's right-hand pane, about 490 px wide. A six-column table pushed the
   citation — the column a reader checks first — off the right edge, where it was reachable
   only by scrolling inside a scrolling pane. Found by looking at the running application, not
   by reading the component. Replaced with a self-contained row per finding.

7. **A citation was printed with the wrong case.** The finding detail rendered the location
   inside a badge, and badges uppercase their contents, so
   `src/main/java/com/example/quality/LegacyConfig.java:15` was displayed as
   `SRC/MAIN/JAVA/...`. Paths are case-sensitive; a citation that is not the path that was
   analysed is a false citation, however cosmetic it looks. The location is no longer a badge,
   and the comment in the component says why.

8. **The smoke test's leak assertion never ran.** `Invoke-WebRequest` without
   `-UseBasicParsing` threw a `NullReferenceException` on this PowerShell version, which
   aborted the script at exactly the assertion that checks a credential value is not returned.
   A harness defect rather than a product one, but it would have hidden a real one.

The overlap between `RP001` and PMD's `EmptyCatchBlock` (and between `RP008` and
`AvoidCatchingGenericException`) is *not* a defect and was not "fixed": it is the cross-check
described in section 1.

## 7. Not done, and stated as such

- **SpotBugs is not implemented.** It needs bytecode, and RepoPilot does not compile
  repositories behind your back. `compiledClassesPresent` is recorded per revision, so it
  will report *not applicable* with the precondition attached rather than a pass — the
  behaviour the evidence model promises.
- **Only one language is analysed.** Java files reach the analyzers; any other readable file is
  counted as skipped with the reason, and skipping a language no analyzer supports does not
  make a run `PARTIAL`. Only a file that should have been analysed but was not, or an analyzer
  that failed, makes a run partial.
- **The built-in rules are name- and shape-based and say so.** No symbol solver, no type
  resolution, and every finding's `limitations` states that boundary. `RP005` in particular is
  name-based: a differently named variable holding a credential is not reported.
- **The corpus is a scale fixture, not a quality one.** One rule accounts for all 600 of its
  findings.
- **`docker compose up --build` still has never been executed** (no daemon in this
  environment) and **PostgreSQL has still never been run**; migrations are exercised on H2 in
  PostgreSQL-compatibility mode.
- **Jobs JFR ingest, heap and thread-dump analysis, benchmark import, AI investigation,
  recommendations, embedding indexing and artifact retention** have no handlers.
- **Known test-only noise, not a defect:** the suite logs
  `JobDispatcher — job dispatch pass failed` with `Table "analysis_job" not found` when a
  cached Spring context's scheduler keeps running after JUnit deletes that test's `@TempDir`
  H2 file. It does not affect any assertion and predates this phase.
- **The working tree is uncommitted.** Nothing has been committed in this repository yet.

## 8. Next phase

1. **JFR ingest** — the `JFR_ANALYSIS` handler over an uploaded recording, producing runtime
   measurements that carry the recording's own provenance (host, JVM, duration, sampling
   parameters) instead of being presented as measurements of the code.
2. **AI investigation with backend citation validation** — the flow that makes the evidence
   model complete: the model proposes, the backend checks every file and line against the
   revision, and anything unverified is displayed as unverified. `CitationVerifier` already
   exists and is unit-tested; what is missing is the provider call and the prompt assembly.
3. **SpotBugs, gated on `compiledClassesPresent`** — so "not applicable" is a reported
   precondition rather than a silent pass.
4. **Benchmark import and the regression view**, built on the corpus and the measurement
   scripts that already exist, with the same rule that a measurement arrives with its method.
5. **Postgres and Compose verification** on a host that has both, then promote the README's
   unverified rows to verified.
