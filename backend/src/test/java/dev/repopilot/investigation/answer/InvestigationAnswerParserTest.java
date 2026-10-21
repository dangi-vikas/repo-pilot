package dev.repopilot.investigation.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.investigation.ClaimKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the answer parser.
 *
 * <p>The parser is where a model's formatting is separated from its substance. These tests pin down
 * the line it draws: cosmetic deviations are normalised and noted, a response that is not the
 * contract is preserved as raw text rather than quietly discarded, and nothing is invented to fill
 * a gap.
 */
class InvestigationAnswerParserTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("parses the contract exactly as the prompt describes it")
    void parsesTheContract() {
        var parsed = InvestigationAnswerParser.parse("""
                {"answer":"The empty catch is at [E1].","claims":[
                  {"text":"An empty catch block swallows the exception.","kind":"OBSERVATION","evidence":["E1"]}],
                 "confidence":"MEDIUM","unanswered":[],"limitations":"Only one file was retrieved."}
                """, json);

        assertThat(parsed.parsed()).isTrue();
        assertThat(parsed.answer().answer()).isEqualTo("The empty catch is at [E1].");
        assertThat(parsed.answer().claims()).hasSize(1);
        assertThat(parsed.answer().claims().get(0).kind()).isEqualTo(ClaimKind.OBSERVATION);
        assertThat(parsed.answer().claims().get(0).evidence()).containsExactly("E1");
        assertThat(parsed.answer().confidence()).isEqualTo("MEDIUM");
        assertThat(parsed.answer().limitations()).isEqualTo("Only one file was retrieved.");
        assertThat(parsed.notes()).isEmpty();
    }

    @Test
    @DisplayName("removes a code fence and says it did")
    void removesACodeFence() {
        var parsed = InvestigationAnswerParser.parse("""
                ```json
                {"answer":"ok","claims":[],"confidence":"LOW"}
                ```
                """, json);

        assertThat(parsed.parsed()).isTrue();
        assertThat(parsed.answer().answer()).isEqualTo("ok");
        assertThat(parsed.notes()).anySatisfy(note -> assertThat(note).contains("code fence"));
    }

    @Test
    @DisplayName("finds the object inside prose and records that the prose was ignored")
    void findsTheObjectInsideProse() {
        var parsed = InvestigationAnswerParser.parse("""
                Here is my answer:
                {"answer":"ok","claims":[],"confidence":"LOW"}
                Let me know if you need more.
                """, json);

        assertThat(parsed.parsed()).isTrue();
        assertThat(parsed.notes()).anySatisfy(note -> assertThat(note).contains("outside the JSON object"));
    }

    @Test
    @DisplayName("keeps a non-JSON response as raw text instead of throwing it away")
    void keepsANonJsonResponse() {
        var parsed = InvestigationAnswerParser.parse(
                "I could not find anything relevant in the evidence.", json);

        assertThat(parsed.parsed()).isFalse();
        assertThat(parsed.answer()).isNull();
        assertThat(parsed.raw()).isEqualTo("I could not find anything relevant in the evidence.");
        assertThat(parsed.notes()).anySatisfy(note -> assertThat(note).contains("JSON object"));
    }

    @Test
    @DisplayName("treats an empty response as unparseable rather than as an empty answer")
    void treatsAnEmptyResponseAsUnparseable() {
        var parsed = InvestigationAnswerParser.parse("   ", json);

        assertThat(parsed.parsed()).isFalse();
        assertThat(parsed.raw()).isEmpty();
        assertThat(parsed.notes()).isNotEmpty();
    }

    @Test
    @DisplayName("records an unrecognised claim kind as an inference, not as an observation")
    void recordsAnUnknownKindAsInference() {
        var parsed = InvestigationAnswerParser.parse("""
                {"answer":"x","claims":[{"text":"t","kind":"GUESS","evidence":["E1"]}]}
                """, json);

        assertThat(parsed.answer().claims().get(0).kind()).isEqualTo(ClaimKind.INFERENCE);
        assertThat(parsed.notes()).anySatisfy(note -> assertThat(note).contains("GUESS"));
    }

    @Test
    @DisplayName("accepts a comma-separated evidence string, because models write them")
    void acceptsACommaSeparatedEvidenceString() {
        var parsed = InvestigationAnswerParser.parse("""
                {"answer":"x","claims":[{"text":"t","kind":"OBSERVATION","evidence":"E1, E4"}]}
                """, json);

        assertThat(parsed.answer().claims().get(0).evidence()).containsExactly("E1", "E4");
        assertThat(parsed.notes()).anySatisfy(note -> assertThat(note).contains("split on commas"));
    }

    @Test
    @DisplayName("drops a claim with no text and says how many were kept")
    void dropsABlankClaim() {
        var parsed = InvestigationAnswerParser.parse("""
                {"answer":"x","claims":[{"text":"","kind":"OBSERVATION","evidence":["E1"]},
                                        {"text":"kept","kind":"OBSERVATION","evidence":["E1"]}]}
                """, json);

        assertThat(parsed.answer().claims()).hasSize(1);
        assertThat(parsed.answer().claims().get(0).text()).isEqualTo("kept");
        assertThat(parsed.notes()).anySatisfy(note -> assertThat(note).contains("no text"));
    }

    @Test
    @DisplayName("does not store a confidence label it does not recognise")
    void refusesAnUnknownConfidenceLabel() {
        var parsed = InvestigationAnswerParser.parse("""
                {"answer":"x","claims":[],"confidence":"CERTAIN"}
                """, json);

        assertThat(parsed.answer().confidence()).isNull();
        assertThat(parsed.notes()).anySatisfy(note -> assertThat(note).contains("CERTAIN"));
    }

    @Test
    @DisplayName("normalises a lowercase confidence label")
    void normalisesConfidence() {
        var parsed = InvestigationAnswerParser.parse("""
                {"answer":"x","claims":[],"confidence":"high"}
                """, json);

        assertThat(parsed.answer().confidence()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("records that a missing claims array means nothing could be checked")
    void notesAMissingClaimsArray() {
        var parsed = InvestigationAnswerParser.parse("""
                {"answer":"Prose only.","confidence":"LOW"}
                """, json);

        assertThat(parsed.parsed()).isTrue();
        assertThat(parsed.answer().claims()).isEmpty();
        assertThat(parsed.notes()).anySatisfy(note -> assertThat(note).contains("no claims array"));
    }

    @Test
    @DisplayName("keeps the answer but notes a missing answer field")
    void notesAMissingAnswerField() {
        var parsed = InvestigationAnswerParser.parse("""
                {"claims":[],"confidence":"LOW"}
                """, json);

        assertThat(parsed.parsed()).isTrue();
        assertThat(parsed.answer().answer()).isEmpty();
        assertThat(parsed.notes()).anySatisfy(note -> assertThat(note).contains("answer field"));
    }

    @Test
    @DisplayName("removes duplicate keys, which would otherwise be counted twice")
    void removesDuplicateKeys() {
        var parsed = InvestigationAnswerParser.parse("""
                {"answer":"x","claims":[{"text":"t","kind":"OBSERVATION","evidence":["E1","E1"]}]}
                """, json);

        assertThat(parsed.answer().claims().get(0).evidence()).containsExactly("E1");
    }

    @Test
    @DisplayName("reads unanswered questions as text and ignores non-text entries")
    void readsUnansweredQuestions() {
        var parsed = InvestigationAnswerParser.parse("""
                {"answer":"x","claims":[],"unanswered":["why it is slow", 4, null]}
                """, json);

        assertThat(parsed.answer().unanswered()).containsExactly("why it is slow");
    }
}
