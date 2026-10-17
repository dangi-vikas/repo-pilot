package dev.repopilot.analysis;

/** What kind of problem a finding describes. Coarse on purpose: it drives grouping and filters. */
public enum FindingCategory {

    /** The code does not do what it appears to say, or will fail at runtime. */
    CORRECTNESS("Correctness"),

    /** A credential, injection, or unsafe-input risk. */
    SECURITY("Security"),

    /** Cost at runtime: avoidable work, blocking calls, leaks. */
    PERFORMANCE("Performance"),

    /** Correct under a single thread, wrong under two. */
    CONCURRENCY("Concurrency"),

    /** A resource that may not be released. */
    RESOURCE("Resource"),

    /** Works today, harder to change safely tomorrow. */
    MAINTAINABILITY("Maintainability"),

    /** A convention or noise issue with no functional cost. */
    STYLE("Style");

    private final String label;

    FindingCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
