package dev.repopilot.analysis.pmd;

import dev.repopilot.analysis.AnalysisBatch;
import dev.repopilot.analysis.AnalysisSource;
import dev.repopilot.analysis.FindingCategory;
import dev.repopilot.analysis.FindingConfidence;
import dev.repopilot.analysis.FindingSeverity;
import dev.repopilot.analysis.SourceAnalyzer;
import dev.repopilot.analysis.StaticFinding;
import dev.repopilot.repository.scan.DetectedLanguage;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PMDVersion;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.Language;
import net.sourceforge.pmd.lang.LanguageRegistry;
import net.sourceforge.pmd.lang.LanguageVersion;
import net.sourceforge.pmd.lang.document.FileId;
import net.sourceforge.pmd.lang.document.TextFile;
import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.lang.rule.RulePriority;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PMD, run in-process over source text the runner has already read.
 *
 * <h2>Content, not paths</h2>
 * PMD is handed {@link TextFile#forCharSeq(CharSequence, FileId, LanguageVersion)} rather than a
 * file path. That is a security property, not a convenience: PMD's own file collector would walk
 * directories and open whatever it found, which would bypass the sandbox, the read policy and the
 * scan's exclusions. The analyzer analyses exactly the bytes the runner decided it may read.
 *
 * <h2>Ruleset</h2>
 * PMD's bundled {@code quickstart} ruleset — its own curated "rules that are most likely to apply
 * everywhere" list, which already excludes the rules that need a classpath. The list is PMD's, not
 * a RepoPilot selection, so the choice is auditable and moves with the PMD version rather than with
 * our opinion.
 *
 * <h2>No classpath, and what that costs</h2>
 * A source-level sweep has no compiled dependencies, so rules needing type resolution cannot match.
 * Every finding says so in its limitations, and the run carries a note with the configuration. A
 * clean PMD result is evidence about syntax and structure, never proof that the code is correct.
 *
 * <h2>Batch failures</h2>
 * A failure anywhere in PMD's setup or sweep propagates to the runner, which marks the run partial
 * and records a note. Swallowing it here would turn "PMD did not run" into "PMD found nothing",
 * which is the exact substitution this product exists to prevent.
 */
@Component
public class PmdAnalyzer implements SourceAnalyzer {

    public static final String ID = "pmd";

    private static final String QUICKSTART_RULESET = "rulesets/java/quickstart.xml";

    /** {@code finding.impact} is 2 000 characters; PMD's rationale text can exceed it. */
    private static final int MAX_IMPACT = 2000;
    private static final int MAX_SUMMARY = 4000;
    private static final int MAX_TITLE = 500;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return PMDVersion.VERSION;
    }

    @Override
    public Set<DetectedLanguage> languages() {
        return Set.of(DetectedLanguage.JAVA);
    }

    @Override
    public AnalysisBatch analyze(List<AnalysisSource> sources) {
        List<AnalysisSource> javaSources = sources.stream()
                .filter(source -> source.language() == DetectedLanguage.JAVA)
                .toList();
        if (javaSources.isEmpty()) {
            return AnalysisBatch.empty();
        }

        Language java = LanguageRegistry.PMD.getLanguageByFullName("Java");
        LanguageVersion version = java.getDefaultVersion();

        PMDConfiguration configuration = new PMDConfiguration();
        configuration.setDefaultLanguageVersion(version);
        // Single-threaded on purpose: the runner already occupies a worker thread, and PMD's own
        // pool would multiply the concurrency the job system believes it is controlling.
        configuration.setThreads(1);
        // The runner decides what is analysed. An incremental cache keyed on paths on disk would
        // make a run's result depend on files the read policy may have refused.
        configuration.setIgnoreIncrementalAnalysis(true);
        configuration.setMinimumPriority(RulePriority.LOW);

        List<String> notes = new ArrayList<>();
        notes.add("pmd " + PMDVersion.VERSION + " quickstart ruleset, Java " + version.getVersion()
                + " syntax, no classpath");

        Map<String, List<StaticFinding>> byPath = new LinkedHashMap<>();
        Map<FileId, AnalysisSource> sourcesByFileId = new LinkedHashMap<>();

        try (PmdAnalysis pmd = PmdAnalysis.create(configuration)) {
            pmd.addRuleSet(pmd.newRuleSetLoader().loadFromResource(QUICKSTART_RULESET));

            for (AnalysisSource source : javaSources) {
                // A relative path, so no absolute path from this host can reach a finding.
                FileId fileId = FileId.fromPathLikeString(source.relativePath());
                if (pmd.files().addFile(TextFile.forCharSeq(source.content(), fileId, version))) {
                    sourcesByFileId.put(fileId, source);
                } else {
                    notes.add("pmd did not accept " + source.relativePath() + " as Java source");
                }
            }

            Report report = pmd.performAnalysisAndCollectReport();

            for (RuleViolation violation : report.getViolations()) {
                AnalysisSource source = sourcesByFileId.get(violation.getFileId());
                if (source == null) {
                    // Should not happen: every file added is in the map. Recorded rather than
                    // guessed, because the alternative is attributing a finding to a path PMD
                    // named that RepoPilot never read.
                    notes.add("pmd reported a violation for a file that was not submitted");
                    continue;
                }
                byPath.computeIfAbsent(source.relativePath(), path -> new ArrayList<>())
                        .add(toFinding(violation, source.relativePath()));
            }

            if (!report.getSuppressedViolations().isEmpty()) {
                notes.add("pmd " + report.getSuppressedViolations().size()
                        + " violation(s) suppressed in source by @SuppressWarnings");
            }
            for (Report.ProcessingError error : report.getProcessingErrors()) {
                notes.add("pmd could not analyse " + error.getFileId().getFileName() + ": " + error.getMsg());
            }
        }

        byPath.values().forEach(findings -> findings.sort(Comparator
                .comparing((StaticFinding finding) -> finding.startLine() == null ? Integer.MAX_VALUE : finding.startLine())
                .thenComparing(StaticFinding::ruleId)));
        return AnalysisBatch.of(byPath, notes);
    }

    private static StaticFinding toFinding(RuleViolation violation, String relativePath) {
        Rule rule = violation.getRule();
        String name = rule.getName();
        return new StaticFinding(
                ID,
                PMDVersion.VERSION,
                name,
                name,
                truncate(name, MAX_TITLE),
                truncate(violation.getDescription(), MAX_SUMMARY),
                severity(rule.getPriority()),
                // A PMD match is a mechanical pattern match over the syntax tree: certainty about
                // the pattern's presence, and nothing at all about whether it matters here.
                FindingConfidence.HIGH,
                category(rule.getRuleSetName()),
                relativePath,
                violation.getBeginLine(),
                violation.getEndLine(),
                truncate(rule.getDescription(), MAX_IMPACT),
                documentation(rule),
                limitations(rule));
    }

    /**
     * PMD's five priorities mapped onto four. {@code MEDIUM_HIGH} becomes {@code MEDIUM} rather
     * than {@code HIGH} deliberately: in PMD's scale it sits below "the build should fail", and
     * promoting it would make RepoPilot's {@code HIGH} mean less than PMD's.
     */
    private static FindingSeverity severity(RulePriority priority) {
        return switch (priority) {
            case HIGH -> FindingSeverity.HIGH;
            case MEDIUM_HIGH, MEDIUM -> FindingSeverity.MEDIUM;
            case MEDIUM_LOW, LOW -> FindingSeverity.LOW;
        };
    }

    /**
     * PMD's ruleset names are a public label shown in its own reports, so they are a stable input.
     * Anything unrecognised falls back to maintainability rather than being dropped: an
     * unclassified finding is still a finding.
     */
    private static FindingCategory category(String ruleSetName) {
        String name = ruleSetName == null ? "" : ruleSetName.toLowerCase(Locale.ROOT);
        if (name.contains("error prone")) {
            return FindingCategory.CORRECTNESS;
        }
        if (name.contains("multithreading")) {
            return FindingCategory.CONCURRENCY;
        }
        if (name.contains("performance")) {
            return FindingCategory.PERFORMANCE;
        }
        if (name.contains("security")) {
            return FindingCategory.SECURITY;
        }
        if (name.contains("code style")) {
            return FindingCategory.STYLE;
        }
        return FindingCategory.MAINTAINABILITY;
    }

    private static String documentation(Rule rule) {
        String url = rule.getExternalInfoUrl();
        if (url == null || url.isBlank()) {
            return "Review the rule's documentation and decide whether it applies to this code.";
        }
        return "Review PMD's documentation for this rule and decide whether it applies: " + url;
    }

    private static String limitations(Rule rule) {
        return "Reported by PMD " + PMDVersion.VERSION + " (" + rule.getRuleSetName()
                + " ruleset), analysing one file at a time without a classpath, so rules that need "
                + "type resolution cannot match. A clean file is not proof that it is correct.";
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return null;
        }
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "\u2026";
    }
}
