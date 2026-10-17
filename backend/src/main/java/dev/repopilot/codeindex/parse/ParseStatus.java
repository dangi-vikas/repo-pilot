package dev.repopilot.codeindex.parse;

/** Outcome of parsing a single source file. */
public enum ParseStatus {
    /** Parsed without errors. */
    PARSED,
    /** Parsed with recoverable problems (for example an unsupported language level construct). */
    PARSED_WITH_PROBLEMS,
    /** Parsing failed. The file is recorded with the reason so coverage is not overstated. */
    FAILED,
    /** Deliberately not parsed (generated code, unsupported language). */
    SKIPPED,
    /** Detected as too large or otherwise outside the analysis boundary. */
    EXCLUDED,
    /**
     * Read and recorded, but not parsed, because no analyzer for this file's language has
     * been written yet — a {@code .properties} or {@code .yml} file waiting for the
     * configuration analyzer, for example.
     *
     * <p>Distinct from {@link #PARSED_WITH_PROBLEMS}, which means a parser ran and reported
     * problems. Without this status every configuration file in a repository was fed to the
     * Java parser and came back "parsed with problems", which claimed a Java parse of a file
     * that is not Java. Nothing is inferred from a recorded file: it has no symbols and no
     * references, and only its existence, size, hash and line count are asserted.
     */
    RECORDED
}
