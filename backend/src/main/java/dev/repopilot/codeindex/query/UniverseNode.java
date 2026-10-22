package dev.repopilot.codeindex.query;

/**
 * One node of the code universe.
 *
 * <p>A node is either an indexed declaration (a class, interface, method and so on) or an
 * external target the index could not resolve to a declaration. The external kind is not a
 * guess: it is a reference the parser recorded by name, and the index stored it with
 * {@code EXTERNAL} or {@code UNRESOLVED} resolution. Keeping those references as nodes lets
 * the UI draw the outbound edge that actually exists instead of pretending the graph ends at
 * the repository boundary.
 *
 * <p>{@code external} is a primitive rather than a nullable property because it answers a
 * binary question and the renderer should never have to distinguish "absent" from "false".
 */
public record UniverseNode(
        String id,
        String kind,
        String name,
        String qualifiedName,
        String path,
        String packageName,
        Integer startLine,
        Integer endLine,
        Integer complexity,
        String parentId,
        boolean external
) {
}
