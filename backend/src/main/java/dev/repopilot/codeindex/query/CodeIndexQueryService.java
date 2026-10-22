package dev.repopilot.codeindex.query;

import dev.repopilot.codeindex.model.EdgeResolution;
import dev.repopilot.codeindex.model.EdgeType;
import dev.repopilot.codeindex.model.MappingType;
import dev.repopilot.codeindex.parse.ParseStatus;
import dev.repopilot.codeindex.persistence.CodeSymbol;
import dev.repopilot.codeindex.persistence.CodeSymbolRepository;
import dev.repopilot.codeindex.persistence.DependencyEdge;
import dev.repopilot.codeindex.persistence.DependencyEdgeRepository;
import dev.repopilot.codeindex.persistence.JavaMappingRepository;
import dev.repopilot.codeindex.persistence.SourceFile;
import dev.repopilot.codeindex.persistence.SourceFileRepository;
import dev.repopilot.repository.RepositoryRevision;
import dev.repopilot.repository.RepositoryRevisionRepository;
import dev.repopilot.repository.ScanAccounting;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Universe limits are lower than the edge-list limits on purpose. The renderer draws every
     * returned edge, so the cost of the last edge is not a row in a table: it is a frame budget.
     */
    private static final int UNIVERSE_DEFAULT_LIMIT = 1_400;
    private static final int UNIVERSE_MAX_LIMIT = 6_000;

    /** Enough external targets to show the shape of the dependency surface without a second hairball. */
    private static final int UNIVERSE_EXTERNAL_MAX = 220;

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

    /**
     * One indexed file by its repository-relative path.
     *
     * <p>The explorer's first file page is bounded, so a deep link to a file that sorts
     * after that page cannot be resolved from the page alone. This lookup keeps the
     * deep link independent of page size without shipping every file to the browser.
     */
    @Transactional(readOnly = true)
    public Optional<SourceFileView> fileByPath(UUID revisionId, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return Optional.empty();
        }
        return sourceFiles.findByRevisionIdAndRelativePath(revisionId, relativePath.strip())
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

    /**
     * A bounded, renderable slice of the dependency universe.
     *
     * <p>The assembly happens here rather than in the browser because the node metadata the
     * inspector needs — qualified name, file, package, line range, complexity — otherwise costs
     * one request per node. One read transaction loads the edge slice, then only the symbols and
     * files that slice actually touches.
     *
     * <p>Targets that do not resolve to an indexed declaration are aggregated by name into
     * external nodes, most-referenced first, capped at {@value #UNIVERSE_EXTERNAL_MAX}. An edge
     * whose external target did not make the cap is dropped, because an edge to a node the
     * payload does not contain would render as a line to nowhere. This is why the returned edge
     * count can be lower than the fetched row count and the caller must not infer completeness
     * from it.
     */
    @Transactional(readOnly = true)
    public UniverseView universe(UUID revisionId, List<EdgeType> types, Integer limit) {
        List<EdgeType> effective = (types == null || types.isEmpty()) ? DEFAULT_GRAPH_TYPES : types;
        int effectiveLimit = limit == null || limit <= 0
                ? UNIVERSE_DEFAULT_LIMIT
                : Math.min(limit, UNIVERSE_MAX_LIMIT);

        long totalEdges = edges.countByRevisionIdAndTypes(revisionId, effective);
        List<DependencyEdge> rows = edges
                .findPageByRevisionIdAndTypes(revisionId, effective, PageRequest.of(0, effectiveLimit))
                .getContent();

        Set<UUID> symbolIds = new HashSet<>();
        for (DependencyEdge edge : rows) {
            if (edge.getSourceSymbolId() != null) {
                symbolIds.add(edge.getSourceSymbolId());
            }
            if (edge.getTargetSymbolId() != null) {
                symbolIds.add(edge.getTargetSymbolId());
            }
        }
        Map<UUID, CodeSymbol> symbolsById = new HashMap<>();
        for (CodeSymbol symbol : symbols.findAllById(symbolIds)) {
            symbolsById.put(symbol.getId(), symbol);
        }

        Set<UUID> fileIds = new HashSet<>();
        for (CodeSymbol symbol : symbolsById.values()) {
            if (symbol.getSourceFileId() != null) {
                fileIds.add(symbol.getSourceFileId());
            }
        }
        Map<UUID, SourceFile> filesById = new HashMap<>();
        for (SourceFile file : sourceFiles.findAllById(fileIds)) {
            filesById.put(file.getId(), file);
        }

        // Count how often each unresolved target is referenced before deciding which external
        // nodes are worth drawing. A target referenced once is usually noise; the shared types
        // are what makes the boundary visible.
        Map<String, Integer> externalCounts = new LinkedHashMap<>();
        for (DependencyEdge edge : rows) {
            if (isResolvedInside(edge, symbolsById)) {
                continue;
            }
            String name = externalName(edge.getTargetName());
            if (name != null) {
                externalCounts.merge(name, 1, Integer::sum);
            }
        }
        Set<String> includedExternal = new HashSet<>();
        externalCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(UNIVERSE_EXTERNAL_MAX)
                .forEach(entry -> includedExternal.add(entry.getKey()));

        List<UniverseNode> nodes = new ArrayList<>(symbolsById.size() + includedExternal.size());
        for (CodeSymbol symbol : symbolsById.values()) {
            SourceFile file = filesById.get(symbol.getSourceFileId());
            nodes.add(new UniverseNode(
                    symbol.getId().toString(),
                    symbol.getKind().name(),
                    symbol.getName(),
                    symbol.getQualifiedName(),
                    file == null ? null : file.getRelativePath(),
                    file == null ? null : file.getPackageName(),
                    symbol.getStartLine(),
                    symbol.getEndLine(),
                    symbol.getComplexity(),
                    symbol.getParentSymbolId() == null ? null : symbol.getParentSymbolId().toString(),
                    false));
        }
        for (String external : includedExternal) {
            nodes.add(new UniverseNode(
                    externalId(external),
                    "EXTERNAL",
                    external,
                    external,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true));
        }
        nodes.sort(Comparator
                .comparing(UniverseNode::external)
                .thenComparing(node -> node.packageName() == null ? "" : node.packageName())
                .thenComparing(node -> node.qualifiedName() == null ? "" : node.qualifiedName())
                .thenComparing(UniverseNode::id));

        List<UniverseEdge> edges = new ArrayList<>(rows.size());
        for (DependencyEdge edge : rows) {
            if (edge.getSourceSymbolId() == null || !symbolsById.containsKey(edge.getSourceSymbolId())) {
                continue;
            }
            String target;
            if (isResolvedInside(edge, symbolsById)) {
                target = edge.getTargetSymbolId().toString();
            } else {
                String external = externalName(edge.getTargetName());
                if (external == null || !includedExternal.contains(external)) {
                    continue;
                }
                target = externalId(external);
            }
            edges.add(new UniverseEdge(
                    edge.getId().toString(),
                    edge.getSourceSymbolId().toString(),
                    target,
                    edge.getEdgeType().name(),
                    edge.getResolution().name(),
                    edge.getSourceLine()));
        }

        return new UniverseView(
                revisionId,
                nodes,
                edges,
                symbols.countByRevisionId(revisionId),
                totalEdges,
                effectiveLimit,
                totalEdges > rows.size());
    }

    private static boolean isResolvedInside(DependencyEdge edge, Map<UUID, CodeSymbol> symbolsById) {
        return edge.getTargetSymbolId() != null && symbolsById.containsKey(edge.getTargetSymbolId());
    }

    /**
     * The display name of an unresolved target.
     *
     * <p>Parser output occasionally wraps a target in generic arguments or a method call. Those
     * characters are not part of a name, and two spellings of the same type should aggregate into
     * one node, so only the leading identifier chain is kept.
     */
    private static String externalName(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        int cut = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<' || c == '(' || c == '[' || Character.isWhitespace(c)) {
                cut = i;
                break;
            }
        }
        String name = value.substring(0, cut).strip();
        if (name.isEmpty()) {
            return null;
        }
        return name.length() > 180 ? name.substring(0, 180) : name;
    }

    private static String externalId(String name) {
        return "external:" + name;
    }
}
