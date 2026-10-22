package dev.repopilot.codeindex.query;

import java.util.List;
import java.util.UUID;

/**
 * A bounded, renderable slice of a revision's code universe.
 *
 * <p>The existing {@code /graph} endpoint answers "what edges exist" as a page of rows. A
 * renderer needs the opposite shape: connected nodes with their metadata, plus the edges
 * between them. This view is that shape, and it is bounded on purpose. {@code truncated} and
 * the two totals are part of the document because a slice presented as the whole graph is a
 * false statement about the repository, particularly on the benchmark corpus where the edge
 * count runs into six figures.
 *
 * <p>External targets are aggregated by name into nodes of kind {@code EXTERNAL}. Those nodes
 * carry no path or line range because there is no source file behind them in this repository;
 * the UI must not offer to open one.
 */
public record UniverseView(
        UUID revisionId,
        List<UniverseNode> nodes,
        List<UniverseEdge> edges,
        long totalSymbols,
        long totalEdges,
        int edgeLimit,
        boolean truncated
) {
}
