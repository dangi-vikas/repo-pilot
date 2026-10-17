package dev.repopilot.analysis;

/**
 * How a statement was obtained. This is the axis the product is built around: a
 * measurement and a model's suggestion are not the same kind of claim, and the API
 * storing them in one column is what stops them being rendered as each other.
 *
 * <p>The value is mandatory on every finding, so an AI hypothesis cannot be persisted
 * without saying that it is one.
 */
public enum EvidenceCategory {

    /** A fact read from the repository: a file exists, a symbol is declared, an endpoint is mapped. */
    OBSERVATION("Observation"),

    /** An analyzer's conclusion about source code, with a named rule and a location. */
    STATIC_FINDING("Static finding"),

    /** A number sampled from a process or a recording: JFR, thread dump, benchmark run. */
    RUNTIME_MEASUREMENT("Runtime measurement"),

    /** A model's interpretation. The weakest category, and it must carry validated citations. */
    AI_HYPOTHESIS("AI hypothesis");

    private final String label;

    EvidenceCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * True for categories derived from the repository or from a running process.
     *
     * <p>Used by the UI to decide what may be presented as fact. The three measured
     * categories are not equally strong — a static pattern is not a runtime sample — but
     * all are evidence, whereas a hypothesis is a candidate explanation.
     */
    public boolean isMeasured() {
        return this != AI_HYPOTHESIS;
    }
}
