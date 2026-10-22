import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { CodeView } from "@/components/CodeView";
import { FindingsPanel } from "@/components/FindingsPanel";
import { IndexButton } from "@/components/IndexButton";
import { RepositoryRequired } from "@/components/RepositoryRequired";
import { Badge, Banner, Card, EmptyState, ErrorState, Loading, PageHeader, SearchInput } from "@/components/ui";
import { Icon } from "@/components/icons";
import {
  useCoverage,
  useFileByPath,
  useFileContent,
  useFileSymbols,
  useFiles,
  useFindingSummary,
  useFindings,
  useMappings,
  useRevisions,
  useSymbolReferences,
  useSymbols,
} from "@/hooks/queries";
import { ApiError } from "@/lib/api";
import { formatBytes, formatCoverageSentence, formatLine, formatLineRange, formatNumber, hasValue, parseStatusTone, titleCase } from "@/lib/format";
import type { FindingView, SymbolView, Uuid } from "@/lib/types";
import { useSelection } from "@/state/selection";

type LeftMode = "files" | "symbols";
type DetailTab = "source" | "symbols" | "mappings" | "findings";
/**
 * The code explorer.
 *
 * Everything on this screen is read from the index for a specific revision. When a file
 * could not be fully parsed that is stated on the row and in the detail pane, because
 * "this file has no symbols" and "this file's symbols could not be extracted" are very
 * different answers and the UI must not blur them.
 */
export function CodeExplorerPage() {
  const { repositoryId, revisionId, selectRevision } = useSelection();
  const [searchParams] = useSearchParams();
  const [mode, setMode] = useState<LeftMode>("files");
  const [query, setQuery] = useState("");
  const [selectedFileId, setSelectedFileId] = useState<Uuid | null>(null);
  const [selectedSymbol, setSelectedSymbol] = useState<SymbolView | null>(null);
  const [tab, setTab] = useState<DetailTab>("source");
  // Which file the findings tab is scoped to, and the location a finding asked to open.
  // Both are consumed as soon as they can be, so the screen never claims to be showing
  // something it is not.
  const [findingsPath, setFindingsPath] = useState<string | null>(null);
  const [findingTarget, setFindingTarget] = useState<{ path: string; symbolId: Uuid | null } | null>(null);
  const [findingHighlight, setFindingHighlight] = useState<{ start: number | null; end: number | null } | null>(null);
  const deepLinkKey = useRef<string | null>(null);
  const deepLinkScroll = useRef(false);

  const coverage = useCoverage(repositoryId, revisionId);
  const files = useFiles(repositoryId, revisionId);
  const revisions = useRevisions(repositoryId);
  const symbols = useSymbols(repositoryId, query, revisionId);
  const mappings = useMappings(repositoryId, revisionId);

  // Deep links can point at any indexed file. The list request is bounded to its first
  // page, so the path lookup is what makes a link to file 1,200 work at all.
  const deepLinkPath = searchParams.get("path");
  const deepLinkFile = useFileByPath(repositoryId, deepLinkPath, revisionId);

  const fileList = files.data?.items ?? [];
  const linkedFile =
    deepLinkFile.data !== undefined && !fileList.some((file) => file.id === deepLinkFile.data?.id)
      ? deepLinkFile.data
      : null;
  const selectedFile = useMemo(
    () =>
      fileList.find((file) => file.id === selectedFileId) ??
      (deepLinkFile.data?.id === selectedFileId ? deepLinkFile.data : null),
    [fileList, selectedFileId, deepLinkFile.data],
  );

  const content = useFileContent(repositoryId, selectedFile?.relativePath ?? null, revisionId);
  const fileSymbols = useFileSymbols(repositoryId, selectedFileId);
  const references = useSymbolReferences(repositoryId, selectedSymbol?.id ?? null);
  // The run header for the findings tab, and the count for the selected file. Both are
  // reads of the same analysis: the browser computes no finding count of its own.
  const findingSummary = useFindingSummary(repositoryId, revisionId);
  const fileFindings = useFindings(
    repositoryId,
    { path: selectedFile?.relativePath, size: 1 },
    { enabled: selectedFile !== null },
  );

  // One sentence describing what was read and what was deliberately not read, built
  // entirely from the API's own numbers: the browser must never compute a coverage
  // claim of its own.
  const coverageNote = coverage.data === undefined ? null : formatCoverageSentence(coverage.data);

  // Adopt the first file once the list arrives, and forget a selection that the current
  // revision no longer contains.
  useEffect(() => {
    if (files.data === undefined) {
      return;
    }
    if (selectedFileId === null) {
      const first = fileList[0];
      if (first !== undefined) {
        setSelectedFileId(first.id);
      }
      return;
    }
    if (!fileList.some((file) => file.id === selectedFileId) && deepLinkFile.data?.id !== selectedFileId) {
      setSelectedFileId(fileList[0]?.id ?? null);
    }
  }, [files.data, fileList, selectedFileId, deepLinkFile.data]);

  // Deep links from the universe inspector and the findings screen open an exact file and
  // line. The URL is left intact so the location can be shared; the guard is keyed by
  // path+line, so choosing another file afterwards is not overridden on the next render.
  useEffect(() => {
    const path = searchParams.get("path");
    if (path === null) {
      return;
    }
    const lineParam = searchParams.get("line");
    const key = `${path}|${lineParam ?? ""}`;
    if (deepLinkKey.current === key) {
      return;
    }
    const file =
      fileList.find((candidate) => candidate.relativePath === path) ??
      (deepLinkFile.data?.relativePath === path ? deepLinkFile.data : undefined);
    if (file === undefined) {
      return;
    }
    deepLinkKey.current = key;
    deepLinkScroll.current = true;
    setSelectedFileId(file.id);
    setSelectedSymbol(null);
    setTab("source");
    const line = lineParam === null ? Number.NaN : Number(lineParam);
    setFindingHighlight(Number.isFinite(line) && line > 0 ? { start: line, end: line } : null);
  }, [searchParams, fileList, deepLinkFile.data]);

  // On a narrow layout the file list and the reader are stacked. A shared link must land
  // on the requested source, not on the list. Once the content arrives, centre the exact
  // highlighted line: the page and the code pane are both scroll containers.
  useEffect(() => {
    if (!deepLinkScroll.current || selectedFile === null) {
      return;
    }
    if (!window.matchMedia("(max-width: 1080px)").matches) {
      deepLinkScroll.current = false;
      return;
    }
    if (content.data === undefined) {
      document.getElementById("file-detail")?.scrollIntoView({ behavior: "auto", block: "start" });
      return;
    }
    deepLinkScroll.current = false;
    const line = document.querySelector<HTMLElement>("#file-detail .code-line.highlight");
    if (line !== null) {
      line.scrollIntoView({ behavior: "auto", block: "center" });
    } else {
      document.getElementById("file-detail")?.scrollIntoView({ behavior: "auto", block: "start" });
    }
  }, [selectedFile, content.data]);

  // A finding clicked in the findings tab asks for a file and a symbol. The symbol can only
  // be resolved once the target file's symbols have loaded, so the request waits here
  // instead of the panel guessing which declaration a line belongs to.
  useEffect(() => {
    if (findingTarget === null || fileSymbols.data === undefined) {
      return;
    }
    if (selectedFile === null || selectedFile.relativePath !== findingTarget.path) {
      return;
    }
    const symbol =
      findingTarget.symbolId === null
        ? undefined
        : fileSymbols.data.find((candidate) => candidate.id === findingTarget.symbolId);
    if (symbol !== undefined) {
      setSelectedSymbol(symbol);
    }
    setFindingTarget(null);
  }, [findingTarget, fileSymbols.data, selectedFile]);

  if (repositoryId === null) {
    return (
      <>
        <PageHeader title="Code Explorer" description="Browse files, symbols, API mappings and indexed coverage." />
        <Card>
          <RepositoryRequired />
        </Card>
      </>
    );
  }

  const notIndexed =
    coverage.isError &&
    coverage.error instanceof ApiError &&
    coverage.error.code === "CONFLICT";

  if (notIndexed) {
    return (
      <>
        <PageHeader title="Code Explorer" description="Browse files, symbols, API mappings and indexed coverage." />
        <Card>
          <EmptyState
            icon="code"
            title="This repository has not been indexed"
            message="The Code Explorer reads from an index. Queue an indexing job and this screen will fill in as soon as it finishes."
            action={<IndexButton repositoryId={repositoryId} />}
          />
        </Card>
      </>
    );
  }

  if (coverage.isError) {
    return (
      <ErrorState
        message={coverage.error instanceof Error ? coverage.error.message : "Coverage could not be loaded."}
        onRetry={() => void coverage.refetch()}
      />
    );
  }

  const filteredFiles = fileList.filter((file) =>
    query.trim().length === 0 ? true : file.relativePath.toLowerCase().includes(query.trim().toLowerCase()),
  );

  const fileSymbolIds = new Set((fileSymbols.data ?? []).map((symbol) => symbol.id));
  const fileMappings = (mappings.data ?? []).filter(
    (mapping) => hasValue(mapping.symbolId) && fileSymbolIds.has(mapping.symbolId),
  );

  // Zero means the indexer recorded no position, so it must not become a highlight.
  // A finding's own line takes precedence over the enclosing symbol's range: the reader
  // asked to see the finding, not the declaration around it.
  const highlightStart =
    findingHighlight?.start ??
    (selectedSymbol !== null && hasValue(selectedSymbol.startLine) && selectedSymbol.startLine > 0
      ? selectedSymbol.startLine
      : null);
  const highlightEnd =
    findingHighlight?.end ??
    (selectedSymbol !== null && hasValue(selectedSymbol.endLine) && selectedSymbol.endLine > 0
      ? selectedSymbol.endLine
      : null);

  const fileFindingCount = fileFindings.data?.totalItems ?? 0;

  /**
   * Opens a finding's location.
   *
   * The highlight is only applied when the file is part of this revision. A line number
   * from another revision would otherwise be drawn over unrelated code in whichever file
   * happens to be displayed, which reads as a citation of the wrong code.
   */
  const openFinding = (finding: FindingView) => {
    const target = fileList.find((file) => file.relativePath === finding.sourcePath);
    setFindingTarget({ path: finding.sourcePath ?? "", symbolId: finding.symbolId ?? null });
    setFindingHighlight(
      target === undefined
        ? null
        : {
            start: hasValue(finding.startLine) && finding.startLine > 0 ? finding.startLine : null,
            end: hasValue(finding.endLine) && finding.endLine > 0 ? finding.endLine : null,
          },
    );
    setSelectedSymbol(null);
    if (target !== undefined) {
      setSelectedFileId(target.id);
    }
    setTab("source");
  };

  return (
    <>
      <PageHeader
        title="Code Explorer"
        description={coverage.data !== undefined ? `${formatNumber(coverage.data.totalFiles)} files in the indexed revision` : undefined}
        actions={
          <>
            <select
              className="select"
              aria-label="Revision"
              style={{ width: "auto" }}
              value={revisionId ?? ""}
              onChange={(event) => selectRevision(event.target.value.length > 0 ? event.target.value : null)}
            >
              <option value="">Latest revision</option>
              {(revisions.data ?? []).map((revision) => (
                <option key={revision.id} value={revision.id}>
                  {revision.revision}
                  {hasValue(revision.branch) ? ` · ${revision.branch}` : ""}
                </option>
              ))}
            </select>
            <IndexButton repositoryId={repositoryId} />
          </>
        }
      />

      {deepLinkPath !== null && deepLinkFile.isError ? (
        <Banner tone="warning" title="That deep link did not resolve">
          No indexed file at <span className="mono-wrap">{deepLinkPath}</span> in this revision.
          The explorer has kept its normal first file; the path in the address bar is unchanged.
        </Banner>
      ) : null}

      {coverage.data !== undefined ? (
        <>
          <div className="chip-row" style={{ marginBottom: "var(--space-3)" }}>
            <Badge tone="success">{formatNumber(coverage.data.parsedFiles)} parsed</Badge>
            {coverage.data.problemFiles > 0 ? (
              <Badge tone="warning">{formatNumber(coverage.data.problemFiles)} parsed with problems</Badge>
            ) : null}
            {coverage.data.failedFiles > 0 ? (
              <Badge tone="danger">{formatNumber(coverage.data.failedFiles)} failed</Badge>
            ) : null}
            {coverage.data.recordedFiles > 0 ? (
              <Badge tone="neutral">{formatNumber(coverage.data.recordedFiles)} recorded</Badge>
            ) : null}
            {coverage.data.excludedFiles > 0 ? (
              <Badge tone="warning">{formatNumber(coverage.data.excludedFiles)} not read</Badge>
            ) : null}
            {coverage.data.prunedDirectories > 0 ? (
              <Badge tone="neutral">
                {formatNumber(coverage.data.prunedDirectories)} dirs not walked
              </Badge>
            ) : null}
            <Badge tone="info">{formatNumber(coverage.data.symbolCount)} symbols</Badge>
            <Badge tone="neutral">{formatNumber(coverage.data.edgeCount)} references</Badge>
            <Badge tone="neutral">{formatNumber(coverage.data.mappingCount)} mappings</Badge>
          </div>
          {/*
            The exclusions are stated in words, not just counted. "3 not read" is not
            enough: the reader has to be able to see that they were credential files.
            Every number here comes from the API; nothing is inferred in the browser.
          */}
          {coverageNote !== null ? (
            <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginBottom: "var(--space-4)" }}>
              {coverageNote}
            </p>
          ) : null}        </>
      ) : null}

      <div className="split">
        <Card flush>
          <div style={{ padding: "var(--space-3) var(--space-4)", borderBottom: "1px solid var(--border-subtle)" }}>
            <div className="toolbar" style={{ marginBottom: "var(--space-3)" }}>
              <div className="segmented">
                <button type="button" aria-pressed={mode === "files"} onClick={() => setMode("files")}>
                  Files
                </button>
                <button type="button" aria-pressed={mode === "symbols"} onClick={() => setMode("symbols")}>
                  Symbols
                </button>
              </div>
            </div>
            <SearchInput
              value={query}
              onChange={setQuery}
              ariaLabel={mode === "files" ? "Filter files by path" : "Search symbols"}
              placeholder={mode === "files" ? "Filter by path…" : "Search symbols by name…"}
            />
          </div>
          <div className="list">
            {mode === "files" && linkedFile !== null ? (
              <div
                className={linkedFile.id === selectedFileId ? "list-item selected" : "list-item"}
                onClick={() => {
                  setSelectedFileId(linkedFile.id);
                  setSelectedSymbol(null);
                  setFindingHighlight(null);
                  setTab("source");
                }}
              >
                <span className="list-item-title rp-truncate" title={linkedFile.relativePath}>
                  {linkedFile.relativePath}
                </span>
                <span className="list-item-sub">
                  <Badge tone="info">Linked file</Badge>
                  {titleCase(linkedFile.parseStatus)}
                  {hasValue(linkedFile.lineCount) ? ` · ${formatNumber(linkedFile.lineCount)} lines` : ""}
                </span>
              </div>
            ) : null}
            {mode === "files" ? (
              files.isLoading ? (
                <Loading label="Loading files" />
              ) : filteredFiles.length === 0 ? (
                <div className="state">
                  <p className="rp-muted">No files match.</p>
                </div>
              ) : (
                filteredFiles.map((file) => (
                  <div
                    key={file.id}
                    className={file.id === selectedFileId ? "list-item selected" : "list-item"}
                    onClick={() => {
                      setSelectedFileId(file.id);
                      setSelectedSymbol(null);
                      setFindingHighlight(null);
                      setTab("source");
                    }}
                  >
                    <span className="list-item-title rp-truncate" title={file.relativePath}>
                      {file.relativePath}
                    </span>
                    <span className="list-item-sub">
                      <span className={`resolution-dot ${file.parseStatus === "PARSED" ? "resolved" : file.parseStatus === "PARSED_WITH_PROBLEMS" ? "inferred" : "unresolved"}`} />
                      {titleCase(file.parseStatus)}
                      {hasValue(file.lineCount) ? ` · ${formatNumber(file.lineCount)} lines` : ""}
                      {` · ${formatBytes(file.sizeBytes)}`}
                    </span>
                  </div>
                ))
              )
            ) : symbols.isLoading ? (
              <Loading label="Searching symbols" />
            ) : (symbols.data?.items ?? []).length === 0 ? (
              <div className="state">
                <p className="rp-muted">No symbols match.</p>
              </div>
            ) : (
              (symbols.data?.items ?? []).map((symbol) => (
                <div
                  key={symbol.id}
                  className={symbol.id === selectedSymbol?.id ? "list-item selected" : "list-item"}
                  onClick={() => {
                    setSelectedFileId(symbol.sourceFileId);
                    setSelectedSymbol(symbol);
                    setFindingHighlight(null);
                    setTab("symbols");
                  }}
                >
                  <span className="list-item-title rp-truncate">{symbol.name}</span>
                  <span className="list-item-sub">
                    {titleCase(symbol.kind)}
                    {hasValue(symbol.startLine) && symbol.startLine > 0 ? ` · line ${symbol.startLine}` : ""}
                    {hasValue(symbol.qualifiedName) ? ` · ${symbol.qualifiedName}` : ""}
                  </span>
                </div>
              ))
            )}
          </div>
        </Card>

        <Card flush id="file-detail">
          {selectedFile === null ? (
            <EmptyState icon="file" title="No file selected" message="Pick a file on the left to read it." />
          ) : (
            <>
              <div style={{ padding: "var(--space-4) var(--space-5)", borderBottom: "1px solid var(--border-subtle)" }}>
                <div className="rp-row" style={{ alignItems: "flex-start" }}>
                  <div style={{ minWidth: 0 }}>
                    <div className="rp-mono rp-truncate" style={{ fontWeight: "var(--weight-medium)" }}>
                      {selectedFile.relativePath}
                    </div>
                    <div className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                      {selectedFile.language}
                      {hasValue(selectedFile.packageName) ? ` · package ${selectedFile.packageName}` : ""}
                      {hasValue(selectedFile.lineCount) ? ` · ${formatNumber(selectedFile.lineCount)} lines` : ""}
                      {` · ${formatBytes(selectedFile.sizeBytes)}`}
                    </div>
                  </div>
                  <div className="rp-spacer" />
                  {fileFindingCount > 0 ? (
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      onClick={() => {
                        setFindingsPath(selectedFile.relativePath);
                        setTab("findings");
                      }}
                      title="Show the findings for this file"
                    >
                      <Icon name="alert" size={14} />
                      {formatNumber(fileFindingCount)}{" "}
                      {fileFindingCount === 1 ? "finding" : "findings"} in this file
                    </button>
                  ) : null}
                  <Badge tone={parseStatusTone(selectedFile.parseStatus)} dot>
                    {selectedFile.parseStatus}
                  </Badge>
                </div>
              </div>

              {hasValue(selectedFile.parseError) && selectedFile.parseError.trim().length > 0 ? (
                <div style={{ padding: "var(--space-4) var(--space-5) 0" }}>
                  <Banner tone="warning" title="This file did not parse cleanly">
                    Symbols and references from this file may be incomplete. The parser’s report:{" "}
                    <span className="mono-wrap">{selectedFile.parseError}</span>
                  </Banner>
                </div>
              ) : null}

              <div style={{ padding: "var(--space-4) var(--space-5) var(--space-3)" }}>
                <div className="tabs">
                  <button type="button" aria-selected={tab === "source"} onClick={() => setTab("source")}>
                    <Icon name="file" size={14} />
                    Source
                  </button>
                  <button type="button" aria-selected={tab === "symbols"} onClick={() => setTab("symbols")}>
                    <Icon name="code" size={14} />
                    Symbols
                    <span className="tab-count">{formatNumber((fileSymbols.data ?? []).length)}</span>
                  </button>
                  <button type="button" aria-selected={tab === "mappings"} onClick={() => setTab("mappings")}>
                    <Icon name="plug" size={14} />
                    Mappings
                    <span className="tab-count">{formatNumber(fileMappings.length)}</span>
                  </button>
                  <button type="button" aria-selected={tab === "findings"} onClick={() => setTab("findings")}>
                    <Icon name="alert" size={14} />
                    Findings
                    <span className="tab-count">{formatNumber(findingSummary.data?.total ?? 0)}</span>
                  </button>
                </div>
              </div>

              <div style={{ padding: "0 var(--space-5) var(--space-5)" }}>
                {tab === "source" ? (
                  content.isLoading ? (
                    <Loading label="Reading file" />
                  ) : content.isError ? (
                    <ErrorState
                      message={content.error instanceof Error ? content.error.message : "The file could not be read."}
                      onRetry={() => void content.refetch()}
                    />
                  ) : content.data !== undefined ? (
                    <>
                      <CodeView
                        content={content.data.content}
                        highlightStart={highlightStart}
                        highlightEnd={highlightEnd}
                      />
                      {content.data.truncated ? (
                        <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-2)" }}>
                          This file is larger than the index limit, so only the first part is shown.
                        </p>
                      ) : null}
                    </>
                  ) : null
                ) : null}

                {tab === "symbols" ? (
                  fileSymbols.isLoading ? (
                    <Loading label="Loading symbols" />
                  ) : (fileSymbols.data ?? []).length === 0 ? (
                    <EmptyState
                      icon="code"
                      title="No symbols extracted"
                      message={
                        selectedFile.parseStatus === "PARSED"
                          ? "This file parsed successfully but declares no classes, methods or fields the indexer records."
                          : "This file did not parse cleanly, so its symbols may be missing."
                      }
                    />
                  ) : (
                    <div className="table-wrap">
                      <table className="data">
                        <thead>
                          <tr>
                            <th>Kind</th>
                            <th>Name</th>
                            <th>Signature</th>
                            <th className="table-num">Lines</th>
                            <th className="table-num">Complexity</th>
                          </tr>
                        </thead>
                        <tbody>
                          {(fileSymbols.data ?? []).map((symbol) => (
                            <tr
                              key={symbol.id}
                              className={symbol.id === selectedSymbol?.id ? "clickable selected" : "clickable"}
                              onClick={() => {
                                setSelectedSymbol(symbol);
                                setTab("source");
                              }}
                            >
                              <td>
                                <Badge tone="neutral">{symbol.kind}</Badge>
                              </td>
                              <td className="rp-mono">{symbol.name}</td>
                              <td className="rp-mono rp-truncate" style={{ maxWidth: 380 }} title={symbol.signature ?? ""}>
                                {symbol.signature ?? "—"}
                              </td>
                              <td className="table-num">{formatLineRange(symbol.startLine, symbol.endLine)}</td>
                              <td className="table-num">{symbol.complexity ?? "—"}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )
                ) : null}

                {tab === "mappings" ? (
                  fileMappings.length === 0 ? (
                    <EmptyState
                      icon="plug"
                      title="No API or messaging mappings in this file"
                      message="Mappings are detected from Spring annotations (@RequestMapping, @KafkaListener, and similar)."
                    />
                  ) : (
                    <div className="table-wrap">
                      <table className="data">
                        <thead>
                          <tr>
                            <th>Type</th>
                            <th>Method</th>
                            <th>Path / topic</th>
                            <th>Detail</th>
                            <th className="table-num">Line</th>
                          </tr>
                        </thead>
                        <tbody>
                          {fileMappings.map((mapping) => (
                            <tr key={mapping.id}>
                              <td>
                                <Badge tone="info">{mapping.mappingType}</Badge>
                              </td>
                              <td className="rp-mono">{mapping.httpMethod ?? "—"}</td>
                              <td className="rp-mono">{mapping.path ?? mapping.topic ?? "—"}</td>
                              <td className="rp-truncate" style={{ maxWidth: 320 }} title={mapping.detail ?? ""}>
                                {mapping.detail ?? "—"}
                              </td>
                              <td className="table-num">{formatLine(mapping.startLine)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )
                ) : null}

                {tab === "findings" ? (
                  <FindingsPanel
                    repositoryId={repositoryId}
                    revisionId={revisionId}
                    pathFilter={findingsPath}
                    onClearPathFilter={() => setFindingsPath(null)}
                    onOpenLocation={openFinding}
                  />
                ) : null}
              </div>

              {selectedSymbol !== null ? (
                <div style={{ padding: "0 var(--space-5) var(--space-5)" }}>
                  <div className="divider" style={{ marginBottom: "var(--space-4)" }} />
                  <div className="section-row">
                    <h3 style={{ fontSize: "var(--text-md)" }}>
                      References to <span className="rp-mono">{selectedSymbol.name}</span>
                    </h3>
                    <div className="rp-spacer" />
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setSelectedSymbol(null)}>
                      Clear
                    </button>
                  </div>
                  {references.isLoading ? (
                    <Loading label="Loading references" />
                  ) : (references.data ?? []).length === 0 ? (
                    <p className="rp-muted" style={{ fontSize: "var(--text-sm)" }}>
                      No incoming references were resolved to this symbol in this revision.
                    </p>
                  ) : (
                    <div className="table-wrap">
                      <table className="data">
                        <thead>
                          <tr>
                            <th>Edge</th>
                            <th>Target</th>
                            <th>Resolution</th>
                            <th className="table-num">Line</th>
                            <th>Detail</th>
                          </tr>
                        </thead>
                        <tbody>
                          {(references.data ?? []).map((edge) => (
                            <tr key={edge.id}>
                              <td className="rp-mono">{edge.edgeType}</td>
                              <td className="rp-mono rp-truncate" style={{ maxWidth: 260 }}>
                                {edge.targetName}
                              </td>
                              <td>
                                <span className={`resolution-dot ${edge.resolution.toLowerCase()}`} />
                                {edge.resolution}
                              </td>
                              <td className="table-num">{formatLine(edge.sourceLine)}</td>
                              <td className="rp-truncate" style={{ maxWidth: 260 }} title={edge.detail ?? ""}>
                                {edge.detail ?? "—"}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              ) : null}
            </>
          )}
        </Card>
      </div>
    </>
  );
}
