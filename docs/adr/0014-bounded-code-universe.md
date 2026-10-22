# ADR 0014 — The Code Universe is a bounded projection, not the whole graph

- **Status:** Accepted
- **Date:** 2026-10-22
- **Deciders:** RepoPilot maintainers

## Context

The Code Explorer can list a repository's symbols and edges, and the earlier dependency
graph exposed one page of edges at a time. That answered "what references what" for a
symbol, but it could not answer the question the workbench is for: what shape does this
codebase have, where are its hubs and isolated modules, and which part of it does a
finding or an investigation touch?

A graph of a real repository is large. The generated benchmark corpus has 16,205 symbols
and 105,001 references. Returning that graph in one response produced 22 MiB of JSON and
took 50 seconds; rendering a DOM node per symbol would be worse. A canvas that quietly
draws 300 of 16,000 symbols without saying so would be a misleading picture, not a
simplified one. The product's evidence discipline applies to the graph as much as to a
measurement: a view has to distinguish "the whole graph" from "the slice I could afford".

## Decision

**The Code Universe is a server-side, bounded projection of the index, rendered on a
Canvas 2D surface. Every response states what it contains and what it left out.**

1. **The endpoint returns nodes and edges together.**
   `GET /api/v1/repositories/{repositoryId}/universe` returns `revisionId`, `nodes`,
   `edges`, `totalSymbols`, `totalEdges`, `edgeLimit` and `truncated`. Node records carry the
   indexed kind, qualified name, package, path, line range, external flag and current
   degree. The browser does not invent nodes or infer missing metadata from a name.

2. **The bound is on edges, not on a page of edges.**
   Edges are ordered deterministically and capped at 1,400 by default and 6,000 at most.
   The UI asks for 2,400. Nodes are the symbols touched by the returned edges, so a node
   is never drawn without a reason to be there. `totalSymbols` counts indexed symbols,
   not external targets; `totalEdges` counts the full match before the cap. When the two
   disagree with the arrays, the screen says so and names both numbers.

3. **External targets are aggregated by name.**
   References to JDK, framework and other unindexed types become one node per distinct
   target name, with ids prefixed `external:`. At most the 220 most-referenced names are
   included. Edges to targets that did not make that cut are dropped, rather than drawn to
   a node that is not present. An external node is a name, not a source location, and the
   inspector says that.

4. **Rendering is Canvas 2D with `d3-force`.**
   The simulation and drawing live outside React's render loop. The canvas culls nodes and
   labels by viewport, scales label density with zoom, and carries the simulation's
   positions between frames. HTML is used only for controls, the inspector and overlays.
   Zoom, pan, drag, search, multi-select, focus, filters, the minimap and keyboard
   navigation are interactions over that canvas, not a DOM graph.

5. **Filters are hard; severity is a lens.**
   Symbol kind, package, edge type, resolution, external and findings-only remove nodes or
   edges. A severity selection only dims nodes that carry no finding at that severity; it
   never removes a node from the picture. The two behaviours are different questions and
   are labelled differently.

6. **Findings and evidence are joined in the browser, with guards.**
   The server's universe does not contain findings or AI evidence. Findings attach by
   `symbolId`, else by path plus line containment. The overlay is withheld unless the
   finding run's `revisionId` equals the universe's. An investigation's evidence trail is
   mapped to nodes in the same way, and is withheld with an explanation when the
   investigation ran on a different revision. A stale trail is never drawn over a newer
   graph.

## Consequences

- The Code Universe is a slice by construction. Counts, truncation and external
  aggregation are part of the contract, not implementation details.
- The endpoint is larger than a page response and is not reusable for an unbounded export.
  That is deliberate: an export needs a different contract and a different budget.
- Layout is ephemeral. RepoPilot does not persist node positions, so the same revision can
  produce a different arrangement after a reload. Moving the simulation state into the
  index would be a separate decision.
- The UI can stay interactive on the benchmark corpus at 309 drawn nodes and 2,400 edges.
  It has not been profiled at the 6,000-edge cap on a low-end machine.
- Finding and evidence joins can be incomplete when a finding's symbol is outside the
  returned slice. The UI shows the number of nodes that carry findings in the slice rather
  than claiming to show every finding.
- The old `/graph` route redirects to `/universe`; the graph endpoint remains available for
  callers that want a paged edge list.
