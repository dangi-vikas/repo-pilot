package dev.repopilot.analysis;

import dev.repopilot.codeindex.parse.JavaSourceParser;
import dev.repopilot.codeindex.persistence.CodeSymbol;
import dev.repopilot.codeindex.persistence.CodeSymbolRepository;
import dev.repopilot.codeindex.persistence.SourceFile;
import dev.repopilot.codeindex.persistence.SourceFileRepository;
import dev.repopilot.jobs.JobAbortedException;
import dev.repopilot.jobs.JobContext;
import dev.repopilot.repository.RepositoryRevision;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.sandbox.RepositorySandbox;
import dev.repopilot.shared.error.ErrorCode;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.joining;

/**
 * Runs the static analyzers over a revision and records what they found.
 *
 * <h2>What it reads</h2>
 * Only files the revision already recorded. The runner never enumerates the filesystem, so a
 * file the scan excluded — a keystore, a {@code .env}, a credential directory — is unreachable
 * here by construction rather than by a second copy of the exclusion rules. The resolved path
 * is still re-validated through the repository sandbox, because "the index wrote this path" and
 * "this path is inside the allowed root" are different claims and only the second one is a
 * security property.
 *
 * <h2>Batch boundaries</h2>
 * Files are processed in batches of {@link #FILE_BATCH}. The batch is what an analyzer gets per
 * call, which is what lets PMD load its ruleset once per batch instead of once per file. The
 * runner decides the size: it is a resource limit, not a semantic unit.
 *
 * <h2>Transactions</h2>
 * The sweep runs with no transaction open. Findings are flushed every {@link #FINDING_BATCH}
 * rows, each flush in its own short transaction, and the run row is written separately at the
 * start and at the end. Holding a transaction across 3 000 file reads and a full parse sweep
 * would pin a connection and a large part of the heap for minutes, for no benefit: a partially
 * analysed revision is an expected outcome, not a corrupt state.
 *
 * <h2>Cancellation</h2>
 * Checked once per batch, which for the batch sizes here is a fraction of a second. When it
 * fires, findings already verified are kept and the run is closed as
 * {@link AnalysisRunStatus#CANCELLED} with its real counts, so the record says how far it got.
 * Discarding them would be tidier and less useful.
 */
@Service
public class AnalysisRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

    /** Files handed to an analyzer per call. Bounds analyzer memory and PMD's per-run setup cost. */
    private static final int FILE_BATCH = 200;

    /** Findings buffered before a flush. Bounded so a pathological ruleset cannot exhaust the heap. */
    private static final int FINDING_BATCH = 500;

    /** Distinct run notes carried into the summary. Beyond this the count is what matters. */
    private static final int MAX_NOTES_IN_SUMMARY = 3;

    private final RepositoryService repositories;
    private final RepositorySandbox sandbox;
    private final SourceFileRepository sourceFiles;
    private final CodeSymbolRepository codeSymbols;
    private final AnalysisRunRepository runs;
    private final AnalysisFindingRepository findings;
    private final List<SourceAnalyzer> analyzers;

    public AnalysisRunner(RepositoryService repositories,
                          RepositorySandbox sandbox,
                          SourceFileRepository sourceFiles,
                          CodeSymbolRepository codeSymbols,
                          AnalysisRunRepository runs,
                          AnalysisFindingRepository findings,
                          List<SourceAnalyzer> analyzers) {
        this.repositories = repositories;
        this.sandbox = sandbox;
        this.sourceFiles = sourceFiles;
        this.codeSymbols = codeSymbols;
        this.runs = runs;
        this.findings = findings;
        // Spring injects the analyzers in declaration order; a stable order keeps the run's
        // output and its notes reproducible.
        this.analyzers = List.copyOf(analyzers);
    }

    /** The analyzers this runner applies, in execution order. */
    public List<SourceAnalyzer> analyzers() {
        return analyzers;
    }

    /** Analyzer ids and versions, as stored on the run. */
    public String analyzerVersions() {
        return analyzers.stream().map(a -> a.id() + " " + a.version()).collect(joining(","));
    }

    public AnalysisOutcome analyse(UUID repositoryId, UUID jobId, JobContext context) {
        repositories.require(repositoryId);
        RepositoryRevision revision = repositories.latestRevision(repositoryId)
                .orElseThrow(() -> new RepoPilotException(ErrorCode.CONFLICT,
                        "This repository has not been indexed yet. Run an index job before analysing it."));
        Path root = repositories.resolveRoot(repositoryId);

        AnalysisRun run = runs.save(new AnalysisRun(repositoryId, revision.getId(), jobId,
                AnalysisRunType.STATIC_ANALYSIS, Instant.now()));
        UUID runId = run.getId();
        log.info("analysis run {} started for repository {} revision {}", runId, repositoryId, revision.getId());

        List<SourceFile> files = sourceFiles.findByRevisionIdOrderByRelativePathAsc(revision.getId());
        Tally tally = new Tally();
        Set<String> fingerprints = new HashSet<>();
        Set<String> notes = new LinkedHashSet<>();
        List<AnalysisFinding> pending = new ArrayList<>(FINDING_BATCH);
        boolean analyzerFailed = false;

        try {
            int total = files.size();
            for (int start = 0; start < total; start += FILE_BATCH) {
                checkpoint(context);
                int end = Math.min(start + FILE_BATCH, total);
                List<SourceFile> slice = files.subList(start, end);

                List<AnalysisSource> sources = new ArrayList<>(slice.size());
                Map<String, SourceFile> byPath = new LinkedHashMap<>();
                for (SourceFile file : slice) {
                    if (applicableAnalyzers(file).isEmpty()) {
                        tally.skippedLanguage++;
                        continue;
                    }
                    String content;
                    try {
                        content = read(root, file.getRelativePath());
                    } catch (IOException | RuntimeException e) {
                        // Unreadable is not fatal: the revision says the file exists, the disk or
                        // the read policy says otherwise. Recorded, counted, and reported.
                        tally.unreadable++;
                        log.warn("analysis: could not read {}: {}", file.getRelativePath(), e.toString());
                        continue;
                    }
                    sources.add(new AnalysisSource(file.getRelativePath(), file.getLanguage(),
                            JavaSourceParser.countLines(content), content));
                    byPath.put(file.getRelativePath(), file);
                }

                progress(context, total == 0 ? 100 : (end * 100) / total, "analysing",
                        end + " of " + total + " file(s)");

                if (!sources.isEmpty()) {
                    List<AnalysisBatch> batches = new ArrayList<>();
                    for (SourceAnalyzer analyzer : analyzers) {
                        List<AnalysisSource> supported = sources.stream()
                                .filter(source -> analyzer.languages().contains(source.language()))
                                .toList();
                        if (supported.isEmpty()) {
                            continue;
                        }
                        try {
                            AnalysisBatch batch = analyzer.analyze(supported);
                            batches.add(batch);
                            notes.addAll(batch.notes());
                        } catch (RuntimeException e) {
                            // One analyzer failing loses that analyzer's findings for this batch
                            // and nothing else. The run is marked partial so the gap is visible.
                            analyzerFailed = true;
                            log.warn("analyzer {} failed on a batch of {} file(s): {}",
                                    analyzer.id(), supported.size(), e.toString());
                            notes.add("analyzer " + analyzer.id() + " failed on a batch of "
                                    + supported.size() + " file(s)");
                        }
                    }

                    for (AnalysisSource source : sources) {
                        List<StaticFinding> produced = new ArrayList<>();
                        for (AnalysisBatch batch : batches) {
                            produced.addAll(batch.forPath(source.relativePath()));
                        }
                        if (!produced.isEmpty()) {
                            // Symbols are loaded only for files that produced findings, so a clean
                            // repository does not pay one extra query per file.
                            SourceFile file = byPath.get(source.relativePath());
                            List<CodeSymbol> fileSymbols =
                                    codeSymbols.findBySourceFileIdOrderByStartLineAsc(file.getId());
                            for (StaticFinding finding : produced) {
                                if (!fingerprints.add(finding.fingerprint())) {
                                    continue;
                                }
                                tally.record(finding.severity());
                                pending.add(toEntity(finding, repositoryId, revision.getId(), runId,
                                        file.getId(), fileSymbols, source.lineCount()));
                            }
                            if (pending.size() >= FINDING_BATCH) {
                                flush(pending);
                            }
                        }
                        tally.analysed++;
                    }
                }
            }
            flush(pending);

            AnalysisRunStatus status = (tally.unreadable > 0 || analyzerFailed)
                    ? AnalysisRunStatus.PARTIAL
                    : AnalysisRunStatus.SUCCEEDED;
            AnalysisOutcome outcome = outcome(runId, revision.getId(), status, tally, notes);
            run.complete(status, analyzerVersions(), outcome.summary(), tally.findings, Instant.now());
            runs.save(run);
            log.info("analysis run {} finished: {}", runId, outcome.summary());
            return outcome;
        } catch (JobAbortedException e) {
            flush(pending);
            run.complete(AnalysisRunStatus.CANCELLED, analyzerVersions(),
                    "cancelled after analysing " + tally.analysed + " file(s); " + tally.findings
                            + " finding(s) kept from the files already analysed",
                    tally.findings, Instant.now());
            runs.save(run);
            throw e;
        } catch (RuntimeException e) {
            flush(pending);
            run.complete(AnalysisRunStatus.FAILED, analyzerVersions(), "analysis failed: " + e,
                    tally.findings, Instant.now());
            runs.save(run);
            throw e;
        }
    }

    /**
     * Analyzers that can read this file. Non-Java files are common in a real repository — Kotlin,
     * YAML, SQL — and are skipped explicitly rather than by accident, because the count is
     * reported in the run summary.
     */
    private List<SourceAnalyzer> applicableAnalyzers(SourceFile file) {
        return analyzers.stream()
                .filter(analyzer -> analyzer.languages().contains(file.getLanguage()))
                .toList();
    }

    /** Reads a recorded relative path through the sandbox, re-validating containment. */
    private String read(Path root, String relativePath) throws IOException {
        Path resolved = sandbox.resolveForRead(root, relativePath);
        return Files.readString(resolved, StandardCharsets.UTF_8);
    }

    /**
     * Innermost recorded symbol containing a line, or {@code null} when no symbol contains it.
     *
     * <p>Best effort and clearly bounded: a finding whose line is not inside any recorded symbol
     * keeps a null symbol id and is still a complete finding. Attaching a finding to the wrong
     * declaration would be worse than attaching it to none.
     *
     * <p>Symbols arrive ordered by start line ascending, so scanning from the end finds the
     * innermost declaration first: a method inside a class, a local class inside a method.
     */
    private static UUID symbolAt(List<CodeSymbol> symbols, int line) {
        for (int index = symbols.size() - 1; index >= 0; index--) {
            CodeSymbol symbol = symbols.get(index);
            if (symbol.getStartLine() == null || symbol.getStartLine() > line) {
                continue;
            }
            if (symbol.getEndLine() == null || symbol.getEndLine() >= line) {
                return symbol.getId();
            }
        }
        return null;
    }

    private AnalysisFinding toEntity(StaticFinding finding,
                                     UUID repositoryId,
                                     UUID revisionId,
                                     UUID runId,
                                     UUID sourceFileId,
                                     List<CodeSymbol> symbols,
                                     int lineCount) {
        UUID symbolId = finding.startLine() == null ? null : symbolAt(symbols, finding.startLine());
        return new AnalysisFinding(repositoryId, revisionId, runId, sourceFileId, symbolId,
                finding.fingerprint(), finding.findingType(), finding.title(), finding.summary(),
                finding.severity(), EvidenceCategory.STATIC_FINDING, finding.confidence(),
                finding.analyzerId(), finding.ruleId(), finding.category(), finding.relativePath(),
                finding.startLine(), finding.endLine(), finding.impact(), finding.recommendedAction(),
                finding.limitations(), CitationVerifier.verify(finding.startLine(), finding.endLine(), lineCount));
    }

    private void flush(List<AnalysisFinding> pending) {
        if (pending.isEmpty()) {
            return;
        }
        findings.saveAll(pending);
        pending.clear();
    }

    /**
     * Cancellation and deadline checks, tolerating a missing context.
     *
     * <p>A runner invoked outside the job system — by a test, or by a future synchronous caller —
     * has no deadline and nothing to cancel. Treating that as "no interruption requested" is more
     * honest than requiring a context and inventing one, and it matches how the indexer behaves.
     */
    private static void checkpoint(JobContext context) {
        if (context != null) {
            context.checkpoint();
        }
    }

    private static void progress(JobContext context, int percent, String stage, String detail) {
        if (context != null) {
            context.progress(percent, stage, detail);
        }
    }

    private AnalysisOutcome outcome(UUID runId, UUID revisionId, AnalysisRunStatus status, Tally tally,
                                    Set<String> notes) {
        return new AnalysisOutcome(runId, revisionId, status, tally.analysed, tally.skippedLanguage,
                tally.unreadable, tally.findings, tally.bySeverity,
                analyzers.stream().map(a -> a.id() + " " + a.version()).toList(),
                notes.stream().limit(MAX_NOTES_IN_SUMMARY).toList(),
                Math.max(0, notes.size() - MAX_NOTES_IN_SUMMARY));
    }

    /** Mutable per-run accumulator. Confined to this thread and to one call. */
    private static final class Tally {
        private int analysed;
        private int skippedLanguage;
        private int unreadable;
        private int findings;
        private final Map<FindingSeverity, Integer> bySeverity = new EnumMap<>(FindingSeverity.class);

        private void record(FindingSeverity severity) {
            findings++;
            bySeverity.merge(severity, 1, Integer::sum);
        }
    }
}
