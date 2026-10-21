# Phase report — AI investigation with citation checking

- **Phase:** 7 of 8
- **Date:** 2026-10-21
- **Status:** complete; backend suite, production build and live smoke test green
- **ADRs:** [0013 — AI investigation](../adr/0013-ai-investigation.md)

## 1. What works now

**A question becomes a checked answer, and the check is visible.**

1. Asking a question queues a durable `INVESTIGATION` job. The screen follows it like any other
   job: `QUEUED → RUNNING → SUCCEEDED`, with cancellation and a history of every question asked of
   the repository.
2. Retrieval is deterministic. The question is tokenised (at most 8 tokens, stop words removed) and
   matched against findings, symbols, files and the repository summary. The result is at most **12
   items**, ranked and numbered `E1…En`; the same question against an unchanged revision assembles
   the same set, because nothing is scored by similarity.
3. Findings are used only when the newest `STATIC_ANALYSIS` run analysed the indexed revision.
   Otherwise retrieval skips them and the diagnostics say so, because a finding about another
   revision is not evidence about this one.
4. Each located item contributes an excerpt read through two guards — the sandbox and the revision
   recorded on the row — capped at **24 lines / 1 200 characters**. The prompt is bounded at
   **24 000 characters**, and the builder reports only the items it actually included.
5. The provider answers from that prompt. On the recorded fixture (3 findings, 6 symbols, 2 files
   and the repository summary) that is **12 items, 12 claims, 12 verified**, prompt **8 690
   characters** of which **6 630** were evidence, prompt SHA-256 `af428b…`, `grounded: true`.
6. Every claim is checked four ways: the cited key must have been in the prompt; the claim must cite
   something; the cited excerpt is re-read and re-hashed (`CONFIRMED` / `CHANGED` / `MISSING` /
   `NOT_LOCATED`); and any `path:line` the claim names must be covered by the evidence it cites.
7. The answer is stored exactly as the model wrote it, including a claim that failed. Verdicts live
   in their own rows, so a sentence is never rewritten to match its evidence.
8. `grounded` is an answer-level verdict: at least one claim, all `VERIFIED`, and no `[En]` marker
   pointing at a key that was never sent. One unsupported claim marks the whole answer *not
   grounded* while the claims table still shows which one failed and why.
9. A question that names nothing is refused before a row exists (`400`, "names nothing to search
   for"); no provider is `503` before the revision check; an unindexed repository is `409`.
10. Cancellation returns `202`. A queued investigation is marked `CANCELLED` immediately (a queued
    job may never reach a handler), a running one stops at its checkpoint, and a cancelled
    investigation is never resurrected by a late answer.

**The page says what it is looking at.**

11. The verdict card opens with `SUCCEEDED · GROUNDED · MODEL CONFIDENCE: LOW · 12 EVIDENCE ITEMS`,
    then the grounded banner or the ungrounded one, then an "AI hypothesis — not a measurement"
    notice that repeats the provider's own limitation.
12. Every `[En]` in the answer is a chip: clicking it scrolls the evidence row into view and flashes
    it (verified in a rendered page: `scrollTop` 4 525 → 3 867, flash applied and cleared after
    2.4 s).
13. The claims table shows the claim text, the cited keys as chips, its kind and its verdict; a key
    that was never sent renders as `E99 not in prompt` rather than a link.
14. The evidence table shows each item's label, detail, `path:line`, retrieval method, source check
    and excerpt size — never the excerpt itself.
15. Provenance records the provider, the model, the prompt size and SHA-256, latency and the
    retrieval sentence (`12 item(s) of evidence (3 findings, 6 symbols, 2 files, 1 repository),
    retrieved by name and text matching for findings, empty, catch, order, importer`).

## 2. What was built

### Backend

| File | Role |
| --- | --- |
| `investigation/EvidenceRetriever` | Deterministic retrieval: 5 findings / 6 symbols / 3 files + repository summary, `MAX_EVIDENCE = 12`, detail truncated at 700 chars, the human-readable retrieval summary |
| `investigation/QuestionTokens` | Lower-cases the question, drops stop words, keeps at most 8 tokens; used by retrieval and by the submit-time refusal |
| `investigation/SourceExcerptReader` | Re-reads a recorded line range through `RepoAccess`, `MAX_LINES = 24`, `MAX_CHARS = 1 200`, returns text + SHA-256 + length |
| `investigation/prompt/InvestigationPromptBuilder` | Assembles the prompt under `MAX_PROMPT_CHARS = 24 000` and returns the `Included` list it actually wrote |
| `investigation/answer/InvestigationAnswerParser` | Parses the JSON answer (`answer`, `claims[]`, `limitations`, `confidence`), bounds answer at 20 000 chars, claims at 60 × 4 000; unparseable text is kept as prose with zero claims |
| `investigation/answer/CitationValidator` | The four checks, per-claim verdicts and notes, dangling markers |
| `investigation/answer/SourceVerifier` | Re-reads and re-hashes a cited excerpt; produces the evidence `sourceStatus` |
| `investigation/InvestigationJobHandler` | The `INVESTIGATION` handler: retrieval, excerpts, prompt, provider call outside any transaction (3 min, 1 500 tokens, temperature 0.1, JSON mode), verification, persistence |
| `investigation/InvestigationStore` | Job-facing transitions (`begin`, `complete`, `fail`, `cancel`, `markCancelledIfQueued`) — split out of the service to break a bean cycle |
| `investigation/InvestigationService` | Submit-time rules (length, stop words, provider, revision), cancellation semantics, query delegation |
| `investigation/query/InvestigationQueryService` | Detail and list views; the detail view never exposes excerpt text or its hash |
| `ai/deterministic/DeterministicAiProvider` | Offline extractor: one `OBSERVATION` per evidence item, `LOW` confidence, says no model was called, answers no part of the question that needs a conclusion |
| `api/v1/InvestigationController` | `POST /repositories/{id}/investigations`, `GET …/investigations`, `GET …/{investigationId}`, `POST …/{investigationId}/cancel` |
| `db/migration/V6__investigation_citations.sql` | `prompt_sha256`, `diagnostics_json`; evidence key, excerpt hash/length, source status, verified-at; `investigation_claim` |

Supporting changes: `AiConfiguration`/`AiProviderRegistry` register the deterministic provider only
when selected and expose `unavailableMessage()`; `SourceFileRepository` gained the read used to list
files by path for retrieval.

### Frontend

| File | Change |
| --- | --- |
| `pages/InvestigationPage.tsx` | Provider banner, composer (8-char guard, optional scope), history with polling only while active, verdict banner, `HypothesisNotice`, answer with clickable `[En]` chips, claims table, diagnostics, evidence table, provenance |
| `components/AppShell.tsx` | `case "investigation"` routes to the page |
| `components/destinations.ts` | AI Investigation marked `available: true` — the ninth and last destination |
| `lib/types.ts` | `InvestigationSummary/Detail/Evidence/Claim/Diagnostics/Provenance`, `InvestigationStatus`, `SourceStatus`, `ClaimKind`, `VerificationStatus`, `SubmissionView` |
| `lib/api.ts` | `investigations`, `investigation`, `askInvestigation`, `cancelInvestigation` |
| `hooks/queries.ts` | `useInvestigations`, `useInvestigation` (polls while queued/running), `useAskInvestigation`, `useCancelInvestigation` |
| `styles/app.css` | `.answer-text`, `.citation-chip`, `.evidence-flash`, `.rp-mono` usage in the evidence cell |

## 3. What the system refuses to claim

- **A grounded answer is not a correct answer.** Grounding means every claim cited evidence that was
  in the prompt and still matched on re-read. It says nothing about whether the model's reasoning
  was right, and the UI labels the answer a hypothesis throughout.
- **No semantic search.** Retrieval is name and text matching; there are no embeddings, no similarity
  scores and no "the model found this relevant". A question that describes a subsystem without
  naming anything in it retrieves little, and the diagnostics say so instead of implying the
  repository has nothing there.
- **No individual measurements are retrieved.** Only the newest runtime run's summary is offered as
  evidence; a number without its scope, unit and caveats is exactly what this feature exists to
  avoid quoting.
- **The prompt is not stored.** Only its SHA-256 and length are, and only the hash of each cited
  excerpt — never the excerpt text. A stored prompt would be a log of source code.
- **No claim is rewritten.** A sentence that failed verification stays in the answer, in the model's
  words, with its verdict beside it.
- **No conversation.** Each question is independent; there is no follow-up turn and no memory of a
  previous answer. `investigation_message` exists in the schema and is unused.
- **The deterministic provider is not an AI feature.** It is selected only by explicit
  configuration, says in every answer that no model was called, and would be a defect in a build
  that presented it as a model.

## 4. Defects found and fixed during verification

1. **The application would not start: a bean cycle.** `JobService → JobRegistry → handler →
   InvestigationService → JobService`. The investigation service both queued jobs and recorded their
   transitions, so the job system and the feature depended on each other. Fixed by extracting
   `InvestigationStore`, which owns only the job-facing transitions and depends on the repositories;
   the service keeps the HTTP-facing rules. Caught by the integration test's context load, not by
   reading the code.
2. **The prompt builder reported evidence the model never saw.** The character budget was applied
   while *reporting* the included items rather than while inserting them, so `Included` could name
   items that were cut. "Every claim cites evidence that was in the prompt" would then have been a
   claim about the wrong list. Fixed by building the inclusion list first and using it for both the
   prompt and the report; `InvestigationPromptBuilderTest` pins the agreement.
3. **A cancelled investigation could still be completed.** Cancellation only set a flag that a
   running handler checked at its next checkpoint, so a queued investigation could start after the
   operator cancelled it and finish normally. Fixed: a `QUEUED` investigation is marked `CANCELLED`
   immediately (and a terminal one is a `409`), `begin()` throws `JobAbortedException` for a
   cancelled investigation, and `complete()` discards a late answer rather than resurrecting it.
4. **The retrieval summary had no number in it.** `new StringBuilder(items.size())` sets a *capacity*,
   not text, so the sentence read `" item(s) of evidence (3 findings, …)"`. Found by reading the live
   API while gathering figures for this report, not by a test — and fixed with a count that is
   appended as text, an integration assertion on the exact prefix, and a smoke check against the live
   server.
5. **Optional fields rendered as `null` text or crashed the page.** Jackson omits null fields, so a
   field typed `number | null` in TypeScript arrives `undefined`; `!== null` guards passed and the
   page dereferenced nothing. Fixed across the page (`limitations`, `answer`, `promptSha256`, `scope`,
   badge counts) by using `!= null` and `?? ""`.
6. **The claims table pushed its verdict column off the panel.** Six columns in a 1 060 px panel
   clipped `VERDICT`. Fixed by reducing the table to four columns and moving the cited keys inline
   under each claim, where they are also the thing being checked.
7. **The evidence table clipped the source-check column and wrapped paths one word per line.** The
   location column was narrow enough that `wordBreak: break-all` produced eight-character lines and
   the rightmost column left the card. Fixed by merging the location into the evidence cell, adding
   break opportunities after path separators (`<wbr>`, which adds no characters to the copied text),
   and suppressing the location line for file items whose label is already the path.
8. **The file-item dedupe did not apply for the same null-versus-undefined reason.** The guard
   compared `startLine !== null`; an absent line arrives as `undefined`, so the duplicate path
   stayed. Found in the screenshot after the fix above. Fixed with `!= null`, and the comment in the
   code now names why.
9. **A test counted the wrong rows.** The integration test asserted on `investigations.count()` in a
   shared Spring context, so a second test class's rows made it fail. Fixed by counting within the
   repository under test.
10. **The diagnostics did not account for the prompt budget.** Retrieval reported the items it dropped
    to stay within the 12-item cap, but the 24 000-character prompt budget could drop further items
    silently — while the page tells the reader that the evidence table is the complete record of what
    the model was shown. Found by checking the documentation against the code rather than by a test;
    fixed by adding the omitted count to the retrieval notes.

## 5. Known limitations, stated rather than papered over

- **Ollama is not installed in this environment.** No real model has ever answered here. Every
  verified result in this report came from the deterministic extractor or from the scripted test
  provider; the Ollama client is exercised only by its unit tests.
- **Retrieval is lexical.** No embeddings, no stemming beyond lower-casing, no synonyms. A question
  phrased entirely in domain words that appear nowhere in the index retrieves nothing and the job
  fails with "no indexed evidence matched".
- **Twelve items is a hard cap.** A question that would need more evidence gets a thinner answer, and
  the diagnostics list what was dropped for budget.
- **A grounded answer can still be misleading.** An injected instruction inside a source file can
  shape what the model says about real evidence; the check catches invented citations, not invented
  conclusions. The UI never presents a claim as an observation.
- **The excerpt hash is a point-in-time check.** It proves the cited lines had not changed between
  sending and verification; it is not re-checked later, and a file edited afterwards leaves a
  `CONFIRMED` verdict standing until the investigation is read again (it is not).
- **No conversation history, no follow-up questions, no editing of a question.** Each investigation
  is one question and one answer.
- **`MODEL_TIMEOUT` is three minutes and not configurable** in this build; a slower model on a large
  prompt fails the job rather than waiting.
- **Docker, PostgreSQL and a real model runtime remain unverified here** (see §7).

## 6. Files added or changed

**Added — backend**

```text
src/main/java/dev/repopilot/ai/deterministic/DeterministicAiProvider.java
src/main/java/dev/repopilot/api/v1/InvestigationController.java
src/main/java/dev/repopilot/investigation/ClaimKind.java
src/main/java/dev/repopilot/investigation/ClaimVerification.java
src/main/java/dev/repopilot/investigation/EvidenceItem.java
src/main/java/dev/repopilot/investigation/EvidenceKind.java
src/main/java/dev/repopilot/investigation/EvidenceRetriever.java
src/main/java/dev/repopilot/investigation/Hashing.java
src/main/java/dev/repopilot/investigation/Investigation.java
src/main/java/dev/repopilot/investigation/InvestigationClaim.java
src/main/java/dev/repopilot/investigation/InvestigationClaimRepository.java
src/main/java/dev/repopilot/investigation/InvestigationEvidence.java
src/main/java/dev/repopilot/investigation/InvestigationEvidenceRepository.java
src/main/java/dev/repopilot/investigation/InvestigationJobHandler.java
src/main/java/dev/repopilot/investigation/InvestigationRepository.java
src/main/java/dev/repopilot/investigation/InvestigationService.java
src/main/java/dev/repopilot/investigation/InvestigationStatus.java
src/main/java/dev/repopilot/investigation/InvestigationStore.java
src/main/java/dev/repopilot/investigation/QuestionTokens.java
src/main/java/dev/repopilot/investigation/SourceExcerptReader.java
src/main/java/dev/repopilot/investigation/SourceStatus.java
src/main/java/dev/repopilot/investigation/answer/CitationValidator.java
src/main/java/dev/repopilot/investigation/answer/InvestigationAnswer.java
src/main/java/dev/repopilot/investigation/answer/InvestigationAnswerParser.java
src/main/java/dev/repopilot/investigation/answer/SourceVerifier.java
src/main/java/dev/repopilot/investigation/prompt/InvestigationPrompt.java
src/main/java/dev/repopilot/investigation/prompt/InvestigationPromptBuilder.java
src/main/java/dev/repopilot/investigation/query/InvestigationQueryService.java
src/main/java/dev/repopilot/investigation/query/InvestigationViews.java
src/main/resources/db/migration/V6__investigation_citations.sql
src/test/java/dev/repopilot/investigation/CitationValidatorTest.java
src/test/java/dev/repopilot/investigation/InvestigationAnswerParserTest.java
src/test/java/dev/repopilot/investigation/InvestigationCitationIntegrationTest.java
src/test/java/dev/repopilot/investigation/InvestigationIntegrationTest.java
src/test/java/dev/repopilot/investigation/InvestigationPromptBuilderTest.java
src/test/java/dev/repopilot/investigation/InvestigationProviderUnavailableIntegrationTest.java
src/test/java/dev/repopilot/investigation/QuestionTokensTest.java
src/test/java/dev/repopilot/investigation/ScriptedAiProvider.java
```

**Added — docs and screenshots**

```text
docs/adr/0013-ai-investigation.md
docs/phase-reports/phase-7-ai-investigation.md
docs/screenshots/ai-investigation-ask.png
docs/screenshots/ai-investigation-verdict.png
docs/screenshots/ai-investigation-claims.png
docs/screenshots/ai-investigation-diagnostics.png
docs/screenshots/ai-investigation-evidence.png
docs/screenshots/ai-investigation-provenance.png
```

**Changed**

```text
backend/src/main/java/dev/repopilot/ai/AiConfiguration.java
backend/src/main/java/dev/repopilot/ai/AiProviderRegistry.java
backend/src/main/java/dev/repopilot/codeindex/persistence/SourceFileRepository.java
frontend/src/components/AppShell.tsx
frontend/src/components/destinations.ts
frontend/src/hooks/queries.ts
frontend/src/lib/api.ts
frontend/src/lib/types.ts
frontend/src/styles/app.css
frontend/src/pages/InvestigationPage.tsx
scripts/smoke-test.ps1
.env.example
README.md
docs/architecture.md
docs/security.md
```

**Removed**

```text
docs/screenshots/capability-not-available.png   ← every destination now has a backend
```

## 7. Verification actually run

| Check | Command | Result |
| --- | --- | --- |
| Backend suite | `.\mvnw.cmd -B test` (from `backend/`) | **229 tests in 35 classes, 0 failures, 0 errors**, `BUILD SUCCESS`. Phase 7 added 46: 13 citation, 14 parser, 6 prompt, 6 tokens, 4 integration, 2 citation integration, 1 provider-unavailable |
| Type check | `npx tsc --noEmit` | clean under strict settings |
| Production build | `vite build` | 118 modules, 377.20 kB JS (109.60 kB gzip), no warnings |
| Live smoke test | `scripts/smoke-test.ps1` | **352 checks, 0 failures, 0 skipped** (was 311; the new section is 41 investigation checks, including the retrieval-summary count, the excerpt-text absence, `E\d+` keys all cited, cross-repository `404`, stop-word `400`, cancel `202 → CANCELLED → 409`, and an unauthenticated `401`) |
| Live investigation | `POST …/investigations` on the quality fixture | "Which findings describe the empty catch in OrderImporter?" → job `SUCCEEDED`; **12 evidence / 12 claims / 12 verified**, `grounded: true`, prompt **8 690 chars** (**6 630** evidence), SHA-256 `af428b…`, retrieval **3 findings, 6 symbols, 2 files, 1 repository** |
| Live investigation, smaller set | a second question | **4 evidence / 4 verified**, prompt 3 119 chars (1 081 evidence), `grounded: true` |
| Evidence discipline | live API read | no `excerpt` or `excerptSha256` field in any response; `E10`/`E11` carry a path but `NO_LOCATION` (nothing to re-read), `E12` is the repository summary and is cited |
| Citation chip | headless Chrome, real page | `[E7]` click: `scrollTop` 4 525 → 3 867, row at 447–609 of a 1 000 px viewport, `evidence-flash` applied, cleared after 2.4 s |
| Frontend, live | Vite dev server + running backend | page renders at 1 060 px (single column) and at 1 440 px (two columns); six screenshots captured |
| Refusals | live API | stop-word question → `400 VALIDATION_FAILED` ("names nothing to search for"); cross-repository read → `404`; cancelling a finished investigation → `409`; no bearer token → `401` |

**How the screenshots were taken.** The interactive browser pane's window was not visible in this
session (`browser.screenshot` refuses without one), and a screenshot of a page that is not really
rendered would be a lie. The page was therefore rendered in headless Chrome at a fixed viewport and
captured over the DevTools protocol, with the token seeded into `sessionStorage` exactly as the
sign-in screen stores it; the question at the top of the history was typed into the form and
submitted there. The chip behaviour above was measured in that same rendered page. The temporary
capture script lives outside the repository and is not part of the build.

**Not verified here, and why**

- **`docker compose up --build`** — no Docker daemon in this environment.
- **PostgreSQL** — never executed; `V6` is written to be portable and is exercised on H2 in
  PostgreSQL-compatibility mode only.
- **A real model answering** — Ollama is not installed (`127.0.0.1:11434` refuses connections). The
  ungrounded path is covered by `InvestigationCitationIntegrationTest` with a scripted provider, and
  the whole vertical slice by the deterministic extractor; neither is evidence that a real model
  behaves well.
- **A citation that changes under the answer** (`CHANGED`) — the verdict is decided by
  `SourceVerifier` re-reading and re-hashing the range, and the mapping from that status to a claim
  verdict is covered by `CitationValidatorTest`; the live path has only ever observed `CONFIRMED`
  and `NO_LOCATION`, because nothing edited a file between sending and checking in the runs
  recorded here.

## 8. Next phase

**Phase 8 — recommendations, and the release seams.** The last declared capability with no backend
is `RECOMMENDATION_*`: a proposed patch attached to a finding or an investigation, stored, reviewed
by a human, and never applied automatically. The other half of the phase is the two seams this
environment cannot exercise — a real PostgreSQL and a real Ollama — plus `docker compose up --build`,
so the product's three "not verified here" rows can become verified or be narrowed honestly.
