package dev.repopilot.analysis;

import dev.repopilot.analysis.query.AnalysisQueryService;
import dev.repopilot.analysis.query.FindingFilter;
import dev.repopilot.analysis.query.FindingSummaryView;
import dev.repopilot.analysis.query.FindingView;
import dev.repopilot.codeindex.index.IndexOutcome;
import dev.repopilot.codeindex.index.RepositoryIndexer;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.workspace.WorkspaceService;
import dev.repopilot.workspace.WorkspaceSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end static analysis: index a small repository, run the analyzers, read the findings back
 * through the query layer.
 *
 * <p>Asserts the things that would be defects rather than features if they broke: that every finding
 * carries a verified location, that a secret's value never reaches a finding, that a file no analyzer
 * supports is counted rather than silently dropped, and that a second run appends rather than
 * replacing the first.
 */
@SpringBootTest(properties = {
        "repopilot.security.token=integration-test-bearer-token-0002",
        "repopilot.ai.enabled=false"
})
class StaticAnalysisIntegrationTest {

    /** The literal the security rule must find and must never repeat. */
    private static final String SECRET_LITERAL = "sk-live-9f8e7d6c5b4a";

    private static final String PROBLEMS_PATH = "com/example/quality/QualityProblems.java";

    @TempDir
    static Path workspaceRoot;

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("repopilot.workspace.roots", () -> workspaceRoot.toAbsolutePath().toString());
        registry.add("repopilot.storage.data-dir", () -> dataDir.toAbsolutePath().toString());
    }

    @Autowired
    private WorkspaceService workspaces;
    @Autowired
    private RepositoryService repositories;
    @Autowired
    private RepositoryIndexer indexer;
    @Autowired
    private AnalysisRunner analysis;
    @Autowired
    private AnalysisQueryService analysisQueries;
    @Autowired
    private AnalysisFindingRepository findings;
    @Autowired
    private AnalysisRunRepository runs;

    @Test
    @DisplayName("finds every built-in rule, verifies every citation, and never repeats a secret")
    void analysesEveryBuiltInRule() throws IOException {
        Path repository = workspaceRoot.resolve("quality");
        write(repository, PROBLEMS_PATH, """
                package com.example.quality;

                import java.text.SimpleDateFormat;
                import java.util.Date;

                public class QualityProblems {

                    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
                    private String apiKey = "%s";
                    private int unusedField;

                    void emptyCatch() {
                        try {
                            risky();
                        } catch (RuntimeException e) {
                        }
                    }

                    boolean swallowed() {
                        boolean recovered = false;
                        try {
                            risky();
                        } catch (IllegalStateException e) {
                            recovered = true;
                        }
                        return recovered;
                    }

                    void prints() {
                        try {
                            risky();
                        } catch (Exception e) {
                            e.printStackTrace();
                            System.out.println("failed");
                        }
                    }

                    void catchThrowable() {
                        try {
                            risky();
                        } catch (Throwable t) {
                            System.err.println("unreachable state");
                        }
                    }

                    void sleeps() throws InterruptedException {
                        int retries = 3;
                        while (retries-- > 0) {
                            Thread.sleep(50);
                        }
                    }

                    String stamp(Date date) {
                        return format.format(date);
                    }

                    private void risky() {
                        throw new IllegalStateException("boom");
                    }
                }
                """.formatted(SECRET_LITERAL));
        // Read and recorded by the index, but no analyzer reads it. It must be counted as skipped
        // rather than silently vanishing from the run's arithmetic.
        write(repository, "src/main/resources/application.properties", "server.port=8080\n");

        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        RepositorySummary registered = repositories.register(workspace.id(), "quality",
                repository.toAbsolutePath().toString());
        IndexOutcome indexed = indexer.index(registered.id(), null);
        assertThat(indexed.revisionId()).isNotNull();

        AnalysisOutcome outcome = analysis.analyse(registered.id(), null, null);

        assertThat(outcome.status()).isEqualTo(AnalysisRunStatus.SUCCEEDED);
        assertThat(outcome.filesAnalysed()).isEqualTo(1);
        assertThat(outcome.filesSkippedLanguage()).isEqualTo(1);
        assertThat(outcome.filesUnreadable()).isZero();
        assertThat(outcome.findings()).isGreaterThanOrEqualTo(8);

        List<AnalysisFinding> stored =
                findings.findByAnalysisRunIdOrderBySeverityRankDescSourcePathAscStartLineAsc(outcome.runId());
        assertThat(stored).hasSize(outcome.findings());

        // Every built-in rule fires on this fixture. Asserting by rule id keeps the check readable
        // and makes a rule that silently stops working fail the build.
        assertThat(stored).extracting(AnalysisFinding::getRuleId)
                .contains("RP001", "RP002", "RP003", "RP004", "RP005", "RP006", "RP007", "RP008");

        // PMD ran in-process over the same bytes. Its findings sit beside ours and stay
        // distinguishable by analyzer id rather than being merged into one anonymous list.
        assertThat(stored).extracting(AnalysisFinding::getAnalyzerId).contains("custom-checks", "pmd");
        assertThat(stored).filteredOn(finding -> "pmd".equals(finding.getAnalyzerId()))
                .extracting(AnalysisFinding::getRuleId)
                .contains("EmptyCatchBlock");
        assertThat(outcome.analyzers()).anySatisfy(analyzer -> assertThat(analyzer).startsWith("pmd "));

        // A finding is only usable if its location was checked against the analysed text.
        assertThat(stored).allSatisfy(finding -> {
            assertThat(finding.getEvidenceCategory()).isEqualTo(EvidenceCategory.STATIC_FINDING);
            assertThat(finding.getVerificationStatus()).isEqualTo(VerificationStatus.SOURCE_CONFIRMED);
            assertThat(finding.getSourcePath()).isEqualTo(PROBLEMS_PATH);
            assertThat(finding.getStartLine()).isNotNull();
            assertThat(finding.getStartLine()).isPositive();
            assertThat(finding.getLimitations()).isNotBlank();
            assertThat(finding.getFingerprint()).isNotBlank();
        });

        // The point of the security rule: it reports the location and the name, and the value itself
        // never enters the database, the API or a prompt.
        assertThat(stored).allSatisfy(finding -> {
            assertThat(finding.getTitle()).doesNotContain(SECRET_LITERAL);
            assertThat(finding.getSummary()).doesNotContain(SECRET_LITERAL);
            assertThat(finding.getImpact()).doesNotContain(SECRET_LITERAL);
            assertThat(finding.getRecommendedAction()).doesNotContain(SECRET_LITERAL);
        });

        AnalysisFinding credential = stored.stream()
                .filter(finding -> "RP005".equals(finding.getRuleId()))
                .findFirst()
                .orElseThrow();
        assertThat(credential.getSeverity()).isEqualTo(FindingSeverity.HIGH);
        assertThat(credential.getCategory()).isEqualTo(FindingCategory.SECURITY);
        // Name-based, so the claim is a signal rather than a proof, and it is recorded as one.
        assertThat(credential.getConfidence()).isEqualTo(FindingConfidence.MEDIUM);
        assertThat(credential.getSummary()).contains("apiKey");

        // The run's own arithmetic has to add up: every finding counted at exactly one severity.
        assertThat(outcome.findingsBySeverity().values())
                .containsExactlyInAnyOrderElementsOf(nonZeroSeverityCounts(stored));

        // The run's summary states what it did and what it did not do.
        assertThat(outcome.summary())
                .contains("finding")
                .contains("1 file(s) skipped (no analyzer for the language)")
                .contains("pmd ");

        // Reading back through the query layer: current run, tallies, and filtered pages.
        FindingSummaryView summary =
                analysisQueries.summary(registered.id(), null, AnalysisRunType.STATIC_ANALYSIS);
        assertThat(summary.runId()).isEqualTo(outcome.runId());
        assertThat(summary.revisionId()).isEqualTo(indexed.revisionId());
        assertThat(summary.runStatus()).isEqualTo(AnalysisRunStatus.SUCCEEDED);
        assertThat(summary.total()).isEqualTo(outcome.findings());
        assertThat(summary.bySeverity().values()).containsExactlyInAnyOrderElementsOf(
                nonZeroSeverityCounts(stored).stream().map(Integer::longValue).toList());
        assertThat(summary.byAnalyzer()).containsKeys("custom-checks", "pmd");

        Page<FindingView> highSeverity = analysisQueries.findings(registered.id(),
                new FindingFilter(null, FindingSeverity.HIGH, null, null, null, null, null),
                PageRequest.of(0, 50));
        assertThat(highSeverity.getContent()).isNotEmpty();
        assertThat(highSeverity.getContent()).allSatisfy(finding -> {
            assertThat(finding.severity()).isEqualTo(FindingSeverity.HIGH);
            assertThat(finding.evidenceCategory()).isEqualTo(EvidenceCategory.STATIC_FINDING);
        });

        Page<FindingView> byFile = analysisQueries.findings(registered.id(),
                new FindingFilter(null, null, null, null, null, PROBLEMS_PATH, null),
                PageRequest.of(0, 200));
        assertThat(byFile.getTotalElements()).isEqualTo(outcome.findings());

        // A filter that matches nothing returns an empty page. The summary above is what tells a
        // client that this means "found nothing" rather than "never analysed".
        Page<FindingView> none = analysisQueries.findings(registered.id(),
                new FindingFilter(null, null, null, null, "no-such-analyzer", null, null),
                PageRequest.of(0, 10));
        assertThat(none.getTotalElements()).isZero();
        assertThat(none.getContent()).isEmpty();

        // Default ordering: most severe first, then reading order.
        Page<FindingView> ordered = analysisQueries.findings(registered.id(),
                new FindingFilter(null, null, null, null, null, null, null),
                PageRequest.of(0, 10, Sort.unsorted()));
        assertThat(ordered.getContent()).isNotEmpty();
        assertThat(ordered.getContent().get(0).severity().rank())
                .isGreaterThanOrEqualTo(ordered.getContent().get(ordered.getContent().size() - 1).severity().rank());

        // A second run appends. The first run's findings stay readable under their own run id, so
        // "fewer findings than last time" is an observable change rather than a silent rewrite.
        AnalysisOutcome second = analysis.analyse(registered.id(), null, null);
        assertThat(second.runId()).isNotEqualTo(outcome.runId());
        assertThat(findings.countByAnalysisRunId(outcome.runId())).isEqualTo(outcome.findings());
        assertThat(findings.countByAnalysisRunId(second.runId())).isEqualTo(second.findings());
        assertThat(analysisQueries.summary(registered.id(), null, AnalysisRunType.STATIC_ANALYSIS).runId())
                .isEqualTo(second.runId());
        assertThat(runs.findFirstByRepositoryIdAndRunTypeOrderByStartedAtDesc(registered.id(),
                AnalysisRunType.STATIC_ANALYSIS)).isPresent();
    }

    @Test
    @DisplayName("reports nothing for source with nothing wrong in it")
    void reportsNothingForCleanSource() throws IOException {
        Path repository = workspaceRoot.resolve("clean");
        write(repository, "com/example/clean/Clean.java", """
                package com.example.clean;

                public class Clean {
                    private final String name;

                    public Clean(String name) {
                        this.name = name;
                    }

                    public String name() {
                        return name;
                    }
                }
                """);

        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        RepositorySummary registered = repositories.register(workspace.id(), "clean",
                repository.toAbsolutePath().toString());
        indexer.index(registered.id(), null);

        AnalysisOutcome outcome = analysis.analyse(registered.id(), null, null);

        assertThat(outcome.status()).isEqualTo(AnalysisRunStatus.SUCCEEDED);
        assertThat(outcome.filesAnalysed()).isEqualTo(1);
        // Zero findings has to mean zero, not "the analyzers did not run": the run still records its
        // analyzers and still reports a completed status.
        assertThat(outcome.findings()).isZero();
        assertThat(outcome.analyzers()).isNotEmpty();
        assertThat(findings.findByAnalysisRunIdOrderBySeverityRankDescSourcePathAscStartLineAsc(outcome.runId()))
                .isEmpty();
    }

    /** Severe-to-mild counts including only severities that occurred, matching the stored tally. */
    private static List<Integer> nonZeroSeverityCounts(List<AnalysisFinding> stored) {
        return Arrays.stream(FindingSeverity.values())
                .map(severity -> (int) stored.stream()
                        .filter(finding -> finding.getSeverity() == severity)
                        .count())
                .filter(count -> count > 0)
                .toList();
    }

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
