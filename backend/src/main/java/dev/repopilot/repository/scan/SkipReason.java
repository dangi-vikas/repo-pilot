package dev.repopilot.repository.scan;

/** Why a file was or was not included in a scan. Used for honest skip reporting. */
public enum SkipReason {
    /** Included: the file will be parsed or analysed. */
    NONE,
    /** Version-control metadata. Never analysed. */
    VERSION_CONTROL,
    /** Build output such as target/, build/, dist/, out/. */
    BUILD_OUTPUT,
    /** Dependency caches such as node_modules/ or .m2/. */
    DEPENDENCY_CACHE,
    /** IDE and tool metadata such as .idea/, .vscode/, .settings/. */
    IDE_METADATA,
    /**
     * Secret-bearing file (private key, keystore, .env, credential store, cloud CLI
     * config). Excluded before any read: the content never enters RepoPilot.
     */
    SECRET_BEARING,
    /** Classified as generated code. */
    GENERATED,
    /** Recognised but not a language RepoPilot parses. */
    UNSUPPORTED_LANGUAGE,
    /** Detected as binary content. */
    BINARY,
    /** Larger than repopilot.workspace.max-file-size-bytes. */
    SIZE_LIMIT,
    /** The repository file-count limit was reached before this file was reached. */
    REPOSITORY_LIMIT,
    /** A symbolic link. Not followed: symlinks are a sandbox-escape vector. */
    SYMLINK,
    /** An I/O error occurred while inspecting the file. */
    UNREADABLE
}
