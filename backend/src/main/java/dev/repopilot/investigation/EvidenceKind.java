package dev.repopilot.investigation;

/**
 * What a piece of retrieved evidence is.
 *
 * <p>Ordered by how directly it can support a sentence: a static finding names a rule and a
 * location, a symbol or file names a location, a measurement names an observation of a run,
 * and the repository summary is context rather than evidence for anything specific. The UI
 * and the prompt both say which kind they are looking at.
 */
public enum EvidenceKind {

    /** A stored static-analysis finding of the newest static run. */
    FINDING("finding"),
    /** An indexed symbol (class, method, field) of the revision. */
    SYMBOL("symbol"),
    /** An indexed source file, used when the question names a file but no symbol matched. */
    FILE("file"),
    /** A measurement from the newest runtime run, if one exists and the question is about behaviour. */
    MEASUREMENT("measurement"),
    /** Counts of what the index and the analysis hold. Context, not proof of anything. */
    REPOSITORY("repository");

    private final String label;

    EvidenceKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
