package dev.repopilot.investigation;

/**
 * Whether the excerpt a model was shown still matches the file on disk.
 *
 * <p>An excerpt is not stored. What is stored is the SHA-256 of the exact lines handed to
 * the model, so the check is "re-read the same range and compare", not "trust the text in
 * the prompt log". {@link #NOT_LOCATED} is not a failure: repository-level evidence has no
 * line to re-read, and saying so is more honest than reporting it as verified.
 */
public enum SourceStatus {

    /** The file was re-read and the same line range hashed to the same value. */
    CONFIRMED,
    /** The file exists but those lines no longer hash to what was sent. */
    CHANGED,
    /** The file recorded in the index is no longer readable. */
    MISSING,
    /** The evidence has no file location, so there is nothing to re-read. */
    NOT_LOCATED
}
