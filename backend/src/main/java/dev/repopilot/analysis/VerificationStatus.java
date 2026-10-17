package dev.repopilot.analysis;

/**
 * Whether the location a finding cites was checked against the revision.
 *
 * <p>RepoPilot validates a model's citations before showing them. The same rule applies
 * to analyzers, and for the same reason: a stale or invented line number is the most
 * persuasive kind of wrong answer. A finding's file and line are re-checked against the
 * revision the run analysed, and the result is stored rather than assumed.
 *
 * <p>{@link #LOCATION_STALE} is not a normal state. It means a rule reported a line that
 * the analysed file does not have, which is a defect in that rule — and it is stored
 * loudly rather than silently corrected, because quietly clamping a citation to a
 * plausible line is exactly the behaviour this product exists to avoid.
 */
public enum VerificationStatus {

    /** The file exists in the revision and the cited line range is inside it. */
    SOURCE_CONFIRMED("Source confirmed"),

    /** The rule reported a line outside the analysed file. A rule defect, not a finding. */
    LOCATION_STALE("Location stale"),

    /** The finding is a claim about a file as a whole, with no line to check. */
    NO_LOCATION_CLAIMED("No location claimed");

    private final String label;

    VerificationStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
