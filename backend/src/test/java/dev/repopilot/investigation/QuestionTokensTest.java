package dev.repopilot.investigation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the searchable terms taken from a question.
 *
 * <p>Retrieval has to be explainable, so the terms it searched for are the explanation. These tests
 * pin the two behaviours a reader would otherwise have to guess at: identifier parts are searchable
 * separately, and words that appear in every question are not searched at all.
 */
class QuestionTokensTest {

    @Test
    @DisplayName("splits an identifier into the words a reader would use")
    void splitsIdentifiers() {
        assertThat(QuestionTokens.from("Why does OrderService retry?"))
                .containsExactly("order", "service", "retry");
    }

    @Test
    @DisplayName("keeps a path and its basename, which are the terms a path search needs")
    void keepsPathsAndBasenames() {
        assertThat(QuestionTokens.from("what happens in src/main/java/com/example/Foo.java"))
                .contains("src/main/java/com/example/foo.java", "foo");
    }

    @Test
    @DisplayName("drops the words that carry no discriminating power")
    void dropsStopwords() {
        assertThat(QuestionTokens.from("how does the code work in this class"))
                .isEmpty();
    }

    @Test
    @DisplayName("is bounded, because retrieval cost is per term")
    void isBounded() {
        assertThat(QuestionTokens.from("alpha bravo charlie delta echo foxtrot golf hotel india juliet"))
                .hasSize(8);
    }

    @Test
    @DisplayName("returns nothing for an empty question rather than throwing")
    void handlesAnEmptyQuestion() {
        assertThat(QuestionTokens.from(null)).isEmpty();
        assertThat(QuestionTokens.from("   ")).isEmpty();
    }

    @Test
    @DisplayName("recognises a question about runtime behaviour")
    void recognisesRuntimeQuestions() {
        assertThat(QuestionTokens.isRuntimeQuestion("Why is this method slow?")).isTrue();
        assertThat(QuestionTokens.isRuntimeQuestion("What does the benchmark measure?")).isTrue();
        assertThat(QuestionTokens.isRuntimeQuestion("Where is the OrderService class?")).isFalse();
    }
}
