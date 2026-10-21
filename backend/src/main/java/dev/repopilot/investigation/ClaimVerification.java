package dev.repopilot.investigation;

/**
 * The verdict on one claim, decided by the backend rather than by the model.
 *
 * <p>This is the product's evidence rule applied to prose: a sentence a model wrote is
 * not evidence, and a citation is only worth something if the thing it cites still says
 * what the sentence claims. The verdict never edits the claim — it is attached beside it.
 */
public enum ClaimVerification {

    /** Every citation resolves, cites at least one piece of evidence that was actually sent,
     *  and any location named in the sentence is covered by those citations. */
    VERIFIED,
    /** The citations resolve, but something weakens them: a cited excerpt no longer matches
     *  the file, or the sentence names a location its citations do not cover. */
    PARTIAL,
    /** No usable citation: an unknown key, no keys at all, or nothing locatable in the answer. */
    UNSUPPORTED
}
