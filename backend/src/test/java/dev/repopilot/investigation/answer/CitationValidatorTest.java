package dev.repopilot.investigation.answer;

import dev.repopilot.investigation.ClaimKind;
import dev.repopilot.investigation.ClaimVerification;
import dev.repopilot.investigation.EvidenceItem;
import dev.repopilot.investigation.EvidenceKind;
import dev.repopilot.investigation.SourceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for citation checking.
 *
 * <p>These are the product's evidence rule expressed as assertions. Each test names the kind of
 * dishonesty it prevents: a citation to something that was never sent, a sentence with no citation
 * wearing the authority of the ones beside it, a stale excerpt presented as current, and a line
 * number the citations do not actually cover.
 */
class CitationValidatorTest {

    private static final String PATH = "src/main/java/com/example/OrderService.java";

    @Test
    @DisplayName("marks every claim verified and the answer grounded when the citations resolve")
    void groundsAResolvableAnswer() {
        var items = List.of(evidence("E1", PATH, 10, 20, "class OrderService {"));
        var answer = answer("[E1] shows the class.",
                claim("The class exists.", "E1"));

        var result = CitationValidator.validate(answer, items, Map.of("E1", SourceStatus.CONFIRMED));

        assertThat(result.grounded()).isTrue();
        assertThat(result.claims()).singleElement()
                .satisfies(claim -> {
                    assertThat(claim.status()).isEqualTo(ClaimVerification.VERIFIED);
                    assertThat(claim.notes()).contains("re-read from disk");
                });
        assertThat(result.danglingMarkers()).isEmpty();
    }

    @Test
    @DisplayName("marks a claim citing a key that was never sent as unsupported, and names the key")
    void refusesAnUnknownKey() {
        var items = List.of(evidence("E1", PATH, 10, 20, "x"));
        var answer = answer("See [E1] and [E9].",
                claim("Something the prompt never contained.", "E9"));

        var result = CitationValidator.validate(answer, items, Map.of("E1", SourceStatus.CONFIRMED));

        assertThat(result.grounded()).isFalse();
        assertThat(result.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.status()).isEqualTo(ClaimVerification.UNSUPPORTED);
            assertThat(claim.notes()).contains("E9");
        });
        assertThat(result.danglingMarkers()).containsExactly("E9");
    }

    @Test
    @DisplayName("marks a claim with no citation as unsupported rather than letting it borrow the others")
    void refusesAnUncitedClaim() {
        var items = List.of(evidence("E1", PATH, 10, 20, "x"));
        var answer = answer("[E1] is fine.",
                claim("A fluent sentence with no citation.", new String[0]));

        var result = CitationValidator.validate(answer, items, Map.of("E1", SourceStatus.CONFIRMED));

        assertThat(result.grounded()).isFalse();
        assertThat(result.claims().get(0).status()).isEqualTo(ClaimVerification.UNSUPPORTED);
        assertThat(result.claims().get(0).notes()).contains("cites no evidence");
    }

    @Test
    @DisplayName("marks a claim partial when a cited excerpt no longer matches the file")
    void downgradesAChangedExcerpt() {
        var items = List.of(evidence("E1", PATH, 10, 20, "x"));
        var answer = answer("[E1] shows it.", claim("It exists.", "E1"));

        var result = CitationValidator.validate(answer, items, Map.of("E1", SourceStatus.CHANGED));

        assertThat(result.grounded()).isFalse();
        assertThat(result.claims().get(0).status()).isEqualTo(ClaimVerification.PARTIAL);
        assertThat(result.claims().get(0).notes()).contains("no longer matches");
    }

    @Test
    @DisplayName("marks a claim partial when a cited file has gone")
    void downgradesAMissingFile() {
        var items = List.of(evidence("E1", PATH, 10, 20, "x"));
        var answer = answer("[E1].", claim("It exists.", "E1"));

        var result = CitationValidator.validate(answer, items, Map.of("E1", SourceStatus.MISSING));

        assertThat(result.claims().get(0).status()).isEqualTo(ClaimVerification.PARTIAL);
        assertThat(result.claims().get(0).notes()).contains("can no longer be read");
    }

    @Test
    @DisplayName("accepts a claim that names a line its citation covers")
    void acceptsACoveredLocation() {
        var items = List.of(evidence("E1", PATH, 10, 20, "x"));
        var answer = answer("The bug is at OrderService.java:14.",
                claim("The bug is at OrderService.java:14.", "E1"));

        var result = CitationValidator.validate(answer, items, Map.of("E1", SourceStatus.CONFIRMED));

        assertThat(result.claims().get(0).status()).isEqualTo(ClaimVerification.VERIFIED);
    }

    @Test
    @DisplayName("marks a claim partial when it names a line its citations do not cover")
    void refusesAnUncoveredLocation() {
        var items = List.of(evidence("E1", PATH, 10, 20, "x"));
        var answer = answer("See OrderService.java:88.",
                claim("See OrderService.java:88.", "E1"));

        var result = CitationValidator.validate(answer, items, Map.of("E1", SourceStatus.CONFIRMED));

        assertThat(result.grounded()).isFalse();
        assertThat(result.claims().get(0).status()).isEqualTo(ClaimVerification.PARTIAL);
        assertThat(result.claims().get(0).notes()).contains("OrderService.java:88");
    }

    @Test
    @DisplayName("does not accept a location covered only by an uncited item")
    void refusesALocationCoveredByAnotherItem() {
        var items = List.of(evidence("E1", PATH, 10, 20, "x"),
                evidence("E2", "src/main/java/com/example/Other.java", 80, 90, "y"));
        var answer = answer("[E1].", claim("See Other.java:85.", "E1"));

        var result = CitationValidator.validate(answer, items, Map.of(
                "E1", SourceStatus.CONFIRMED, "E2", SourceStatus.CONFIRMED));

        assertThat(result.claims().get(0).status()).isEqualTo(ClaimVerification.PARTIAL);
    }

    @Test
    @DisplayName("treats evidence with no location as citable but not re-readable")
    void acceptsEvidenceWithoutALocation() {
        var items = List.of(new EvidenceItem("E1", EvidenceKind.REPOSITORY, "repository summary",
                "12 files", null, null, null, null, null, null, 5, "repository-counts"));
        var answer = answer("[E1].", claim("The repository has 12 files.", "E1"));

        var result = CitationValidator.validate(answer, items, Map.of("E1", SourceStatus.NOT_LOCATED));

        assertThat(result.grounded()).isTrue();
        assertThat(result.claims().get(0).notes()).contains("no file location");
    }

    @Test
    @DisplayName("is not grounded when the answer has no claims, however confident it sounds")
    void refusesAnAnswerWithNoClaims() {
        var items = List.of(evidence("E1", PATH, 10, 20, "x"));

        var result = CitationValidator.validate(answer("A prose answer with [E1].",
                        new InvestigationAnswer.AnswerClaim[0]),
                items, Map.of("E1", SourceStatus.CONFIRMED));

        assertThat(result.grounded()).isFalse();
        assertThat(result.claims()).isEmpty();
        assertThat(result.notes()).anySatisfy(note -> assertThat(note).contains("no claims"));
    }

    @Test
    @DisplayName("is not grounded when the prose cites a key that was not sent, even if claims resolve")
    void refusesADanglingMarker() {
        var items = List.of(evidence("E1", PATH, 10, 20, "x"));
        var answer = answer("As [E7] shows.",
                claim("It exists.", "E1"));

        var result = CitationValidator.validate(answer, items, Map.of("E1", SourceStatus.CONFIRMED));

        assertThat(result.grounded()).isFalse();
        assertThat(result.danglingMarkers()).containsExactly("E7");
        assertThat(result.claims().get(0).status()).isEqualTo(ClaimVerification.VERIFIED);
    }

    @Test
    @DisplayName("collects the cited keys, including ones that do not resolve")
    void collectsCitedKeys() {
        var items = List.of(evidence("E1", PATH, 10, 20, "x"));
        var answer = answer("[E1].",
                claim("a", "E1"),
                claim("b", "E4"));

        var result = CitationValidator.validate(answer, items, Map.of("E1", SourceStatus.CONFIRMED));

        assertThat(result.citedKeys()).containsExactly("E1", "E4");
        assertThat(result.count(ClaimVerification.VERIFIED)).isEqualTo(1);
        assertThat(result.count(ClaimVerification.UNSUPPORTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("never rewrites the claim text it is judging")
    void neverRewritesTheClaim() {
        var items = List.of(evidence("E1", PATH, 10, 20, "x"));
        String text = "  The   method returns   null, probably.  ";
        var answer = answer("[E1].", claim(text, "E1"));

        var result = CitationValidator.validate(answer, items, Map.of("E1", SourceStatus.CONFIRMED));

        assertThat(result.claims().get(0).text()).isEqualTo(text);
    }

    private static InvestigationAnswer answer(String text, InvestigationAnswer.AnswerClaim... claims) {
        return new InvestigationAnswer(text, List.of(claims), "MEDIUM", List.of(), null);
    }

    private static InvestigationAnswer.AnswerClaim claim(String text, String... keys) {
        return new InvestigationAnswer.AnswerClaim(text, ClaimKind.OBSERVATION, List.of(keys));
    }

    private static EvidenceItem evidence(String key, String path, Integer start, Integer end,
                                         String excerpt) {
        return new EvidenceItem(key, EvidenceKind.FINDING, "RP001 — label", "MAJOR · detail",
                path, start, end, "com.example.OrderService", "CLASS", excerpt, 40, "finding-text");
    }
}
