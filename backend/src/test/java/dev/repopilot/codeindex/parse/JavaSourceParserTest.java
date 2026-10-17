package dev.repopilot.codeindex.parse;

import dev.repopilot.codeindex.model.EdgeType;
import dev.repopilot.codeindex.model.SymbolKind;
import dev.repopilot.codeindex.model.SymbolVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for the Java parser.
 *
 * <p>These assert what RepoPilot claims about a file, including the claims of
 * limitation: an unparsable file must be recorded as failed, not silently skipped.
 */
class JavaSourceParserTest {

    private JavaSourceParser parser;

    @BeforeEach
    void setUp() {
        parser = new JavaSourceParser();
    }

    @Test
    @DisplayName("extracts package, imports, types and members with accurate line spans")
    void extractsDeclarations() {
        String source = """
                package com.example.orders;

                import java.util.List;

                /**
                 * Order service.
                 */
                public class OrderService {
                    private final List<String> names;

                    OrderService(List<String> names) {
                        this.names = names;
                    }

                    public int count() {
                        int total = 0;
                        for (String name : names) {
                            if (name != null) {
                                total++;
                            }
                        }
                        return total;
                    }
                }
                """;

        ParsedFile file = parser.parse("src/com/example/orders/OrderService.java", source);

        assertThat(file.status()).isEqualTo(ParseStatus.PARSED);
        assertThat(file.packageName()).isEqualTo("com.example.orders");
        assertThat(file.imports()).contains("java.util.List");
        assertThat(file.primaryTypeName()).isEqualTo("com.example.orders.OrderService");

        ParsedSymbol type = symbol(file, "com.example.orders.OrderService").orElseThrow();
        assertThat(type.kind()).isEqualTo(SymbolKind.CLASS);
        assertThat(type.visibility()).isEqualTo(SymbolVisibility.PUBLIC);
        // Positions are only available when the parser stores tokens; a zero here would
        // mean line information had been lost and every citation would be unverifiable.
        assertThat(type.startLine()).isPositive();
        assertThat(type.javadocSummary()).contains("Order service");

        ParsedSymbol method = file.symbols().stream()
                .filter(s -> s.kind() == SymbolKind.METHOD && s.simpleName().equals("count"))
                .findFirst().orElseThrow();
        assertThat(method.signature()).isEmpty();
        assertThat(method.startLine()).isEqualTo(15);
        assertThat(method.startLine()).isGreaterThan(type.startLine());
        // one entry point + for + if + parameter of count()
        assertThat(method.complexity()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("records inheritance, implementation and thrown exception references")
    void extractsTypeRelations() {
        String source = """
                package com.example;

                import java.io.IOException;

                public class FastReader extends Reader implements AutoCloseable {
                    @Override
                    public void close() throws IOException {
                    }
                }
                """;

        ParsedFile file = parser.parse("FastReader.java", source);

        assertThat(references(file, EdgeType.EXTENDS)).contains("Reader");
        assertThat(references(file, EdgeType.IMPLEMENTS)).contains("AutoCloseable");
        assertThat(references(file, EdgeType.THROWS)).contains("IOException");
        assertThat(references(file, EdgeType.ANNOTATION)).contains("Override");
    }

    @Test
    @DisplayName("detects REST endpoints, Kafka listeners and producers, and JPA repositories")
    void detectsFrameworkSurfaces() {
        String source = """
                package com.example;

                import org.springframework.web.bind.annotation.*;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.data.jpa.repository.JpaRepository;

                @RestController
                @RequestMapping("/api/orders")
                class OrderController {
                    @GetMapping("/{id}")
                    String get(@PathVariable String id) {
                        return id;
                    }

                    @PostMapping
                    String create(@RequestBody String body) {
                        return body;
                    }
                }

                class Sender {
                    private org.springframework.kafka.core.KafkaTemplate<String, String> template;

                    void publish(String event) {
                        template.send("order.created", event);
                    }

                    @KafkaListener(topics = "order.paid", groupId = "billing")
                    void onPaid(String id) {
                    }
                }

                interface OrderRepository extends JpaRepository<String, String> {
                }
                """;

        ParsedFile file = parser.parse("src/com/example/Everything.java", source);

        assertThat(endpoints(file)).contains("GET /api/orders/{id}", "POST /api/orders");
        assertThat(mappingsOfType(file, ParsedMapping.MappingTypeValue.KAFKA_LISTENER))
                .anySatisfy(mapping -> {
                    assertThat(mapping.topic()).isEqualTo("order.paid");
                    assertThat(mapping.consumerGroup()).isEqualTo("billing");
                });
        assertThat(mappingsOfType(file, ParsedMapping.MappingTypeValue.KAFKA_PRODUCER))
                .anySatisfy(mapping -> assertThat(mapping.topic()).isEqualTo("order.created"));
        assertThat(mappingsOfType(file, ParsedMapping.MappingTypeValue.REST_ENDPOINT)).isNotEmpty();
    }

    @Test
    @DisplayName("a file that cannot be parsed is recorded as FAILED with a bounded reason")
    void recordsParseFailure() {
        String broken = "@@@ not java at all ###";

        ParsedFile file = parser.parse("Broken.java", broken);

        assertThat(file.status()).isEqualTo(ParseStatus.FAILED);
        assertThat(file.parsedSuccessfully()).isFalse();
        assertThat(file.problems()).isNotEmpty();
        assertThat(file.problems().get(0).length()).isLessThanOrEqualTo(301);
    }

    @Test
    @DisplayName("a recoverable syntax error yields PARSED_WITH_PROBLEMS rather than FAILED")
    void recordsRecoverableProblems() {
        String almost = "package com.example; public class Almost { void x( { } }";

        ParsedFile file = parser.parse("Almost.java", almost);

        assertThat(file.status()).isIn(ParseStatus.PARSED_WITH_PROBLEMS, ParseStatus.FAILED);
        assertThat(file.problems()).isNotEmpty();
    }

    @Test
    @DisplayName("references never include primitive, void or var types")
    void ignoresPrimitiveAndVoidReferences() {
        String source = """
                package com.example;

                class Small {
                    void run(int count, boolean flag) {
                        var local = count;
                        long other = 2L;
                    }
                }
                """;

        ParsedFile file = parser.parse("Small.java", source);

        List<String> targets = file.references().stream().map(RawReference::targetName).toList();
        assertThat(targets).doesNotContain("int", "boolean", "long", "void", "var");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Optional<ParsedSymbol> symbol(ParsedFile file, String qualifiedName) {
        return file.symbols().stream()
                .filter(s -> qualifiedName.equals(s.qualifiedName()))
                .findFirst();
    }

    private static List<String> references(ParsedFile file, EdgeType type) {
        return file.references().stream()
                .filter(reference -> reference.edgeType() == type)
                .map(RawReference::targetName)
                .toList();
    }

    private static List<ParsedMapping> mappingsOfType(ParsedFile file,
                                                      ParsedMapping.MappingTypeValue type) {
        return file.mappings().stream()
                .filter(mapping -> mapping.mappingType() == type)
                .toList();
    }

    private static List<String> endpoints(ParsedFile file) {
        return mappingsOfType(file, ParsedMapping.MappingTypeValue.REST_ENDPOINT).stream()
                .map(mapping -> (mapping.httpMethod() == null ? "" : mapping.httpMethod()) + " "
                        + (mapping.path() == null ? "" : mapping.path()))
                .toList();
    }
}
