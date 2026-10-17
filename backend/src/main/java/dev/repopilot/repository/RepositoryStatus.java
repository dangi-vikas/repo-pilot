package dev.repopilot.repository;

/**
 * Lifecycle of a registered repository.
 *
 * <p>{@link #REGISTERED} and {@link #READY} are meaningful, separate states: a
 * repository can be registered and browseable at the file level long before it has
 * been parsed. Collapsing them would make the UI claim indexing had happened when
 * it had not.
 */
public enum RepositoryStatus {
    /** Registered and path-validated. No parsing has completed for any revision. */
    REGISTERED,
    /** An indexing job is currently running. */
    INDEXING,
    /** At least one revision is fully indexed and queryable. */
    READY,
    /** The most recent indexing attempt failed. A previous revision may still be browseable. */
    FAILED
}
