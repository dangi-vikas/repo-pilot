# ADR 0007 — The static-analysis contract

- **Status:** Accepted
- **Date:** 2026-10-18
- **Deciders:** RepoPilot maintainers

## Context

Static analysis produces the product's second kind of claim: *a rule fired, and here is the
line it fired on*. It is the first place in RepoPilot where a statement is attributed to a
tool rather than read out of the index, and the first place where a third-party engine (PMD)
is given repository content.

Three things make this easy to get wrong:

1. **An analyzer with ambient power is unauditable.** If an analyzer can read the disk, query
   the database, or consult the clock, then what it reports depends on something the reader
   cannot see, and two runs on the same revision can disagree without anyone being able to
   say why.
2. **A rule's metadata is not the rule's business.** If an analyzer decides its own severity,
   evidence category or verification status, then every analyzer is a separate opportunity to
   mislabel a finding — and one that nothing else in the system can correct.
3. **Third-party engines want to touch the filesystem.** PMD's public API is built around a
   file collector that opens files by path. That would mean re-reading repository content
   outside the sandbox that every other read goes through, and it would reintroduce the
   exclusions the scanner already applied.

## Decision

**Analyzers are pure functions of the files they are handed, and everything the framework
can decide about a finding, the framework decides.**

1. **Input is a list of sources, not a repository.** `SourceAnalyzer.analyze(List<AnalysisSource>)`
   returns `AnalysisBatch(findingsByPath, notes)`. Analyzers never read a file: the runner has
   already re-read each file through the sandbox, recorded its hash, and reconciled it against
   the indexed revision. An analyzer that wanted to look at anything else would have to ask
   for it in its input.

2. **Analyzers may not assume a batch is the whole revision.** The runner chooses the batch
   size (200 files) so that an expensive engine amortises its setup over several files, but no
   analyzer may depend on that. PMD's ruleset is loaded once per batch and the violations are
   keyed back through a `Map<FileId, AnalysisSource>` rather than by re-normalising paths.

3. **Analyzer metadata is injected, never declared.** Severity ranking, `evidenceCategory`
   (`STATIC_FINDING`), `analyzerId`, `analysisRunId`, `verificationStatus` and the fingerprint
   are set by the runner. An analyzer declares a rule id, a location, a severity it believes,
   prose, and — mandatorily — what it cannot see.

4. **A finding must state its own boundary.** `limitations` is non-blank by construction; the
   finding is rejected otherwise. A rule whose blind spot is not written down is a rule that
   reads as more certain than it is.

5. **Citations are verified against the text that was analysed, not clamped.** The runner
   compares each finding's line range with the line count of the source it came from and
   stores `SOURCE_CONFIRMED`, `LOCATION_STALE` or `NO_LOCATION_CLAIMED`. A stale citation is
   kept and shown: it is a defect in the rule, and hiding it would hide the defect. The check
   lives in `CitationVerifier.verify(startLine, endLine, lineCount)` so it can be unit-tested
   directly rather than through a run.

6. **Content is fed to PMD, not collected by it.** Files reach PMD through
   `TextFile.forCharSeq(content, FileId.fromPathLikeString(path), version)`. No classpath is
   supplied, the engine is single-threaded, and its incremental cache is off, so a run's
   result depends only on the bytes in the request. PMD's bundled `rulesets/java/quickstart.xml`
   is used as PMD ships it; RepoPilot does not curate it, and does not silently drop rules.

7. **Two engines may report the same line.** `analyzerId` is part of every finding and part of
   the fingerprint, so PMD's `EmptyCatchBlock` and RepoPilot's `RP001` coexist on the same
   empty catch. The duplication is deliberate: agreement between independent engines is
   evidence, and suppressing one of them would destroy it.

8. **Findings are append-only per run.** Re-analysing a revision writes a second run. Reads are
   scoped by `analysis_run_id`, and the summary resolves "the current run" for a revision
   rather than mutating the previous one.

## Consequences

- An analyzer is testable without a database, a clock or a filesystem: hand it a list of
  strings and assert on the findings. The integration test proves the rules fire; the rules
  themselves are testable in isolation.
- A rule bug is a code change in one class, and a run's output is reproducible from its
  revision and its analyzer versions, both of which are recorded on the run.
- The framework owns the honest half of every finding, which is why `severity` cannot be
  inflated by an analyzer and `limitations` cannot be omitted.
- Adding SpotBugs means adding a file-level precondition (bytecode present), not renegotiating
  the contract: without compiled classes it reports *not applicable* with the precondition
  attached, exactly as the evidence model already requires.
- The cost is that an analyzer cannot resolve anything for itself. Every rule that needs
  context (a symbol, a type) must be given that context in the input, and today the input is
  source text only. That is a deliberate limit: it keeps findings tied to text that was read.
