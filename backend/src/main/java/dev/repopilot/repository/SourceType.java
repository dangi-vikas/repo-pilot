package dev.repopilot.repository;

/** How a repository entered RepoPilot. */
public enum SourceType {
    /** A directory on the local filesystem inside a permitted workspace root. */
    LOCAL_PATH,
    /**
     * A repository cloned from a remote Git URL into the managed workspace.
     * Cloning executes a network fetch, so it always requires an explicit
     * user-initiated job rather than happening implicitly on import.
     */
    GIT_CLONE
}
