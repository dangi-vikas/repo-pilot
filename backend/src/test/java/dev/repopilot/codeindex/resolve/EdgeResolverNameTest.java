package dev.repopilot.codeindex.resolve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the name normalisation the resolver depends on. */
class EdgeResolverNameTest {

    @Test
    @DisplayName("strips generic arguments so a parameterised type indexes by its raw name")
    void stripsGenerics() {
        assertThat(EdgeResolver.normalize("List<Order>")).isEqualTo("List");
        assertThat(EdgeResolver.normalize("Map<String, List<Order>>")).isEqualTo("Map");
        assertThat(EdgeResolver.normalize("  Optional <Foo> ")).isEqualTo("Optional");
    }

    @Test
    @DisplayName("strips array suffixes")
    void stripsArrays() {
        assertThat(EdgeResolver.normalize("Order[]")).isEqualTo("Order");
        assertThat(EdgeResolver.normalize("int[][]")).isEqualTo("int");
    }

    @Test
    @DisplayName("blank input yields null rather than an empty name")
    void blankIsNull() {
        assertThat(EdgeResolver.normalize("   ")).isNull();
        assertThat(EdgeResolver.normalize(null)).isNull();
    }

    @Test
    @DisplayName("lastSegment returns the simple name of a qualified name")
    void lastSegment() {
        assertThat(EdgeResolver.lastSegment("com.example.Order")).isEqualTo("Order");
        assertThat(EdgeResolver.lastSegment("Order")).isEqualTo("Order");
    }
}
