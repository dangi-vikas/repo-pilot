package dev.repopilot.codeindex.index;

import dev.repopilot.codeindex.model.EdgeResolution;
import dev.repopilot.codeindex.model.SymbolKind;
import dev.repopilot.codeindex.parse.ParseStatus;
import dev.repopilot.codeindex.persistence.CodeSymbol;
import dev.repopilot.codeindex.persistence.CodeSymbolRepository;
import dev.repopilot.codeindex.persistence.DependencyEdge;
import dev.repopilot.codeindex.persistence.DependencyEdgeRepository;
import dev.repopilot.codeindex.persistence.JavaMappingRepository;
import dev.repopilot.codeindex.persistence.SourceFile;
import dev.repopilot.codeindex.persistence.SourceFileRepository;
import dev.repopilot.codeindex.query.CodeIndexQueryService;
import dev.repopilot.codeindex.query.CoverageView;
import dev.repopilot.codeindex.query.GraphView;
import dev.repopilot.codeindex.query.SourceFileView;
import dev.repopilot.codeindex.query.UniverseEdge;
import dev.repopilot.codeindex.query.UniverseNode;
import dev.repopilot.codeindex.query.UniverseView;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.RepositoryStatus;
import dev.repopilot.repository.RepositorySummary;
import dev.repopilot.workspace.WorkspaceService;
import dev.repopilot.workspace.WorkspaceSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end indexing test: enumerate, parse, persist, resolve.
 *
 * <p>Runs against the {@code local} profile (embedded H2) so no Docker is required.
 * The workspace root and data directory are isolated temporary directories, which is
 * also what exercises the sandbox's refusal to index anything overlapping its own
 * data directory indirectly (they are siblings here).
 */
@SpringBootTest(properties = {
        "repopilot.security.token=integration-test-bearer-token-0001",
        "repopilot.ai.enabled=false"
})
class RepositoryIndexingIntegrationTest {

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
    private CodeIndexQueryService codeIndex;
    @Autowired
    private SourceFileRepository sourceFiles;
    @Autowired
    private CodeSymbolRepository symbols;
    @Autowired
    private DependencyEdgeRepository edges;
    @Autowired
    private JavaMappingRepository mappings;

    @Test
    @DisplayName("indexes a small Java repository and records honest coverage")
    void indexesSampleRepository() throws IOException {
        Path repository = workspaceRoot.resolve("sample");
        writeJava(repository, "com/example/orders/Order.java", """
                package com.example.orders;

                public record Order(String id, long total) {
                    public boolean isLarge() {
                        return total > 1000;
                    }
                }
                """);
        writeJava(repository, "com/example/orders/OrderService.java", """
                package com.example.orders;

                import org.springframework.stereotype.Service;

                @Service
                public class OrderService {
                    private final OrderRepository repository;

                    public OrderService(OrderRepository repository) {
                        this.repository = repository;
                    }

                    public Order place(Order order) {
                        if (order.isLarge()) {
                            return order;
                        }
                        return repository.save(order);
                    }
                }
                """);
        writeJava(repository, "com/example/orders/OrderRepository.java", """
                package com.example.orders;

                public interface OrderRepository {
                    Order save(Order order);
                }
                """);
        writeJava(repository, "com/example/orders/Broken.java", """
                package com.example.orders;
                public class Broken { void x( { } }
                """);

        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);

        RepositorySummary registered = repositories.register(workspace.id(), "sample",
                repository.toAbsolutePath().toString());
        assertThat(registered.status()).isEqualTo(RepositoryStatus.REGISTERED);

        IndexOutcome outcome = indexer.index(registered.id(), null);

        // JavaParser is a recovering parser: the syntactically broken file still yields a
        // tree, so it counts as indexed and its problems are recorded — it is not silently
        // dropped, and it is not fabricated into a clean parse either.
        assertThat(outcome.filesIndexed()).isEqualTo(4);
        assertThat(outcome.parseFailures()).isEqualTo(0);
        assertThat(outcome.symbols()).isGreaterThan(0);
        assertThat(outcome.revisionId()).isNotNull();

        RepositorySummary after = repositories.require(registered.id());
        assertThat(after.status()).isEqualTo(RepositoryStatus.READY);
        assertThat(after.analyzedFileCount()).isEqualTo(4);

        CoverageView coverage = codeIndex.coverage(outcome.revisionId());
        assertThat(coverage.totalFiles()).isEqualTo(4);
        assertThat(coverage.parsedFiles()).isEqualTo(3);
        assertThat(coverage.problemFiles()).isEqualTo(1);
        assertThat(coverage.failedFiles()).isEqualTo(0);
        assertThat(coverage.readableFiles()).isEqualTo(4);

        // The file with problems is recorded with a non-blank reason, so the code explorer
        // can show why it was only partially understood.
        SourceFile broken = sourceFiles
                .findByRevisionIdAndRelativePath(outcome.revisionId(), "com/example/orders/Broken.java")
                .orElseThrow();
        assertThat(broken.getParseStatus()).isEqualTo(ParseStatus.PARSED_WITH_PROBLEMS);
        assertThat(broken.getParseError()).isNotBlank();

        // A deep link resolves by path, not by position in the first file page. Unknown
        // paths stay absent so the API can answer 404 instead of inventing a file.
        SourceFileView orderByPath = codeIndex
                .fileByPath(outcome.revisionId(), "com/example/orders/Order.java")
                .orElseThrow();
        assertThat(orderByPath.relativePath()).isEqualTo("com/example/orders/Order.java");
        assertThat(orderByPath.parseStatus()).isEqualTo(ParseStatus.PARSED);
        assertThat(codeIndex.fileByPath(outcome.revisionId(), "com/example/orders/Missing.java")).isEmpty();
        assertThat(codeIndex.fileByPath(outcome.revisionId(), "  ")).isEmpty();

        // The service references Order and OrderRepository, both declared in this revision,
        // so at least one edge must be internal rather than external.
        assertThat(coverage.edgeCount()).isGreaterThan(0);
        assertThat(coverage.resolvedEdges() + coverage.inferredEdges()).isGreaterThan(0);

        // Both of those declarations live in the package the referencing file declares, which
        // is the one case name resolution can prove without a classpath. Before the
        // same-package rule these edges were only INFERRED and nothing was ever RESOLVED.
        assertThat(coverage.resolvedEdges()).isGreaterThan(0);

        // Re-indexing the same content is idempotent: the revision is reused and the
        // derived rows are replaced, not duplicated.
        long edgesBefore = edges.countByRevisionId(outcome.revisionId());
        IndexOutcome second = indexer.index(registered.id(), null);
        assertThat(second.revisionId()).isEqualTo(outcome.revisionId());
        assertThat(edges.countByRevisionId(outcome.revisionId())).isEqualTo(edgesBefore);
        assertThat(sourceFiles.countByRevisionId(outcome.revisionId())).isEqualTo(4);
    }

    @Test
    @DisplayName("resolves an external JDK reference as EXTERNAL rather than inventing a target")
    void marksUnknownTargetsExternal() throws IOException {
        Path repository = workspaceRoot.resolve("external");
        writeJava(repository, "com/example/UsesList.java", """
                package com.example;

                import java.util.ArrayList;
                import java.util.List;

                public class UsesList {
                    public List<String> names() {
                        return new ArrayList<String>();
                    }
                }
                """);

        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        RepositorySummary registered = repositories.register(workspace.id(), "external",
                repository.toAbsolutePath().toString());
        IndexOutcome outcome = indexer.index(registered.id(), null);

        long external = edges.countByRevisionIdAndResolution(outcome.revisionId(), EdgeResolution.EXTERNAL);
        assertThat(external).isGreaterThan(0);
        assertThat(mappings.countByRevisionId(outcome.revisionId())).isGreaterThanOrEqualTo(0);

        // Nothing declared here is called List, ArrayList or String, so none of the recorded
        // names may be qualified into the file's own package. Qualifying a java.lang type
        // would be an invented location and a false statement about the code.
        assertThat(edges.findDistinctTargetNames(outcome.revisionId()))
                .doesNotContain("com.example.List", "com.example.ArrayList", "com.example.String");
        assertThat(edges.countByRevisionIdAndResolution(outcome.revisionId(), EdgeResolution.RESOLVED))
                .isZero();
    }

    @Test
    @DisplayName("proves a same-package reference and leaves an imported one unproven")
    void resolvesSamePackageReferences() throws IOException {
        Path repository = workspaceRoot.resolve("packages");
        writeJava(repository, "com/example/orders/Order.java", """
                package com.example.orders;

                public record Order(String id) {
                }
                """);
        writeJava(repository, "com/example/orders/OrderService.java", """
                package com.example.orders;

                public class OrderService {
                    Order find() {
                        return new Order("1");
                    }
                }
                """);
        // Same declaration, reached through an explicit import in a different package. The
        // parser records the simple name, and RepoPilot does not track imports, so this edge
        // must stay INFERRED instead of being claimed as proven.
        writeJava(repository, "com/example/api/OrderApi.java", """
                package com.example.api;

                import com.example.orders.Order;

                public class OrderApi {
                    Order find() {
                        return new Order("2");
                    }
                }
                """);

        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        RepositorySummary registered = repositories.register(workspace.id(), "packages",
                repository.toAbsolutePath().toString());
        IndexOutcome outcome = indexer.index(registered.id(), null);

        CodeSymbol orderType = typeNamed(outcome.revisionId(), "com.example.orders.Order");

        // Both the return type and the instantiation in OrderService name a declaration in
        // OrderService's own package, so every one of them is proven.
        List<DependencyEdge> samePackage =
                edgesTo(outcome.revisionId(), "com/example/orders/OrderService.java", "Order");
        assertThat(samePackage).isNotEmpty();
        assertThat(samePackage).allSatisfy(edge -> {
            assertThat(edge.getResolution()).isEqualTo(EdgeResolution.RESOLVED);
            assertThat(edge.getTargetSymbolId()).isEqualTo(orderType.getId());
        });

        List<DependencyEdge> imported =
                edgesTo(outcome.revisionId(), "com/example/api/OrderApi.java", "Order");
        assertThat(imported).isNotEmpty();
        assertThat(imported).allSatisfy(edge -> {
            assertThat(edge.getResolution()).isEqualTo(EdgeResolution.INFERRED);
            // Still pointed at the only declaration with that simple name, but not asserted as
            // the referent: an unproven edge names a candidate, it does not prove one.
            assertThat(edge.getTargetSymbolId()).isEqualTo(orderType.getId());
        });
    }

    private CodeSymbol typeNamed(java.util.UUID revisionId, String qualifiedName) {
        return symbols.findByRevisionIdAndKindOrderByQualifiedNameAsc(revisionId, SymbolKind.RECORD).stream()
                .filter(symbol -> qualifiedName.equals(symbol.getQualifiedName()))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Every edge declared in {@code sourcePath} whose recorded target is {@code targetName}.
     *
     * <p>Edges are gathered through the symbols the file declares, because that is how they
     * are attached: a return type belongs to its method, not to the enclosing type.
     */
    private List<DependencyEdge> edgesTo(java.util.UUID revisionId, String sourcePath, String targetName) {
        SourceFile file = sourceFiles.findByRevisionIdAndRelativePath(revisionId, sourcePath).orElseThrow();
        return symbols.findBySourceFileIdOrderByStartLineAsc(file.getId()).stream()
                .flatMap(symbol -> edges.findBySourceSymbolIdOrderBySourceLineAsc(symbol.getId()).stream())
                .filter(edge -> targetName.equals(edge.getTargetName()))
                .toList();
    }

    @Test
    @DisplayName("reports what the walker deliberately did not read")
    void reportsExcludedFilesFromTheScan() throws IOException {
        Path repository = workspaceRoot.resolve("exclusions");
        writeJava(repository, "com/example/kept/Kept.java", """
                package com.example.kept;

                public class Kept {
                }
                """);
        // Never opened: both names are on the credential list.
        writeJava(repository, "config/credentials.json", "{ \"apiKey\": \"not-a-real-key\" }");
        writeJava(repository, "config/id_rsa", "not-a-real-private-key");
        // Inspected and reported, but not a language RepoPilot parses.
        writeJava(repository, "notes.md", "# notes");
        // Read and recorded, but nothing to parse it with yet. It must not be reported as a
        // Java parse that ran and had problems.
        writeJava(repository, "src/main/resources/application.properties", "server.port=8080\n");
        // Never walked at all, so no file count can be given for its contents.
        writeJava(repository, "target/classes/Kept.class", "not-really-bytecode");

        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        RepositorySummary registered = repositories.register(workspace.id(), "exclusions",
                repository.toAbsolutePath().toString());
        IndexOutcome outcome = indexer.index(registered.id(), null);
        CoverageView coverage = codeIndex.coverage(outcome.revisionId());

        assertThat(coverage.parsedFiles()).isEqualTo(1);
        // A .properties file is read and recorded, not handed to the Java parser. Reporting
        // it as "parsed with problems" would claim a parse that never happened.
        assertThat(coverage.recordedFiles()).isEqualTo(1);
        assertThat(coverage.problemFiles()).isZero();
        assertThat(coverage.failedFiles()).isZero();
        // The excluded count cannot be derived from rows: an excluded file never becomes
        // one. Before the scan accounting was persisted this number was always zero.
        assertThat(coverage.excludedFiles()).isEqualTo(3);
        assertThat(coverage.excludedByReason())
                .contains("SECRET_BEARING=2")
                .contains("UNSUPPORTED_LANGUAGE=1");
        // Every inspected file was either read or deliberately skipped.
        assertThat(coverage.enumeratedFiles())
                .isEqualTo(coverage.totalFiles() + coverage.excludedFiles());
        // A pruned directory hides an unknown number of unseen files, so it is reported as
        // a directory rather than converted into a fabricated file count.
        assertThat(coverage.prunedDirectories()).isEqualTo(1);
        assertThat(coverage.prunedByReason()).contains("BUILD_OUTPUT=1");
        // Excluded before any read means no row at all, not a row with the content dropped.
        assertThat(sourceFiles.findByRevisionIdOrderByRelativePathAsc(outcome.revisionId()))
                .extracting(SourceFile::getRelativePath)
                .noneMatch(path -> path.contains("credentials") || path.contains("id_rsa"));
        // The job result states the exclusions, so the history shows them without a query.
        assertThat(outcome.summary())
                .contains("3 file(s) excluded")
                .contains("SECRET_BEARING=2")
                .contains("1 recorded without a language analyzer");
    }

    @Test
    @DisplayName("bounds the graph view and states the total it sliced")
    void boundsTheGraphView() throws IOException {
        Path repository = workspaceRoot.resolve("graph-bounds");
        writeJava(repository, "com/example/g/Thing.java", """
                package com.example.g;

                public class Thing {
                }
                """);
        writeJava(repository, "com/example/g/Uses.java", """
                package com.example.g;

                public class Uses {
                    Thing a;
                    Thing b;
                    Thing c;
                    Thing d;
                    Thing e;
                    Thing f;

                    Thing make() {
                        return new Thing();
                    }
                }
                """);

        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        RepositorySummary registered = repositories.register(workspace.id(), "graph-bounds",
                repository.toAbsolutePath().toString());
        IndexOutcome outcome = indexer.index(registered.id(), null);

        // The un-bounded version of this endpoint returned 100 801 edges — 22 MiB of JSON in
        // 50 seconds — for the 3 000-file benchmark corpus. A graph response that cannot be
        // bounded is not a usable endpoint, so the default is bounded and the envelope says
        // how many edges matched.
        GraphView whole = codeIndex.graphEdges(outcome.revisionId(), null, null);
        assertThat(whole.items()).isNotEmpty();
        assertThat(whole.truncated()).isFalse();
        assertThat(whole.totalEdges()).isEqualTo(whole.items().size());

        GraphView slice = codeIndex.graphEdges(outcome.revisionId(), null, 2);
        assertThat(slice.items()).hasSize(2);
        assertThat(slice.limit()).isEqualTo(2);
        // "Two of many" and "two of two" must be distinguishable, or a caller cannot tell a
        // complete graph from the first slice of one.
        assertThat(slice.totalEdges()).isEqualTo(whole.totalEdges());
        assertThat(slice.truncated()).isTrue();

        // A client cannot escape the bound by asking for an enormous page.
        GraphView capped = codeIndex.graphEdges(outcome.revisionId(), null, 1_000_000);
        assertThat(capped.limit()).isLessThan(1_000_000);
        assertThat(capped.items().size()).isLessThanOrEqualTo(capped.limit());
    }

    @Test
    @DisplayName("returns a renderable universe whose every edge has a node at both ends")
    void buildsARenderableUniverse() throws IOException {
        Path repository = workspaceRoot.resolve("universe");
        writeJava(repository, "com/example/u/Thing.java", """
                package com.example.u;

                public class Thing {
                }
                """);
        writeJava(repository, "com/example/u/Uses.java", """
                package com.example.u;

                import java.util.List;

                public class Uses {
                    Thing thing;
                    List<String> names;

                    Thing make() {
                        return new Thing();
                    }
                }
                """);

        workspaces.ensureDefaultWorkspace();
        WorkspaceSummary workspace = workspaces.listSummaries().get(0);
        RepositorySummary registered = repositories.register(workspace.id(), "universe",
                repository.toAbsolutePath().toString());
        IndexOutcome outcome = indexer.index(registered.id(), null);

        UniverseView universe = codeIndex.universe(outcome.revisionId(), null, null);
        assertThat(universe.nodes()).isNotEmpty();
        assertThat(universe.edges()).isNotEmpty();

        // A duplicate id would make two nodes indistinguishable to the renderer's maps and to
        // the inspector's lookups, so the endpoint guarantees uniqueness rather than leaving it
        // to the client to deduplicate.
        assertThat(universe.nodes()).extracting(UniverseNode::id).doesNotHaveDuplicates();

        var nodeIds = new java.util.HashSet<String>();
        universe.nodes().forEach(node -> nodeIds.add(node.id()));
        // Every edge must terminate at a node that is in the document. An edge to a node the
        // payload omitted draws as a line to nowhere, which is worse than omitting the edge.
        for (UniverseEdge edge : universe.edges()) {
            assertThat(nodeIds).contains(edge.source());
            assertThat(nodeIds).contains(edge.target());
        }

        // Repository nodes carry the metadata the inspector needs; external nodes must not
        // pretend to have a file, because there is nothing to open.
        for (UniverseNode node : universe.nodes()) {
            if (node.external()) {
                assertThat(node.kind()).isEqualTo("EXTERNAL");
                assertThat(node.path()).isNull();
            } else {
                assertThat(node.path()).isNotBlank();
                assertThat(node.kind()).isNotBlank();
            }
        }

        assertThat(universe.nodes()).anyMatch(node ->
                !node.external() && "Thing".equals(node.name()));
        assertThat(universe.nodes()).anyMatch(node ->
                node.external() && node.qualifiedName() != null
                        && node.qualifiedName().endsWith("List"));

        // The bound is a real bound, and truncation is stated rather than implied by a short list.
        UniverseView slice = codeIndex.universe(outcome.revisionId(), null, 1);
        assertThat(slice.edges()).hasSize(1);
        assertThat(slice.edgeLimit()).isEqualTo(1);
        assertThat(slice.truncated()).isTrue();
        assertThat(slice.totalEdges()).isEqualTo(universe.totalEdges());
    }

    private static void writeJava(Path root, String relativePath, String content) throws IOException {        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
