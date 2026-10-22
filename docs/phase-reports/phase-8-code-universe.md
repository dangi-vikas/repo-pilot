# Phase 8 — The Code Intelligence Universe

- **Status:** implemented and exercised; staged but **not committed**
- **Date:** 2026-10-22
- **Base:** `1fb95b3 feat: done Ollama integration`
- **Scope:** a visual and interaction redesign of the existing console, plus one new
  backend read model for the graph. No indexing, analysis, runtime or investigation
  semantics were replaced.

## What this phase is, and is not

This phase turns RepoPilot's UI into a dark, motion-aware "code intelligence universe",
and it makes the centrepiece a real graph: indexed symbols, resolved edges, external
targets, findings and investigation evidence. It does not invent profiler data, fake
timings, synthetic nodes or a demo investigation.

The redesign is additive on the frontend. Existing routes and API semantics remain; the
old `/graph` destination is no longer in the navigation and redirects to `/universe`.
Backend changes are limited to a read-only universe projection and a path lookup for deep
links.

## The universe contract

`GET /api/v1/repositories/{repositoryId}/universe` returns:

| Field | Meaning |
| --- | --- |
| `revisionId` | Revision the projection was read from |
| `nodes` | Indexed symbols touched by the returned edges, with kind, qualified name, package, path, line range, external flag and degree |
| `edges` | At most `edgeLimit` edges, ordered deterministically |
| `totalSymbols` | Indexed symbols in the revision, excluding external aggregates |
| `totalEdges` | Every matching edge before the cap |
| `edgeLimit` | Effective cap (default 1,400; maximum 6,000; UI asks for 2,400) |
| `truncated` | Whether the response is a slice |

External targets are aggregated by name (the 220 most-referenced, ids prefixed
`external:`). A node is never returned without an edge in the same response. The UI states
the slice and both totals; it never presents the returned nodes as the whole repository.

Findings and AI evidence are joined in the browser. Findings attach by `symbolId`, else by
path and line containment. The findings overlay is withheld when the run's `revisionId`
does not equal the universe's. An investigation trail is likewise withheld with a visible
explanation on a revision mismatch. Full rationale: [ADR 0014](../adr/0014-bounded-code-universe.md).

## Backend changes

| File | Change |
| --- | --- |
| `backend/.../codeindex/query/UniverseNode.java` | Projection node record |
| `backend/.../codeindex/query/UniverseEdge.java` | Projection edge record |
| `backend/.../codeindex/query/UniverseView.java` | Response envelope with totals, limit and `truncated` |
| `backend/.../codeindex/query/CodeIndexQueryService.java` | Universe assembly, external aggregation, deterministic edge ordering, and `fileByPath` |
| `backend/.../api/v1/RepositoryController.java` | `GET /{repositoryId}/universe` and `GET /{repositoryId}/files/by-path`; unknown paths answer `404`, not an invented file |
| `backend/.../RepositoryIndexingIntegrationTest.java` | Assertions for the universe and by-path lookup, including a missing/blank path |

The by-path endpoint exists because the explorer's list is bounded to 500 rows. A shared
link to file 1,200 previously selected nothing: the list did not contain it. The lookup
resolves it directly against the revision.

## Frontend changes

### Shell and visual layer

- `AppShell.tsx`: fixed shell composition, right inspector, bottom activity console, and
  content scroll reset on route change.
- `NavRail.tsx`, `destinations.ts`, `TopBar.tsx`, `CommandPalette.tsx`: exactly the nine
  requested destinations, grouped as Universe / Investigate / Operate; brand and AI
  status; repository/branch; global search; index status; notifications; settings; AI
  shortcut.
- `styles/redesign.css`, `styles/universe.css`: near-black/deep-navy surfaces, violet,
  cyan, magenta, lime, amber and red accents, mono identifiers, restrained glass, and a
  responsive layout. Imported after `app.css`.
- `state/motion.tsx`: explicit reduced-motion choice, OS preference as default, persisted.
- `components/ActivityConsole.tsx`: real job rows from the backend, start collapsed,
  persists `repopilot.console.open`, filters All/Active/Failed, no simulated terminal.
- `components/AiOrb.tsx`: status orb used by the shell and overview; motion obeys the
  preference.

### Overview and Findings

- `OverviewPage.tsx`: real counters for files, symbols, relationships, resolved
  relationships and findings; repository status; jobs in flight; runtime summaries; a
  labelled universe preview. A missing value is shown as missing, not as zero.
- `FindingsPage.tsx`: standalone findings workspace with severity rail, category and
  status filters, search, and paths that deep-link to the exact line.

### Code Universe

- `CodeUniversePage.tsx`: query, filters, selection, focus, inspector state, minimap,
  legend, trail URL and revision guard.
- `components/universe/UniverseCanvas.tsx`: Canvas 2D + `d3-force`; viewport culling,
  zoom-scaled labels, drag, pan, zoom, fit, search highlighting, multi-select, focus
  neighbourhood, minimap, scan reveal and particles. Canvas nodes are keyboard reachable
  through the search and inspector, not only by pointer.
- `components/universe/UniverseChrome.tsx`: search, hard filters, severity lens, particle
  density, legend (starts collapsed).
- `components/universe/NodeInspector.tsx`: kind, qualified name, path and line range,
  incoming/outgoing edges with source lines, real source excerpt through the sandboxed
  file endpoint, findings, "Ask AI", focus, expand, and open-source.
- `lib/universe.ts`: pure transforms, search ranking, evidence mapping, revision guard.
  `lib/universe.test.ts` covers them.

Hard filters remove nodes or edges: kind, package, edge type, resolution, external,
findings-only. Severity is a lens: it dims nodes without a finding at that severity and
does not remove them.

### Investigation and explorer integration

- `InvestigationPage.tsx`: evidence table links to `/universe?trail=<investigationId>`,
  keeps confidence and verdict visible, and separates confirmed citations from heuristics
  and hypotheses.
- `CodeUniversePage.tsx`: consumes `?trail=`, selects the investigation, and withholds the
  trail when its revision differs from the graph revision.
- `CodeExplorerPage.tsx`: `?path=&line=` deep links now resolve through `fileByPath`, pin a
  file outside the first list page as *Linked file*, scroll the stacked reader into view,
  and centre the requested line. An unresolved path shows a warning instead of silently
  showing a different file.
- `CodeView.tsx`: scrolls the highlighted line into view inside the independently
  scrolling source pane.
- `ui.tsx`: `Card` accepts an `id`, used by the deep-link reader target.

## Verification

### Automated

| Check | Result |
| --- | --- |
| `backend/mvnw.cmd -B test` | **230 tests, 35 classes, 0 failures, 0 errors, 0 skipped** |
| `frontend npm run typecheck` | Clean under TypeScript strict |
| `frontend npm test` | **30 tests passed** (`lib/universe.test.ts`) |
| `frontend npm run build` | Success; 578 modules; 608.48 kB JS / 187.03 kB gzip; 97.76 kB CSS / 18.76 kB gzip. The existing >500 kB chunk warning remains |
| `GET /files/by-path` | `200` with the expected `relativePath`, `PARSED`, 125 lines for a file outside the first 500; `404` for an unknown path |
| Target test | `RepositoryIndexingIntegrationTest` passed before the full suite |

### Browser verification

Exercised against a running backend (`127.0.0.1:8080`) and Vite (`127.0.0.1:5173`) on
Windows, at a 1024-wide viewport:

- Overview: real counters and status, jobs, universe preview.
- Code Universe: 309 nodes / 2,400 edges, 7 nodes with findings; graph fully visible;
  search for `DraftLedger01305Service`; inspector showed 7 outgoing and 0 incoming edges
  plus a real source excerpt; the source-path button opened
  `/code?path=…DraftLedger01305Service.java&line=15` with `@Service` highlighted.
- Code Explorer deep link: `?path=…DraftLedger01305Service.java&line=91` opened a file
  outside the first 500-row page, centred line 91, and showed it highlighted. The same
  file and line were previously unreachable from the link. An invalid path
  (`?path=does/not/Exist.java`) showed *"That deep link did not resolve"* and left the
  address bar intact instead of silently showing an unrelated file.
- Investigation trail: `Show evidence path in Code Universe` produced
  `/universe?trail=6d2974a2-…`; the trail bar showed the selected investigation, the graph
  reported 33 nodes / 49 edges / 6 with findings, and 5 evidence nodes / 6 path edges were
  highlighted. No mismatch was shown because that fixture's revision matched; the guard
  is covered by the transform tests and the UI path.
- Findings: standalone severity rail and filters render, paths deep-link to code.
- Performance Lab: real benchmark data (277 measurements, 1 artefact), with the
  "quoted from the harness — not measured here" banner.
- Memory Analysis and Concurrency Inspector: informative empty states on a repository with
  no heap or thread dump. No runtime data was fabricated to fill them.
- Motion toggle: switched `full → reduced → full`, persisted in
  `localStorage["repopilot.motion"]` and reflected in `documentElement.dataset.motion`.
- Activity console: opened from the collapsed bar, showed eight real
  `ARTIFACT_RETENTION` job rows and the counters `0 running · 0 queued · 8 failed`;
  collapsed again and persisted `repopilot.console.open=false`.

### Screenshots refreshed

`overview.png`, `code-universe.png`, `code-universe-evidence-trail.png`,
`findings.png`, `code-explorer-deep-link.png` in `docs/screenshots/`.

## Defects found and fixed during the phase

- A deep link to a file beyond the first 500 files selected nothing. Fixed with the
  by-path read and a pinned *Linked file* row.
- The deep-linked line was highlighted inside a 62vh source pane but often off-screen.
  Fixed by scrolling within the pane and, on stacked layouts, scrolling the reader to the
  line.
- The standalone Findings rail collapsed to two columns at desktop widths. Fixed with six
  columns and 3/2-column fallbacks.
- The compact navigation hid destinations. All nine now render.
- The content scroll position survived route changes and could hide the top of a new page.
  Reset on navigation.
- The minimap was too large and the legend started open. Minimap reduced and legend/
  inspector start collapsed.
- The activity console started open and dominated short screens. It now starts collapsed,
  persists the choice, and resizes the universe without moving the viewport centre.

## Pending and not verified

- **Docker Compose / PostgreSQL:** no Docker daemon is available in this environment.
  PostgreSQL has never executed here; Flyway migrations are exercised only on H2 in
  PostgreSQL compatibility mode.
- **Shipped 7B model:** the end-to-end AI run used `qwen2.5-coder:3b`; the shipped
  `qwen2.5-coder:7b` was not run on this CPU-only machine. A 3B question already used
  54–79 s of the 120 s timeout.
- **Graph scale:** the 6,000-edge cap was not profiled on a low-end machine. The browser
  verification used 2,400 edges and 309 drawn nodes.
- **Layout persistence:** node positions are ephemeral; a reload can rearrange the same
  revision. No layout is written to the index.
- **Bundle split:** the production build still emits one JS chunk over 500 kB. Lazy routes
  or a vendor split for `motion` and `d3-force` would address the warning.
- **Visual regression coverage:** screenshots were checked by eye; there is no automated
  pixel test.

## Honest summary

The backend integrations are real: graph data, path lookup, source reads, findings and
investigation evidence all come from the running API. The universe is a stated slice, not
the whole graph, and the screen says so. The runtime pages continue to show only what the
backend recorded; where no dump or recording exists, they show a zero state. The remaining
gaps are environment gaps (Docker/PostgreSQL/7B model), a known bundle-size warning, and
unmeasured behaviour at the maximum edge cap.
