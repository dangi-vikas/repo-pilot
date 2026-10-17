package dev.repopilot.analysis;

/**
 * How sure the analyzer is about the finding's claim.
 *
 * <p>This is confidence in the <em>claim</em>, not in the impact:
 *
 * <ul>
 *   <li>A rule that matched the syntax tree directly is {@link #CERTAIN} that the
 *       pattern is present. That says nothing about whether the pattern is wrong here.</li>
 *   <li>A rule that matched a name ({@code password}) without following data flow is
 *       {@link #MEDIUM}: the name is a signal, not a proof.</li>
 *   <li>A heuristic that needs call-graph context has no better claim than
 *       {@link #LOW}, and should say so in the finding's limitations.</li>
 * </ul>
 *
 * <p>The UI shows confidence next to severity so a reader can triage by both. Reporting
 * a name-based guess with the same certainty as a syntax match would make the whole
 * findings list untrustworthy.
 */
public enum FindingConfidence {

    /** The claim is mechanically provable from the text that was analysed. */
    CERTAIN("Certain"),

    /** Strong evidence, but from a pattern that has legitimate exceptions. */
    HIGH("High"),

    /** A signal, not a proof: a heuristic or a name-based match. */
    MEDIUM("Medium"),

    /** Weak evidence. Present for completeness; the limitations field must say why. */
    LOW("Low");

    private final String label;

    FindingConfidence(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
