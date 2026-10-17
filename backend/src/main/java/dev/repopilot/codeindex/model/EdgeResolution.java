package dev.repopilot.codeindex.model;

/**
 * How confident RepoPilot is that an edge in the dependency graph is real.
 *
 * <h2>Why this is a first-class field</h2>
 * Static analysis without a full type resolution classpath cannot prove that
 * {@code service.process(order)} calls {@code OrderService.process}. It can only
 * observe a name. Presenting a name match as an established dependency would be
 * misleading, and the difference matters enormously when a user is deciding whether
 * a class is safe to change.
 *
 * <p>RepoPilot therefore never collapses these categories, and the UI renders them
 * differently.
 */
public enum EdgeResolution {
    /**
     * The target symbol was located in the analysed revision and the reference was
     * unambiguous — a same-file declaration, or a simple name matching exactly one
     * type in an explicitly imported package.
     */
    RESOLVED,
    /**
     * The target was matched by name within the analysed revision but the reference
     * could not be confirmed (a simple-name match without an import, or an
     * ambiguous winner). Real in most cases; not proven.
     */
    INFERRED,
    /**
     * The target is not declared in the analysed revision — a JDK type, a library type,
     * or another module. The name is recorded so the graph can show the boundary
     * rather than hiding it.
     */
    EXTERNAL,
    /** The reference could not be associated with any type name at all. */
    UNRESOLVED;

    /** True when this edge points at a symbol inside the analysed revision. */
    public boolean isInternal() {
        return this == RESOLVED || this == INFERRED;
    }
}
