package dev.repopilot.analysis;

/**
 * How much a finding matters if it is real.
 *
 * <p>Severity and {@link FindingConfidence} are deliberately separate. A rule can be
 * certain that a pattern exists and have no idea whether it is a problem — an empty
 * catch block in a test helper is not the same emergency as one wrapping a payment
 * call. Collapsing the two into one number is how tools end up reporting "142 critical
 * issues" that nobody acts on.
 */
public enum FindingSeverity {

    /** A defect with a likely production impact and no plausible rationale. */
    CRITICAL("Critical", 4),

    /** A real defect, or a security or correctness risk that needs a decision. */
    HIGH("High", 3),

    /** A likely defect or a maintainability problem worth fixing. */
    MEDIUM("Medium", 2),

    /** A minor issue: noise in logs, a style choice with a cost. */
    LOW("Low", 1),

    /** Context, not a defect. Recorded so a rule's output is complete. */
    INFO("Info", 0);

    private final String label;
    private final int rank;

    FindingSeverity(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    public String label() {
        return label;
    }

    /** Higher is more severe. Used for ordering without relying on ordinal. */
    public int rank() {
        return rank;
    }
}
