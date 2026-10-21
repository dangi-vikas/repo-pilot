# ADR 0013 — AI investigation: an answer is a set of checked citations, not a paragraph

- **Status:** Accepted
- **Date:** 2026-10-21
- **Deciders:** RepoPilot maintainers

## Context

Every other feature in RepoPilot answers a question with something the server can point at: an index
row, a finding with a fingerprint, a measurement with a unit and a run. The investigation feature is
the one place where a language model writes prose, and prose is the one artefact this product cannot
verify the way it verifies a number. A model that says "the retry loop in `PaymentService` never
terminates" may be right, may be repeating a comment it read, or may be inventing a method that does
not exist — and all three read the same on screen.

There were three possible products here:

1. **A chat box over the repository.** Send whatever fits in a context window, show whatever comes
   back. Cheapest to build, and the one thing it cannot do is tell the operator when it is wrong.
2. **A summariser with a warning label.** Generate prose, print "AI-generated, verify before use", and
   leave the checking to the reader. Honest about the risk, useless at reducing it.
3. **An evidence pipeline with a writer attached.** Choose the evidence deterministically, number it,
   show it, require the model to cite it, and then verify the citations against the filesystem before
   anything is displayed.

This ADR records the third option, because the product's whole claim is evidence discipline and an AI
feature that quietly exempts itself from it would be the weakest thing in the build.

The constraint that shaped the rest of the design is that Ollama is not installed in the environment
where this phase was built. That is not a reason to ship an untested path or to fake a model: it is a
reason to make the parts that *can* be tested — retrieval, prompt construction, parsing, verification,
grounding — independent of any model, and to provide an explicit, loudly-labelled extractor so the
whole vertical slice can be exercised end to end.

## Decision

**An investigation is a durable job that retrieves a bounded, numbered evidence set deterministically,
asks a provider to answer *only* from it, and then verifies every citation against the index and the
filesystem. The prose is stored as written; the verdicts are stored beside it, never applied to it.**

1. **Retrieval is deterministic, not semantic.** Candidates come from the existing index — symbols by
   name, files by path, findings by rule and message, plus one repository summary — matched on the
   question's tokens (`QuestionTokens`, at most 8, stop words removed). There is no embedding store,
   no vector distance, no similarity score and therefore no "the model found this relevant": the
   evidence list is a reproducible function of the question and the index. A reader can re-run the
   question and expect the same set.

2. **Evidence is numbered `E1…En`, capped at 12, and builders quote those keys.** At most 5 findings,
   6 symbols and 3 files, each detail truncated at 700 characters, plus the repository summary. The
   numbering is assigned once, when the set is assembled, and every later stage — prompt, answer,
   claims, storage — uses the same key. A key that is not in the assembled set is not a citation; it is
   a dangling marker, and dangling markers are a failed grounding check.

3. **The prompt is bounded at 24 000 characters, and the builder returns only what it included.** The
   budget is enforced while assembling, item by item; the `Included` list is the authority for what the
   model was shown. The first implementation budgeted against the full candidate list and then reported
   items the model never saw, which made "every claim cites evidence that was in the prompt" a claim
   about the wrong set. The prompt is hashed (SHA-256) and the hash, the prompt size and the evidence
   size are stored instead of the prompt, because a stored prompt is a stored copy of source code.

4. **Findings are used only if they describe the indexed revision.** The newest `STATIC_ANALYSIS` run
   carries the revision it analysed. If that revision is not the one being indexed, the findings are
   stale by definition, so retrieval skips them and says so in the diagnostics. A finding from another
   revision is not evidence about this one.

5. **Runtime evidence is the newest run's summary only.** Measurements are deliberately not retrieved:
   a single number without its scope, unit and caveats is exactly the kind of quotation this phase
   exists to prevent. The summary says what was recorded and the diagnostics say that individual
   measurements were not used.

6. **Excerpts are read at answer time through two guards and are never stored.** A candidate's recorded
   `path:line` range is checked against the sandbox (`RepoAccess.resolve`, traversal and symlink
   defence) and against the revision recorded on the row, then at most 24 lines and 1 200 characters
   are read. Only the SHA-256 of the exact text and its character count are persisted; the text itself
   goes to the provider and nowhere else.

7. **Four checks decide whether a citation holds.** For each claim: the cited key must exist in the
   prompt (otherwise the claim is unsupported and the marker is dangling); the claim must cite
   something; the cited excerpt is re-read and re-hashed, yielding `CONFIRMED`, `CHANGED`, `MISSING` or
   `NOT_LOCATED`; and any `path:line` the claim names in its own text must be covered by the evidence it
   cites. A claim about `PaymentService.java:40-52` that cites a finding on `OrderImporter.java` is
   unsupported even if both files exist, because it is describing something it was not given.

8. **Grounding is a property of the whole answer, and it is stricter than "most claims passed".** An
   answer is grounded only if it has at least one claim, every claim is `VERIFIED`, and no `[En]`
   marker in the prose points at a key that was not in the prompt. One unsupported claim makes the
   whole answer ungrounded; the per-claim verdicts are still shown, so the reader can see which part
   failed and why.

9. **The prose is never rewritten.** The model's sentences are stored exactly as received, including a
   claim that failed verification. The UI renders the verdict next to it and the banner above it; it
   does not edit, soften or delete the sentence. Rewriting would destroy the only record of what the
   model actually said.

10. **Parsed but ungrounded is `SUCCEEDED`; no evidence or no parse is `FAILED`.** A JSON answer with
    bad citations is a completed investigation with a negative verdict — the job did what it was asked
    and the answer did not hold up. A provider that returns non-JSON gets its raw text stored with zero
    claims and an ungrounded verdict, because "we could not read the answer" is a result about the
    provider, not about the repository. If retrieval finds nothing usable (including the case where the
    repository summary is the only candidate), the investigation **fails without calling the model at
    all** — an empty prompt is not a question, and paying for an answer to it would be waste.

11. **Refusals happen before a job exists.** A question shorter than 8 characters, or one made only of
    stop words, is `400 VALIDATION_FAILED` with a message that says it names nothing to search for. No
    usable provider is `503 DEPENDENCY_UNAVAILABLE`, checked **before** the revision check so an
    unconfigured server says so rather than blaming the repository. No indexed revision is `409`. None
    of these creates an investigation row, so the history contains only questions that were actually
    attempted.

12. **Cancellation is honest about what it can stop.** `POST …/cancel` returns `202`; a `QUEUED`
    investigation is marked `CANCELLED` immediately, because a queued job may never reach a handler and
    "cancelling" by waiting for it to start would be a lie. A `RUNNING` one is left to the handler's
    checkpoint. `begin()` refuses to run a cancelled investigation, and `complete()` discards an answer
    that arrives after cancellation — a cancelled investigation is never resurrected by a late reply.

13. **No transaction spans the model call.** The job reads the index in short transactions, closes them,
    calls the provider, and writes the result in a new transaction, per ADR 0006. A three-minute model
    reply must not hold a database connection, and it must not extend a transaction past the timeout of
    anything else in the system.

14. **The deterministic provider is a test double, not a fallback.** It is registered only when
    `repopilot.ai.provider=deterministic` is set explicitly; it reports itself as
    `deterministic-extractor (no model called)`, states in its limitations that no language model was
    called, emits one `LOW`-confidence observation per evidence item quoting the label and location, and
    answers no part of the question that requires a conclusion (the diagnostics say so). It is never
    selected because Ollama is unreachable. A build that silently answered with it would be fabricating
    an AI feature.

## Consequences

- **"Grounded" is a check, not a tone.** A reviewer can read the claims table, click a citation and land
  on the evidence row it names; the hash of the exact excerpt that was sent is stored, so the claim can
  be re-checked after the fact without the excerpt ever having been kept.
- **The ungrounded path is real and visible.** `InvestigationCitationIntegrationTest` drives a scripted
  provider that returns four claims — one verified, one citing a key that was never in the prompt, one
  citing nothing, and one naming a file and line its evidence does not cover — plus a dangling `E99`
  marker in the prose. The screen shows the answer as ungrounded, preserves the prose, and marks the
  four verdicts separately. A feature that can only demonstrate the happy path has not been
  demonstrated.
- **Retrieval quality is bounded by design.** Name and text matching finds the symbols and findings a
  question names; it does not find the subsystem a question *describes*. The screen says so, and the
  diagnostics list what the model was not shown. Embeddings remain an opt-in future, not a silent
  substitution.
- **Every investigation is reproducible up to the model.** The evidence set, the prompt hash and the
  provider identity are stored; the same question against an unchanged revision rebuilds the same
  prompt. The answer is not reproducible — that is the model's property, and the provenance says which
  model produced it.
- **Cost is bounded and local.** 12 items, a 24 000-character prompt, 1 500 answer tokens and a
  three-minute ceiling. A model that hangs cannot hold a job slot indefinitely.
- **Not claimed:** that the answer is correct, that a grounded answer is a good answer, that retrieval is
  exhaustive, or that a model's reasoning is visible. An investigation reports what a model said about a
  numbered evidence set, and exactly which of those citations survived checking.
