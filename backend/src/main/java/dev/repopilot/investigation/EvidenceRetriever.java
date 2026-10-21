package dev.repopilot.investigation;

import dev.repopilot.analysis.AnalysisFinding;
import dev.repopilot.analysis.AnalysisFindingRepository;
import dev.repopilot.analysis.AnalysisRun;
import dev.repopilot.analysis.AnalysisRunRepository;
import dev.repopilot.analysis.AnalysisRunType;
import dev.repopilot.codeindex.persistence.CodeSymbol;
import dev.repopilot.codeindex.persistence.CodeSymbolRepository;
import dev.repopilot.codeindex.persistence.SourceFile;
import dev.repopilot.codeindex.persistence.SourceFileRepository;
import dev.repopilot.codeindex.model.SymbolKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Chooses the evidence a question is answered from.
 *
 * <h2>No semantic retrieval, on purpose</h2>
 * Every item here can be explained in one sentence: a finding matched the question's terms,
 * a symbol's name did, a file's path did, or the item is the repository's own counts. The
 * reader can therefore check the retrieval as well as the answer. Embeddings are declared in
 * this product as optional and are not part of this build; pretending to a similarity score
 * would make the evidence list unauditable.
 *
 * <h2>What is deliberately not retrieved</h2>
 * <ul>
 *   <li><b>Findings from an older revision.</b> The newest static run is used only when it
 *       ran against the indexed revision. Attaching a line number from a previous revision to
 *       current code is exactly the stale-citation failure this phase exists to prevent.</li>
 *   <li><b>Runtime measurements beyond the newest run's summary.</b> A benchmark import can
 *       hold twenty thousand rows; retrieving a selection of them by keyword would look like
 *       analysis and would be arbitrary. The summary states the headline figures, and the
 *       Performance Lab is the place to read the rest.</li>
 *   <li><b>Anything not in the index.</b> No path is searched from the question text; a file
 *       must be a recorded source file of the revision, and the sandbox re-checks it before a
 *       line is read.</li>
 * </ul>
 */
@Service
public class EvidenceRetriever {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRetriever.class);

    /** Items offered to the model. Beyond this the prompt stops being reviewable. */
    public static final int MAX_EVIDENCE = 12;
    private static final int MAX_FINDINGS = 5;
    private static final int MAX_SYMBOLS = 6;
    private static final int MAX_FILES = 3;
    private static final int MAX_DETAIL_CHARS = 700;

    private final AnalysisRunRepository runs;
    private final AnalysisFindingRepository findings;
    private final CodeSymbolRepository symbols;
    private final SourceFileRepository sourceFiles;
    private final SourceExcerptReader excerpts;

    public EvidenceRetriever(AnalysisRunRepository runs,
                             AnalysisFindingRepository findings,
                             CodeSymbolRepository symbols,
                             SourceFileRepository sourceFiles,
                             SourceExcerptReader excerpts) {
        this.runs = runs;
        this.findings = findings;
        this.symbols = symbols;
        this.sourceFiles = sourceFiles;
        this.excerpts = excerpts;
    }

    /** Retrieval output: the numbered evidence, a one-line summary and what was left out. */
    public record RetrievalResult(List<EvidenceItem> items, String summary, List<String> notes) {

        public RetrievalResult {
            items = List.copyOf(items);
            notes = List.copyOf(notes);
        }
    }

    public RetrievalResult retrieve(UUID repositoryId, UUID revisionId, String question) {
        List<String> tokens = QuestionTokens.from(question);
        List<String> notes = new ArrayList<>();

        // Deliberately not one transaction. Each repository read below is its own short
        // transaction and the excerpt reads happen between them with no transaction open,
        // because a prompt is built from filesystem content and ADR 0006 keeps filesystem work
        // out of transactions. Nothing here mutates, so the only cost is a handful of tiny
        // reads; the benefit is that a slow disk cannot pin a connection.
        Map<String, EvidenceItem> selected = new LinkedHashMap<>();
        if (tokens.isEmpty()) {
            notes.add("The question contained no searchable terms, so retrieval used the "
                    + "repository summary only.");
        }

        retrieveFindings(repositoryId, revisionId, tokens, selected, notes);
        retrieveSymbols(repositoryId, revisionId, tokens, selected);
        retrieveFiles(revisionId, tokens, selected);
        retrieveRuntime(repositoryId, question, selected, notes);
        addRepositorySummary(revisionId, selected, notes);

        List<EvidenceItem> ranked = selected.values().stream()
                .sorted(Comparator.comparingDouble(EvidenceItem::retrievalScore).reversed()
                        .thenComparing(item -> item.kind().ordinal())
                        .thenComparing(item -> item.path() == null ? "" : item.path())
                        .thenComparing(item -> item.startLine() == null ? 0 : item.startLine()))
                .toList();

        int dropped = Math.max(0, ranked.size() - MAX_EVIDENCE);
        if (dropped > 0) {
            notes.add(dropped + " lower-ranked item(s) were dropped to stay within the "
                    + MAX_EVIDENCE + "-item evidence budget.");
        }
        List<EvidenceItem> kept = new ArrayList<>(ranked.subList(0, Math.min(ranked.size(), MAX_EVIDENCE)));
        List<EvidenceItem> numbered = new ArrayList<>(kept.size());
        for (int i = 0; i < kept.size(); i++) {
            numbered.add(kept.get(i).withKey("E" + (i + 1)));
        }

        String summary = summarise(numbered, tokens);
        log.debug("retrieval for revision {} returned {} item(s) from terms {}", revisionId,
                numbered.size(), tokens);
        return new RetrievalResult(numbered, summary, notes);
    }

    private void retrieveFindings(UUID repositoryId, UUID revisionId, List<String> tokens,
                                  Map<String, EvidenceItem> selected, List<String> notes) {
        Optional<AnalysisRun> newest = runs.findFirstByRepositoryIdAndRunTypeOrderByStartedAtDesc(
                repositoryId, AnalysisRunType.STATIC_ANALYSIS);
        if (newest.isEmpty()) {
            notes.add("No static analysis run exists for this repository, so no findings were retrieved.");
            return;
        }
        AnalysisRun run = newest.get();
        if (!java.util.Objects.equals(run.getRevisionId(), revisionId)) {
            notes.add("The newest static analysis describes a different revision ("
                    + run.getRevisionId() + "), so its findings were not retrieved: a finding's "
                    + "line numbers belong to the revision it was run against.");
            return;
        }
        List<AnalysisFinding> all = findings.findByAnalysisRunIdOrderBySeverityRankDescSourcePathAscStartLineAsc(
                run.getId());
        List<Scored<AnalysisFinding>> scored = new ArrayList<>();
        for (AnalysisFinding finding : all) {
            int hits = hits(tokens, finding.getTitle(), finding.getSummary(), finding.getRuleId(),
                    finding.getSourcePath(), finding.getFindingType());
            // Every stored finding is a candidate; the severity rank decides the order when the
            // question names nothing in particular, which is the documented fallback.
            double score = 40 + finding.getSeverityRank() + 6.0 * hits;
            scored.add(new Scored<>(finding, score));
        }
        scored.sort(Comparator.comparingDouble(Scored<AnalysisFinding>::score).reversed()
                .thenComparing(entry -> entry.value().getSourcePath() == null ? "" : entry.value().getSourcePath())
                .thenComparing(entry -> entry.value().getStartLine() == null ? 0 : entry.value().getStartLine()));

        int accepted = 0;
        for (Scored<AnalysisFinding> entry : scored) {
            if (accepted >= MAX_FINDINGS) {
                break;
            }
            AnalysisFinding finding = entry.value();
            String path = finding.getSourcePath();
            Integer start = finding.getStartLine();
            String excerpt = null;
            Integer end = finding.getEndLine();
            if (path != null && start != null && start >= 1) {
                int wanted = end != null && end >= start ? end - start + 1 : SourceExcerptReader.MAX_LINES;
                SourceExcerptReader.Excerpt read = excerpts.read(repositoryId, revisionId, path, start, wanted);
                if (read != null) {
                    excerpt = read.text();
                    start = read.startLine();
                    end = read.endLine();
                }
            }
            String rule = finding.getRuleId() != null ? finding.getRuleId() : finding.getAnalyzerId();
            String label = (rule == null ? "finding" : rule) + " — " + finding.getTitle();
            String detail = finding.getSeverity() + " · " + finding.getCategory() + " · "
                    + truncate(finding.getSummary(), MAX_DETAIL_CHARS);
            selected.putIfAbsent("finding:" + path + ":" + start,
                    new EvidenceItem(null, EvidenceKind.FINDING, label, detail, path,
                            start >= 1 ? start : null, end >= 1 ? end : null,
                            null, null, excerpt, entry.score(), "finding-text"));
            accepted++;
        }
    }

    private void retrieveSymbols(UUID repositoryId, UUID revisionId, List<String> tokens,
                                 Map<String, EvidenceItem> selected) {
        Map<UUID, Scored<CodeSymbol>> found = new LinkedHashMap<>();
        for (String token : tokens) {
            for (CodeSymbol symbol : symbols.search(revisionId, token, PageRequest.of(0, 10)).getContent()) {
                int hits = hits(tokens, symbol.getName(), symbol.getQualifiedName(), symbol.getSignature());
                boolean exactName = symbol.getName() != null
                        && symbol.getName().equalsIgnoreCase(token);
                boolean type = isType(symbol.getKind());
                double score = 30 + 8.0 * hits + (exactName ? 6 : 0) + (type ? 2 : 0);
                Scored<CodeSymbol> existing = found.get(symbol.getId());
                if (existing == null || existing.score() < score) {
                    found.put(symbol.getId(), new Scored<>(symbol, score));
                }
            }
        }
        List<Scored<CodeSymbol>> ranked = new ArrayList<>(found.values());
        ranked.sort(Comparator.comparingDouble(Scored<CodeSymbol>::score).reversed()
                .thenComparing(entry -> entry.value().getQualifiedName()));

        Map<UUID, String> paths = filePaths(ranked.stream().map(entry -> entry.value().getSourceFileId()).toList());
        int accepted = 0;
        for (Scored<CodeSymbol> entry : ranked) {
            if (accepted >= MAX_SYMBOLS) {
                break;
            }
            CodeSymbol symbol = entry.value();
            String path = paths.get(symbol.getSourceFileId());
            Integer start = symbol.getStartLine();
            Integer end = symbol.getEndLine();
            String excerpt = null;
            if (path != null && start != null && start >= 1) {
                SourceExcerptReader.Excerpt read = excerpts.read(repositoryId, revisionId, path, start,
                        SourceExcerptReader.MAX_LINES);
                if (read != null) {
                    excerpt = read.text();
                    start = read.startLine();
                    end = read.endLine();
                }
            }
            String detail = symbol.getKind() + (symbol.getSignature() != null
                    ? " · " + symbol.getSignature() : "")
                    + (symbol.getJavadocSummary() != null ? " · " + symbol.getJavadocSummary() : "");
            selected.putIfAbsent("symbol:" + symbol.getQualifiedName(),
                    new EvidenceItem(null, EvidenceKind.SYMBOL,
                            symbol.getQualifiedName() + " (" + symbol.getKind() + ")",
                            truncate(detail, MAX_DETAIL_CHARS), path, start, end,
                            symbol.getQualifiedName(), symbol.getKind().name(), excerpt,
                            entry.score(), "symbol-name"));
            accepted++;
        }
    }

    private void retrieveFiles(UUID revisionId, List<String> tokens,
                               Map<String, EvidenceItem> selected) {
        Map<UUID, Scored<SourceFile>> found = new LinkedHashMap<>();
        for (String token : tokens) {
            for (SourceFile file : sourceFiles.searchByPath(revisionId, token, PageRequest.of(0, 5))) {
                int hits = hits(tokens, file.getRelativePath());
                double score = 20 + 5.0 * hits;
                Scored<SourceFile> existing = found.get(file.getId());
                if (existing == null || existing.score() < score) {
                    found.put(file.getId(), new Scored<>(file, score));
                }
            }
        }
        List<Scored<SourceFile>> ranked = new ArrayList<>(found.values());
        ranked.sort(Comparator.comparingDouble(Scored<SourceFile>::score).reversed()
                .thenComparing(entry -> entry.value().getRelativePath()));

        int accepted = 0;
        for (Scored<SourceFile> entry : ranked) {
            if (accepted >= MAX_FILES) {
                break;
            }
            SourceFile file = entry.value();
            selected.putIfAbsent("file:" + file.getRelativePath(),
                    new EvidenceItem(null, EvidenceKind.FILE, file.getRelativePath(),
                            file.getLanguage() + " · " + file.getLineCount() + " line(s)"
                                    + (file.getPackageName() != null ? " · package " + file.getPackageName() : ""),
                            file.getRelativePath(), null, null, null, null, null,
                            entry.score(), "file-path"));
            accepted++;
        }
    }

    private void retrieveRuntime(UUID repositoryId, String question,
                                 Map<String, EvidenceItem> selected, List<String> notes) {
        if (!QuestionTokens.isRuntimeQuestion(question)) {
            return;
        }
        Optional<AnalysisRun> newest = runs.findFirstByRepositoryIdAndRunTypeInOrderByStartedAtDesc(
                repositoryId, List.of(AnalysisRunType.JFR_ANALYSIS, AnalysisRunType.HEAP_ANALYSIS,
                        AnalysisRunType.THREAD_DUMP_ANALYSIS, AnalysisRunType.BENCHMARK_IMPORT));
        if (newest.isEmpty()) {
            notes.add("The question is about runtime behaviour, but no runtime artefact has been "
                    + "analysed for this repository, so no measurement was retrieved.");
            return;
        }
        AnalysisRun run = newest.get();
        String detail = run.getRunType() + " · " + truncate(run.getSummary(), MAX_DETAIL_CHARS)
                + " · the individual measurements are not retrieved; the Performance Lab reads them.";
        selected.putIfAbsent("runtime:" + run.getId(),
                new EvidenceItem(null, EvidenceKind.MEASUREMENT,
                        "newest runtime run: " + run.getRunType(), detail,
                        null, null, null, null, null, null, 15, "runtime-run"));
        notes.add("Runtime evidence is the newest run's summary. Individual measurements are not "
                + "retrieved in this build.");
    }

    private void addRepositorySummary(UUID revisionId, Map<String, EvidenceItem> selected,
                                      List<String> notes) {
        long files = sourceFiles.countByRevisionId(revisionId);
        long symbolCount = symbols.countByRevisionId(revisionId);
        String detail = files + " indexed source file(s), " + symbolCount + " symbol(s) in revision "
                + revisionId + ". Counts describe what the index holds, not what the code does.";
        selected.putIfAbsent("repository:" + revisionId,
                new EvidenceItem(null, EvidenceKind.REPOSITORY, "repository summary", detail,
                        null, null, null, null, null, null, 5, "repository-counts"));
        if (files == 0) {
            notes.add("The indexed revision has no source files.");
        }
    }

    private Map<UUID, String> filePaths(List<UUID> fileIds) {
        List<UUID> distinct = fileIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> paths = new LinkedHashMap<>();
        for (SourceFile file : sourceFiles.findAllById(distinct)) {
            paths.put(file.getId(), file.getRelativePath());
        }
        return paths;
    }

    private static boolean isType(SymbolKind kind) {
        return kind == SymbolKind.CLASS || kind == SymbolKind.INTERFACE
                || kind == SymbolKind.ENUM || kind == SymbolKind.RECORD
                || kind == SymbolKind.ANNOTATION;
    }

    private static int hits(List<String> tokens, String... fields) {
        int hits = 0;
        for (String token : tokens) {
            for (String field : fields) {
                if (field != null && field.toLowerCase(java.util.Locale.ROOT).contains(token)) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }

    private static String summarise(List<EvidenceItem> items, List<String> tokens) {
        if (items.isEmpty()) {
            return "No indexed evidence matched the question.";
        }
        Map<EvidenceKind, Integer> counts = new LinkedHashMap<>();
        for (EvidenceItem item : items) {
            counts.merge(item.kind(), 1, Integer::sum);
        }
        StringBuilder summary = new StringBuilder().append(items.size()).append(" item(s) of evidence");
        summary.append(" (").append(counts.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey().label()
                        + (entry.getValue() == 1 ? "" : "s"))
                .reduce((left, right) -> left + ", " + right).orElse("none")).append(")");
        if (!tokens.isEmpty()) {
            summary.append(", retrieved by name and text matching for ")
                    .append(String.join(", ", tokens));
        }
        summary.append(".");
        return summary.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private record Scored<T>(T value, double score) {
    }
}
