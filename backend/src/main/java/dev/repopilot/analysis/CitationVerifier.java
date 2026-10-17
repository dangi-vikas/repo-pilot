package dev.repopilot.analysis;

/**
 * Checks a finding's citation against the text the analyzer was given.
 *
 * <p>Every citation is re-checked here before it is stored, for the same reason a model's
 * citations are validated before they are shown: a line number that does not exist is the most
 * persuasive kind of wrong answer, and it is indistinguishable from a right one in a report.
 *
 * <p>Never clamps and never drops. A rule reporting line 900 of a 40-line file is a defect in that
 * rule, and the record says so rather than quietly moving the citation to a line that exists. The
 * check is deliberately strict about {@code endLine < startLine} too: an inverted range means the
 * analyzer's own range computation is broken, which a client rendering the range would hide.
 *
 * <p>Extracted from the runner and made public so this logic can be tested directly. A rule that
 * lies about its location is exactly the failure mode worth its own test, and reaching it through
 * a fabricated analyzer and a whole repository would have tested the fixture instead.
 */
public final class CitationVerifier {

    private CitationVerifier() {
    }

    /**
     * @param startLine 1-based first line the finding is about, or {@code null} for a file-level
     *                  finding that claims no location. {@code 0} is never a valid line and is
     *                  treated as a defect rather than as "absent"
     * @param endLine   1-based last line, or {@code null} when the rule means a single line
     * @param lineCount number of lines in the text the analyzer actually read
     */
    public static VerificationStatus verify(Integer startLine, Integer endLine, int lineCount) {
        if (startLine == null) {
            return VerificationStatus.NO_LOCATION_CLAIMED;
        }
        int start = startLine;
        int end = endLine == null ? start : endLine;
        if (start < 1 || end < start || end > lineCount) {
            return VerificationStatus.LOCATION_STALE;
        }
        return VerificationStatus.SOURCE_CONFIRMED;
    }
}
