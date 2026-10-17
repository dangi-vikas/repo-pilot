package dev.repopilot.codeindex.query;

import java.util.List;

/**
 * A bounded view of a revision's dependency graph.
 *
 * <p>The graph is the one place in the API where the response is not a page of rows: cycles
 * and fan-out make a graph awkward to page through, and a renderer wants connected nodes,
 * not rows 400–450 of a sorted list. The un-bounded version of this endpoint returned
 * {@code 100 801} edges — 22 MiB of JSON taking 50 seconds — for a 3 000-file repository,
 * because "return every matching edge" is a promise that cannot be kept on a real codebase.
 *
 * <p>So the endpoint is bounded and *says so*: {@code items} is the first {@code limit}
 * edges in a deterministic order, {@code totalEdges} is how many matched, and
 * {@code truncated} is true when there are more. A client can therefore distinguish "this is
 * the whole graph" from "this is the first slice of it", which is the difference between a
 * usable view and a misleading one.
 *
 * @param items      the first {@code limit} matching edges, ordered by file, line and id
 * @param totalEdges how many edges matched the filter in this revision
 * @param limit      the effective limit applied
 * @param truncated  true when {@code totalEdges > items.size()}
 */
public record GraphView(List<EdgeView> items, long totalEdges, int limit, boolean truncated) {
}
