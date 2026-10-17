package dev.repopilot.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the citation check that every finding passes through.
 *
 * <p>Deliberately not tested through an analyzer: the behaviour under test is what RepoPilot does
 * when a rule cites a line that does not exist, and building a fixture to make a real rule misbehave
 * would test the fixture.
 */
class CitationVerifierTest {

    @Test
    @DisplayName("accepts a line the file actually has")
    void acceptsALineInsideTheFile() {
        assertThat(CitationVerifier.verify(3, null, 20)).isEqualTo(VerificationStatus.SOURCE_CONFIRMED);
    }

    @Test
    @DisplayName("accepts the last line and a range that ends on it")
    void acceptsTheLastLine() {
        assertThat(CitationVerifier.verify(20, 20, 20)).isEqualTo(VerificationStatus.SOURCE_CONFIRMED);
        assertThat(CitationVerifier.verify(18, 20, 20)).isEqualTo(VerificationStatus.SOURCE_CONFIRMED);
    }

    @Test
    @DisplayName("rejects a line past the end of the file instead of clamping it")
    void rejectsALinePastTheEnd() {
        assertThat(CitationVerifier.verify(900, 900, 20)).isEqualTo(VerificationStatus.LOCATION_STALE);
        assertThat(CitationVerifier.verify(19, 21, 20)).isEqualTo(VerificationStatus.LOCATION_STALE);
    }

    @Test
    @DisplayName("rejects zero, which is never a line number")
    void rejectsZero() {
        assertThat(CitationVerifier.verify(0, null, 20)).isEqualTo(VerificationStatus.LOCATION_STALE);
        assertThat(CitationVerifier.verify(-4, null, 20)).isEqualTo(VerificationStatus.LOCATION_STALE);
    }

    @Test
    @DisplayName("rejects an inverted range, which means the rule's own range is broken")
    void rejectsAnInvertedRange() {
        assertThat(CitationVerifier.verify(10, 4, 20)).isEqualTo(VerificationStatus.LOCATION_STALE);
    }

    @Test
    @DisplayName("rejects any line in a file with no lines")
    void rejectsALineInAnEmptyFile() {
        assertThat(CitationVerifier.verify(1, null, 0)).isEqualTo(VerificationStatus.LOCATION_STALE);
    }

    @Test
    @DisplayName("treats an absent line as no claim rather than as a defect")
    void treatsAnAbsentLineAsNoClaim() {
        assertThat(CitationVerifier.verify(null, null, 20)).isEqualTo(VerificationStatus.NO_LOCATION_CLAIMED);
    }
}
