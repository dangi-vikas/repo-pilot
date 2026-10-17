package dev.repopilot.codeindex.query;

import dev.repopilot.codeindex.model.EdgeResolution;
import dev.repopilot.codeindex.model.EdgeType;
import dev.repopilot.codeindex.model.MappingType;
import dev.repopilot.codeindex.parse.ParseStatus;
import dev.repopilot.codeindex.persistence.CodeSymbolRepository;
import dev.repopilot.codeindex.persistence.DependencyEdgeRepository;
import dev.repopilot.codeindex.persistence.JavaMappingRepository;
import dev.repopilot.codeindex.persistence.SourceFileRepository;
import dev.repopilot.repository.RepositoryRevision;
import dev.repopilot.repository.RepositoryRevisionRepository;
import dev.repopilot.repository.ScanAccounting;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only queries over an indexed revision.
 *
 * <p>Every method is a short read transaction with no I/O and no model calls, so it is
 * safe to bridge from the reactive web layer with
 * {@link dev.repopilot.shared.reactive.Blocking#offload}.
 */
@Service
public class CodeIndexQueryService {

    /**
     * Edge types drawn by default. Injectable and delivery edges (annotations, thrown types,
     * dependency injection, Kafka, REST) are indexed and listed per symbol, but drawing them
     * turns a dependency graph into a hairball.
     */
    private static final List<EdgeType> DEFAULT_GRAPH_TYPES = List.of(
            EdgeType.EXTENDS, EdgeType.IMPLEMENTS, EdgeType.TYPE_REFERENCE,
            EdgeType.INSTANTIATES, EdgeType.CALLS);

    private static final int GRAPH_DEFAULT_LIMIT = 1_000;
    private static final int GRAPH_MAX_LIMIT = 20_000;

    private final SourceFileRepository sourceFiles;
    private final CodeSymbolRepository symbols;
    private final DependencyEdgeRepository edges;
    private final JavaMappingRepository mappings;
    private final RepositoryRevisionRepository revisions;

    public CodeIndexQueryService(SourceFileRepository sourceFiles,
                                 CodeSymbolRepository symbols,
                                 DependencyEdgeRepository edges,
                                 JavaMappingRepository mappings,
                                 RepositoryRevisionRepository revisions) {
        this.sourceFiles = sourceFiles;
        this.symbols = symbols;
        this.edges = edges;
        this.mappings = mappings;
        this.revisions = revisions;
    }

    @Transactional(readOnly = true)
    public Page<SourceFileView> listFiles(UUID revisionId, Pageable pageable) {
        return sourceFiles.findByRevisionIdOrderByRelativePathAsc(revisionId, pageable)
                .map(file -> new SourceFileView(file.getId(), file.getRelativePath(), file.getLanguage(),
                        file.getSizeBytes(), file.getLineCount(), file.getParseStatus(),
                        file.getParseError(), file.getPackageName()));
    }

    @Transactional(readOnly = true)
    public List<SymbolView> symbolsForFile(UUID sourceFileId) {
        return symbols.findBySourceFileIdOrderByStartLineAsc(sourceFileId).stream()
                .map(SymbolView::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<SymbolView> searchSymbols(UUID revisionId, String term, Pageable pageable) {
        String safeTerm = term == null ? "" : term.strip();
        return symbols.search(revisionId, safeTerm, pageable).map(SymbolView::from);
    }

    @Transactional(readOnly = true)
    public List<MappingView> mappings(UUID revisionId, MappingType type) {
        List<dev.repopilot.codeindex.persistence.JavaMapping> found = type == null
                ? mappings.findByRevisionIdOrderByMappingTypeAscPathAsc(revisionId)
                : mappings.findByRevisionIdAndMappingType(revisionId, type);
        return found.stream().map(MappingView::from).toList();
    }

    /** Incoming and outgoing references for a symbol, so the impact view needs one call. */
    @Transactional(readOnly = true)
    public List<EdgeView> edgesForSymbol(UUID symbolId) {
        List<EdgeView> outgoing = edges.findBySourceSymbolIdOrderBySourceLineAsc(symbolId).stream()
                .map(EdgeView::from).toList();
        List<EdgeView> incoming = edges.findByTargetSymbolIdOrderBySourceLineAsc(symbolId).stream()
                .map(EdgeView::from).toList();
        java.util.List<EdgeView> combined = new java.util.ArrayList<>(outgoing.size() + incoming.size());
        combined.addAll(outgoing);
        combined.addAll(incoming);
        return combined;
    }

    @Transactional(readOnly = true)
    public CoverageView coverage(UUID revisionId) {
        long total = sourceFiles.countByRevisionId(revisionId);
        long parsed = sourceFiles.countByRevisionIdAndParseStatus(revisionId, ParseStatus.PARSED);
        long problems = sourceFiles.countByRevisionIdAndParseStatus(revisionId, ParseStatus.PARSED_WITH_PROBLEMS);
        long failed = sourceFiles.countByRevisionIdAndParseStatus(revisionId, ParseStatus.FAILED);
        long recorded = sourceFiles.countByRevisionIdAndParseStatus(revisionId, ParseStatus.RECORDED);
        // Rows can only ever describe files that were read, so a status-based count here
        // would always report zero exclusions. The number comes from the scan manifest,
        // recorded on the revision when it was indexed.
        ScanAccounting accounting = revisions.findById(revisionId)
                .map(RepositoryRevision::scanAccounting)
                .orElse(ScanAccounting.NONE);
        long excluded = accounting.excludedFiles()
                + sourceFiles.countByRevisionIdAndParseStatus(revisionId, ParseStatus.EXCLUDED)
                + sourceFiles.countByRevisionIdAndParseStatus(revisionId, ParseStatus.SKIPPED);
        return new CoverageView(
                revisionId,
                total,
                parsed,
                problems,
                failed,
                recorded,
                excluded,
                accounting.enumeratedFiles(),
                accounting.prunedDirectories(),
                accounting.excludedByReason(),
                accounting.prunedByReason(),
                accounting.compiledClassesPresent(),
                symbols.countByRevisionId(revisionId),
                edges.countByRevisionId(revisionId),
                edges.countByRevisionIdAndResolution(revisionId, EdgeResolution.RESOLVED),
                edges.countByRevisionIdAndResolution(revisionId, EdgeResolution.INFERRED),
                edges.countByRevisionIdAndResolution(revisionId, EdgeResolution.EXTERNAL),
                mappings.countByRevisionId(revisionId));
    }

    /**
     * The graph view, bounded on purpose.
     *
     * <p>Returns at most {@code limit} edges plus the total that matched. An unbounded graph
     * response is not a feature: measured on the 3 000-file benchmark corpus, returning every
     * structural edge produced 22 MiB of JSON in 50 seconds, which makes the Dependency Graph
     * screen look broken on exactly the repositories where a dependency graph is useful.
     *
     * @param limit requested edge count; defaults to {@value #GRAPH_DEFAULT_LIMIT} and is
     *              capped at {@value #GRAPH_MAX_LIMIT} so a client cannot ask for the whole
     *              graph by setting a large number
     */
    @Transactional(readOnly = true)
    public GraphView graphEdges(UUID revisionId, List<EdgeType> types, Integer limit) {
        List<EdgeType> effective = (types == null || types.isEmpty()) ? DEFAULT_GRAPH_TYPES : types;
        int effectiveLimit = limit == null || limit <= 0
                ? GRAPH_DEFAULT_LIMIT
                : Math.min(limit, GRAPH_MAX_LIMIT);
        long total = edges.countByRevisionIdAndTypes(revisionId, effective);
        List<EdgeView> items = edges
                .findPageByRevisionIdAndTypes(revisionId, effective, PageRequest.of(0, effectiveLimit))
                .map(EdgeView::from)
                .getContent();
        return new GraphView(items, total, effectiveLimit, total > items.size());
    }
}
