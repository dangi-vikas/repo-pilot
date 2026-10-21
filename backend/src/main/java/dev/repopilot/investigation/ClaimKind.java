package dev.repopilot.investigation;

/**
 * What kind of statement a claim is.
 *
 * <p>The distinction is the model's own, and RepoPilot stores it unmodified. It matters
 * because the four evidence labels in this product are not interchangeable: an observation
 * reports what the index says, an inference is the model connecting two of them, and a
 * recommendation is advice. Only the first can be checked against a citation at all, which
 * is why every claim is labelled before it is read.
 */
public enum ClaimKind {

    /** Reports something the cited evidence states. */
    OBSERVATION,
    /** A conclusion drawn from cited evidence: plausible, not itself in the index. */
    INFERENCE,
    /** A suggested action. Never applied by RepoPilot. */
    RECOMMENDATION
}
