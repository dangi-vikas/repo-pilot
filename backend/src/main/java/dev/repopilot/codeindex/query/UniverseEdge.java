package dev.repopilot.codeindex.query;

/**
 * One drawn relationship in the code universe.
 *
 * <p>Endpoints are the string ids used by {@link UniverseNode}, not raw symbol UUIDs, because
 * an edge may terminate at an aggregated external node. The resolution travels with the edge so
 * the renderer can make an inferred or unresolved relationship visually distinct from one that
 * was resolved against a declaration.
 */
public record UniverseEdge(
        String id,
        String source,
        String target,
        String type,
        String resolution,
        Integer sourceLine
) {
}
