package dev.repopilot.codeindex.model;

import dev.repopilot.codeindex.parse.ParsedMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the deliberate duplication between the parser's mapping enum and the
 * persisted one. The parser must not depend on persistence, so the two are mirrored;
 * this test is what stops them drifting.
 */
class MappingTypeParityTest {

    @Test
    @DisplayName("the parser mapping enum and the persisted enum declare identical constants")
    void parserAndPersistedEnumsMatch() {
        Set<String> parserValues = Arrays.stream(ParsedMapping.MappingTypeValue.values())
                .map(Enum::name)
                .collect(Collectors.toSet());
        Set<String> persistedValues = Arrays.stream(MappingType.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertThat(persistedValues).isEqualTo(parserValues);
    }
}
