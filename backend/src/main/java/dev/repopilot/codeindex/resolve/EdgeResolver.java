package dev.repopilot.codeindex.resolve;

import dev.repopilot.codeindex.model.EdgeResolution;
import dev.repopilot.codeindex.model.EdgeType;
import dev.repopilot.codeindex.persistence.CodeSymbolRepository;
import dev.repopilot.codeindex.persistence.DependencyEdge;
import dev.repopilot.codeindex.persistence.DependencyEdgeRepository;
import dev.repopilot.codeindex.persistence.SourceFileRepository;
import dev.repopilot.jobs.JobContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assigns a {@link EdgeResolution} to every edge in a revision.
 *
 * <h2>Why resolution is a separate pass</h2>
 * An edge is written as {@link EdgeResolution#UNRESOLVED} while a file is parsed,
 * because at that moment the rest of the revision has not been seen. Resolving during
 * parsing would make the result depend on file order — the first file alphabetically
 * would appear to reference nothing. Resolution runs once, after every symbol is
 * persisted, against the complete name index.
 *
 * <h2>Honesty rules</h2>
 * <ul>
 *   <li>An exact qualified-name match against a type declared in this revision is
 *       {@code RESOLVED}.</li>
 *   <li>A simple name is looked up in the package of the file that wrote it, because Java
 *       resolves a same-package type before any on-demand import. That is also
 *       {@code RESOLVED}, and only when the revision actually declares the type — a name
 *       that is not declared there is never qualified into an invented location.</li>
 *   <li>A unique simple-name match is {@code INFERRED}: without a classpath RepoPilot
 *       cannot prove the simple name referred to that declaration.</li>
 *   <li>An ambiguous simple-name match is {@code INFERRED} with no target id. The
 *       ambiguity is preserved, never broken by an arbitrary tie-break.</li>
 *   <li>No match at all is {@code EXTERNAL}: a JDK or library type. The name is kept
 *       so the dependency boundary is visible.</li>
 * </ul>
 *
 * <p>Even {@code RESOLVED} means "proven from the source text and this revision's own
 * declarations", not "proven against a real classpath": RepoPilot does not compile the
 * repository, and a same-package claim assumes the revision is compilable.
 */
@Component
public class EdgeResolver {

    private static final Logger log = LoggerFactory.getLogger(EdgeResolver.class);

    /** Edges resolved per short transaction. Bounds both the entity cache and lock time. */
    private static final int PAGE_SIZE = 2000;
    /** Safety valve: an unbounded loop here would hang a worker, so cap the passes. */
    private static final int MAX_PASSES = 1_000_000;

    private final CodeSymbolRepository symbols;
    private final DependencyEdgeRepository edges;
    private final SourceFileRepository sourceFiles;
    private final TransactionTemplate transactions;

    public EdgeResolver(CodeSymbolRepository symbols,
                        DependencyEdgeRepository edges,
                        SourceFileRepository sourceFiles,
                        PlatformTransactionManager transactionManager) {
        this.symbols = symbols;
        this.edges = edges;
        this.sourceFiles = sourceFiles;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Resolves every unresolved edge of the revision.
     *
     * @return counts by outcome, for the job result and coverage reporting
     */
    public ResolutionSummary resolveRevision(UUID revisionId, JobContext context) {
        SymbolNameIndex index = transactions.execute(status -> {
            List<CodeSymbolLookup> lookups = symbols.findLookups(revisionId);
            return SymbolNameIndex.build(lookups);
        });
        if (index == null) {
            return new ResolutionSummary(0, 0, 0, 0);
        }

        Map<UUID, String> packagesByFile = readPackages(revisionId);

        int resolved = 0;
        int inferred = 0;
        int external = 0;
        int passes = 0;

        while (true) {
            if (context != null) {
                context.checkpoint();
            }
            if (++passes > MAX_PASSES) {
                log.error("resolution for revision {} exceeded {} passes; aborting to protect the worker",
                        revisionId, MAX_PASSES);
                break;
            }

            Outcome outcome = transactions.execute(status -> resolvePage(revisionId, index, packagesByFile));
            if (outcome == null || outcome.processed == 0) {
                break;
            }
            resolved += outcome.resolved;
            inferred += outcome.inferred;
            external += outcome.external;
            if (context != null && resolved + inferred + external > 0 && passes % 20 == 0) {
                context.progress(70, "Resolving references",
                        (resolved + inferred + external) + " references classified");
            }
        }

        log.info("revision {} resolution: {} resolved, {} inferred, {} external",
                revisionId, resolved, inferred, external);
        return new ResolutionSummary(resolved, inferred, external, 0);
    }

    private Outcome resolvePage(UUID revisionId, SymbolNameIndex index, Map<UUID, String> packagesByFile) {
        List<DependencyEdge> page = edges.findByRevisionIdAndResolution(
                revisionId, EdgeResolution.UNRESOLVED, PageRequest.of(0, PAGE_SIZE)).getContent();
        if (page.isEmpty()) {
            return new Outcome(0, 0, 0, 0);
        }

        int resolved = 0;
        int inferred = 0;
        int external = 0;
        for (DependencyEdge edge : page) {
            Classification classification = classify(edge, index, packagesByFile);
            edge.setResolution(classification.resolution);
            edge.setTargetSymbolId(classification.targetSymbolId);
            switch (classification.resolution) {
                case RESOLVED -> resolved++;
                case INFERRED -> inferred++;
                case EXTERNAL, UNRESOLVED -> external++;
            }
        }
        edges.saveAll(page);
        return new Outcome(resolved, inferred, external, page.size());
    }

    /** Package declared by each file of the revision, keyed by file id. */
    private Map<UUID, String> readPackages(UUID revisionId) {
        Map<UUID, String> packages = new HashMap<>();
        List<SourceFilePackage> files = transactions.execute(status -> sourceFiles.findPackages(revisionId));
        if (files == null) {
            return packages;
        }
        for (SourceFilePackage file : files) {
            if (file.packageName() != null && !file.packageName().isBlank()) {
                packages.put(file.fileId(), file.packageName().strip());
            }
        }
        return packages;
    }

    private Classification classify(DependencyEdge edge,
                                    SymbolNameIndex index,
                                    Map<UUID, String> packagesByFile) {
        String name = normalize(edge.getTargetName());
        if (name == null) {
            return new Classification(EdgeResolution.UNRESOLVED, null);
        }

        // An exact qualified match is the only case RepoPilot treats as proven.
        CodeSymbolLookup exact = index.typeByQualifiedName(name);
        if (exact != null) {
            return new Classification(EdgeResolution.RESOLVED, exact.id());
        }

        CodeSymbolLookup samePackage = samePackageLookup(edge, name, index, packagesByFile);
        if (samePackage != null) {
            return new Classification(EdgeResolution.RESOLVED, samePackage.id());
        }

        String simple = lastSegment(name);
        if (edge.getEdgeType() == EdgeType.CALLS) {
            List<CodeSymbolLookup> callables = index.callablesByName(simple);
            if (callables.size() == 1) {
                return new Classification(EdgeResolution.INFERRED, callables.get(0).id());
            }
            // Zero matches: a JDK or library method. Several matches: cannot choose.
            return new Classification(EdgeResolution.EXTERNAL, null);
        }

        if (edge.getEdgeType() == EdgeType.KAFKA_PUBLISH || edge.getEdgeType() == EdgeType.REST_ENDPOINT) {
            // These targets are topics and paths, not symbols. Recorded as external so
            // the graph shows the boundary without pretending a symbol exists.
            return new Classification(EdgeResolution.EXTERNAL, null);
        }

        List<CodeSymbolLookup> matches = index.typesBySimpleName(simple);
        if (matches.size() == 1) {
            return new Classification(EdgeResolution.INFERRED, matches.get(0).id());
        }
        if (matches.size() > 1) {
            // Ambiguous: the honest outcome is an unconfirmed edge with no chosen target.
            return new Classification(EdgeResolution.INFERRED, null);
        }
        return new Classification(EdgeResolution.EXTERNAL, null);
    }

    /**
     * Looks the name up in the package of the file that wrote the reference.
     *
     * <p>Java resolves a simple name to a type of the same package before consulting any
     * type-import-on-demand, so the lookup is a proof whenever it succeeds. It is only a
     * proof because the declaration has to exist in this revision: a {@code java.lang} or
     * library name such as {@code String} is never rewritten into a package that does not
     * contain it, it is simply not found here.
     */
    private CodeSymbolLookup samePackageLookup(DependencyEdge edge,
                                              String name,
                                              SymbolNameIndex index,
                                              Map<UUID, String> packagesByFile) {
        UUID fileId = edge.getSourceFileId();
        String packageName = fileId == null ? null : packagesByFile.get(fileId);
        return packageName == null ? null : index.typeByQualifiedName(packageName + "." + name);
    }

    /** Strips generic arguments and array suffixes so {@code List<Order>} indexes as {@code List}. */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        int generic = value.indexOf('<');
        if (generic >= 0) {
            value = value.substring(0, generic);
        }
        while (value.endsWith("[]")) {
            value = value.substring(0, value.length() - 2);
        }
        value = value.strip();
        return value.isEmpty() ? null : value;
    }

    static String lastSegment(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private record Classification(EdgeResolution resolution, UUID targetSymbolId) {
    }

    private record Outcome(int resolved, int inferred, int external, int processed) {
    }

    /** Outcome counts for a completed resolution pass. */
    public record ResolutionSummary(int resolved, int inferred, int external, int unresolved) {
        public int total() {
            return resolved + inferred + external + unresolved;
        }
    }
}
