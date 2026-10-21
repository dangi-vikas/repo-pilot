import { Fragment, useEffect, useMemo, useRef, useState } from "react";
import { RepositoryRequired } from "@/components/RepositoryRequired";
import {
  Badge,
  Banner,
  Card,
  EmptyState,
  ErrorState,
  HypothesisNotice,
  KeyValue,
  Loading,
  PageHeader,
} from "@/components/ui";
import {
  useAskInvestigation,
  useCancelInvestigation,
  useInvestigation,
  useInvestigations,
  useOverview,
} from "@/hooks/queries";
import { ApiError } from "@/lib/api";
import {
  formatInstant,
  formatLineRange,
  formatNumber,
  formatRelative,
  titleCase,
  type Tone,
} from "@/lib/format";
import type {
  AiStatus,
  ClaimVerification,
  EvidenceSourceStatus,
  InvestigationClaim,
  InvestigationDetail,
  InvestigationEvidence,
  InvestigationStatus,
  InvestigationSummary,
  Uuid,
} from "@/lib/types";
import { useSelection } from "@/state/selection";

/**
 * AI Investigation: ask a question of the indexed revision, and read the verdict on the answer.
 *
 * <h2>What this screen refuses to do</h2>
 * It never presents a model's sentence as a finding. The answer is shown as the model wrote it, with
 * the backend's claim-by-claim verdict beside it, the evidence the model was given, and the reason a
 * claim failed when one did. "Grounded" on this page means every claim cited evidence that was in the
 * prompt and every cited excerpt was re-read from disk and matched — not that the model sounded sure.
 *
 * The excerpt itself is never on this page, because it is never stored: the backend keeps a hash and a
 * length, so the page can say what was checked without becoming a prompt log.
 */
export function InvestigationPage() {
  const { repositoryId } = useSelection();

  if (repositoryId === null) {
    return (
      <>
        <PageHeader
          title="AI Investigation"
          description="Ask a question about the selected revision. The answer is checked against the indexed evidence, claim by claim."
        />
        <RepositoryRequired />
      </>
    );
  }

  return <Investigation repositoryId={repositoryId} />;
}

function Investigation({ repositoryId }: { repositoryId: Uuid }) {
  const overview = useOverview();
  const ai = overview.data?.ai;

  const [selectedId, setSelectedId] = useState<Uuid | null>(null);
  const [question, setQuestion] = useState("");
  const [scope, setScope] = useState("");
  const [highlight, setHighlight] = useState<string | null>(null);
  const highlightTimer = useRef<number | null>(null);

  // Poll only while something is in flight. A finished list is a record; refreshing it for no
  // reason would make a static page look alive. The flag is set from the data an effect has seen,
  // so the first active row turns polling on and the last terminal row turns it off.
  const [polling, setPolling] = useState(false);
  const list = useInvestigations(repositoryId, { refetchWhileActive: polling });
  const items = list.data?.items ?? [];

  useEffect(() => {
    const active =
      list.data?.items.some((item) => item.status === "QUEUED" || item.status === "RUNNING") ?? false;
    setPolling(active);
  }, [list.data]);
  const activeId = selectedId ?? items[0]?.id ?? null;
  const activeSummary = items.find((item) => item.id === activeId);
  const detailActive =
    activeSummary !== undefined &&
    (activeSummary.status === "QUEUED" || activeSummary.status === "RUNNING");

  const detail = useInvestigation(repositoryId, activeId, { refetchWhileActive: detailActive });
  const ask = useAskInvestigation(repositoryId);
  const cancel = useCancelInvestigation(repositoryId);

  const canAsk =
    ai?.usable === true && question.trim().length >= 8 && !ask.isPending && repositoryId !== null;

  const mutationError =
    ask.error instanceof ApiError
      ? ask.error
      : cancel.error instanceof ApiError
        ? cancel.error
        : null;

  function submit(): void {
    const trimmed = question.trim();
    if (trimmed.length < 8) {
      return;
    }
    ask.mutate(
      { question: trimmed, scope: scope.trim() },
      {
        onSuccess: (submission) => {
          setSelectedId(submission.investigationId);
          setQuestion("");
          setScope("");
        },
      },
    );
  }

  function cite(key: string): void {
    setHighlight(key);
    document
      .getElementById(`evidence-${key}`)
      ?.scrollIntoView({ behavior: "smooth", block: "center" });
    if (highlightTimer.current !== null) {
      window.clearTimeout(highlightTimer.current);
    }
    highlightTimer.current = window.setTimeout(() => setHighlight(null), 2_400);
  }

  const detailError = detail.error instanceof ApiError ? detail.error : null;

  return (
    <>
      <PageHeader
        title="AI Investigation"
        description="Ask a question about the selected revision. Retrieval is name and text matching over the index; every citation is checked, and a claim that does not hold up is shown as unsupported rather than smoothed over."
      />

      <ProviderNotice ai={ai} />

      {mutationError !== null ? (
        <Banner tone="danger" title="The question was not queued">
          {mutationError.message}
          {mutationError.code === "DEPENDENCY_UNAVAILABLE" ? (
            <div className="rp-faint" style={{ marginTop: "var(--space-2)", fontSize: "var(--text-xs)" }}>
              The provider is configured in the environment, not on this screen. See the setup note
              above, or open Settings for the current status.
            </div>
          ) : null}
        </Banner>
      ) : null}

      <Card>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
          className="stack-3"
        >
          <div>
            <label htmlFor="investigation-question" className="rp-muted" style={{ fontSize: "var(--text-sm)" }}>
              Question
            </label>
            <textarea
              id="investigation-question"
              className="input"
              rows={3}
              maxLength={2000}
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              placeholder="Why does the empty catch in PaymentService hide failures?"
              style={{ marginTop: "var(--space-2)", width: "100%", resize: "vertical" }}
              disabled={ai !== undefined && !ai.usable}
            />
            <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-2)" }}>
              {question.trim().length < 8
                ? "At least 8 characters, and at least one term a symbol, file or finding could match."
                : "The question becomes the investigation's title and its retrieval terms."}
            </p>
          </div>

          <div>
            <label htmlFor="investigation-scope" className="rp-muted" style={{ fontSize: "var(--text-sm)" }}>
              Scope <span className="rp-faint">(optional)</span>
            </label>
            <input
              id="investigation-scope"
              className="input"
              maxLength={500}
              value={scope}
              onChange={(event) => setScope(event.target.value)}
              placeholder="e.g. the payment module only"
              style={{ marginTop: "var(--space-2)", width: "100%" }}
              disabled={ai !== undefined && !ai.usable}
            />
            <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-2)" }}>
              Stored and shown with the answer. It does not filter retrieval in this build; the
              evidence list below is the complete record of what the model was shown.
            </p>
          </div>

          <div className="toolbar" style={{ justifyContent: "flex-end" }}>
            <button type="submit" className="btn btn-primary" disabled={!canAsk}>
              {ask.isPending ? "Queuing…" : "Investigate"}
            </button>
          </div>
        </form>
      </Card>

      <div className="split" style={{ marginTop: "var(--space-5)" }}>
        <Card title="History">
          <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginBottom: "var(--space-3)" }}>
            Every question asked of this repository, newest first.
          </p>
          {list.isPending ? <Loading label="Reading investigations" /> : null}
          {list.isError ? (
            <ErrorState
              message="The investigation list could not be read."
              onRetry={() => void list.refetch()}
            />
          ) : null}
          {!list.isPending && items.length === 0 ? (
            <EmptyState
              icon="sparkles"
              title="No investigations yet"
              message="Ask the first question above. The answer will keep the evidence it was given and the verdict on every claim."
            />
          ) : null}
          {items.length > 0 ? (
            <div className="list">
              {items.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  className={`list-item${item.id === activeId ? " selected" : ""}`}
                  onClick={() => setSelectedId(item.id)}
                  style={{ textAlign: "left", background: "none", border: "none", width: "100%" }}
                >
                  <span className="list-item-title">{item.question}</span>
                  <span className="list-item-sub">
                    {formatRelative(item.createdAt)} · {item.status.toLowerCase()}
                    {item.status === "SUCCEEDED"
                      ? item.grounded
                        ? " · grounded"
                        : " · not grounded"
                      : ""}
                    {item.evidenceCount != null ? ` · ${formatNumber(item.evidenceCount)} evidence` : ""}
                  </span>
                </button>
              ))}
            </div>
          ) : null}
        </Card>

        <Card
          title="Verdict"
          actions={
            activeSummary !== undefined &&
            (activeSummary.status === "QUEUED" || activeSummary.status === "RUNNING") ? (
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => cancel.mutate(activeSummary.id)}
                disabled={cancel.isPending}
              >
                {cancel.isPending ? "Cancelling…" : "Cancel"}
              </button>
            ) : null
          }
        >
          {detail.isPending && activeId !== null ? <Loading label="Reading the investigation" /> : null}
          {detailError !== null ? (
            <ErrorState message={detailError.message} onRetry={() => void detail.refetch()} />
          ) : null}
          {activeId === null && !detail.isPending ? (
            <EmptyState
              icon="info"
              title="Nothing selected"
              message="Ask a question, or choose one from the history, to read its evidence and verdict."
            />
          ) : null}
          {detail.data !== undefined ? <InvestigationDetailView detail={detail.data} onCite={cite} highlight={highlight} /> : null}
        </Card>
      </div>
    </>
  );
}

function ProviderNotice({ ai }: { ai: AiStatus | undefined }) {
  if (ai === undefined) {
    return null;
  }
  if (!ai.enabled) {
    return (
      <Banner tone="warning" title="AI is switched off">
        Investigations are disabled in this installation. Set{" "}
        <span className="rp-mono">REPOPILOT_AI_ENABLED=true</span> and configure a provider to ask
        questions. Everything else in RepoPilot works without a model.
      </Banner>
    );
  }
  if (!ai.usable) {
    return (
      <Banner tone="warning" title="No model is reachable">
        {ai.message ??
          "The configured provider is not answering. Investigations are refused rather than answered without a model."}
        <div style={{ marginTop: "var(--space-2)" }}>
          Start the provider (by default a local Ollama instance) and pull the configured model, or set{" "}
          <span className="rp-mono">REPOPILOT_AI_PROVIDER=deterministic</span> to exercise retrieval and
          citation checking without a model. The deterministic extractor is a test double: it quotes
          the retrieved evidence and draws no conclusions, and it says so in every answer.
        </div>
      </Banner>
    );
  }
  return (
    <p className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
      Provider <span className="rp-mono">{ai.providerId ?? "unknown"}</span>. The answer is checked
      against the indexed evidence; no source excerpt is sent anywhere except the provider you
      configured, and no excerpt is stored.
    </p>
  );
}

function InvestigationDetailView({
  detail,
  onCite,
  highlight,
}: {
  detail: InvestigationDetail;
  onCite: (key: string) => void;
  highlight: string | null;
}) {
  const statusTone = investigationStatusTone(detail.status, detail.grounded);
  const running = detail.status === "QUEUED" || detail.status === "RUNNING";

  return (
    <div className="stack-4">
      <div className="chip-row">
        <Badge tone={statusTone} dot>
          {titleCase(detail.status)}
        </Badge>
        {detail.status === "SUCCEEDED" ? (
          <Badge tone={detail.grounded ? "success" : "warning"} dot>
            {detail.grounded ? "grounded" : "not grounded"}
          </Badge>
        ) : null}
        {detail.confidence != null ? (
          <Badge tone="neutral">model confidence: {detail.confidence.toLowerCase()}</Badge>
        ) : null}
        {detail.evidenceCount != null ? (
          <Badge tone="neutral">{formatNumber(detail.evidenceCount)} evidence items</Badge>
        ) : null}
      </div>

      <div>
        <div className="list-item-title" style={{ fontSize: "var(--text-md)" }}>
          {detail.question}
        </div>
        <div className="rp-faint" style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-1)" }}>
          Asked {formatInstant(detail.createdAt)}
          {detail.completedAt != null ? ` · finished ${formatRelative(detail.completedAt)}` : ""}
          {detail.revisionId != null ? (
            <>
              {" · revision "}
              <span className="rp-mono">{detail.revisionId.slice(0, 8)}</span>
            </>
          ) : null}
        </div>
        {detail.scope != null ? (
          <p className="rp-muted" style={{ fontSize: "var(--text-sm)", marginTop: "var(--space-2)" }}>
            Scope: {detail.scope}
          </p>
        ) : null}
      </div>

      {running ? (
        <Banner tone="info" title="The investigation is still running">
          Retrieval, the model call and the citation check happen in a job. This page refreshes while
          it is in flight; the answer appears here when the job has finished.
        </Banner>
      ) : null}

      {detail.status === "FAILED" ? (
        <Banner tone="danger" title="No answer was produced">
          {detail.errorMessage ?? "The job failed without recording a reason."}
        </Banner>
      ) : null}

      {detail.status === "CANCELLED" ? (
        <Banner tone="neutral" title="Cancelled">
          The investigation was cancelled before an answer was recorded. Nothing was sent to a model
          after the cancellation was observed.
        </Banner>
      ) : null}

      {detail.status === "SUCCEEDED" ? (
        <>
          <Banner
            tone={detail.grounded ? "success" : "warning"}
            title={detail.grounded ? "Grounded in the evidence" : "Not grounded"}
          >
            {detail.grounded ? (
              <>
                Every one of the {formatNumber(detail.claims.length)} claim(s) cites evidence that was
                in the prompt, and every cited excerpt was re-read from disk and matched when the answer
                was checked.
              </>
            ) : (
              <>
                {formatNumber(detail.unsupportedClaims)} unsupported and{" "}
                {formatNumber(detail.partialClaims)} partly supported of{" "}
                {formatNumber(detail.claims.length)} claim(s). The answer is shown as the model wrote it;
                read the claims below before relying on it.
              </>
            )}
          </Banner>

          <HypothesisNotice>
            {(detail.limitations ?? "").trim().length > 0
              ? (detail.limitations ?? "")
              : "This text was generated by a language model. The evidence and the verdicts beside it are RepoPilot's; the reasoning is not."}
          </HypothesisNotice>

          {(detail.answer ?? "").trim().length > 0 ? (
            <div className="answer-text">
              <AnswerText text={detail.answer ?? ""} onCite={onCite} />
            </div>
          ) : (
            <p className="rp-faint">The model returned no answer text.</p>
          )}

          <Claims claims={detail.claims} evidenceKeys={detail.evidence.map((item) => item.key)} onCite={onCite} />

          <Diagnostics detail={detail} />

          <EvidenceList evidence={detail.evidence} highlight={highlight} />

          <div>
            <div className="list-item-title" style={{ marginBottom: "var(--space-2)" }}>
              Provenance
            </div>
            <KeyValue
              items={[
                { label: "Provider", value: detail.modelProvider ?? "-" },
                { label: "Model", value: detail.modelName ?? "-" },
                {
                  label: "Prompt",
                  value: `${formatNumber(detail.promptChars)} chars, ${formatNumber(detail.contextChars)} of evidence`,
                },
                {
                  label: "Prompt SHA-256",
                  value: detail.promptSha256 != null ? (
                    <span className="rp-mono" title={detail.promptSha256}>
                      {detail.promptSha256.slice(0, 16)}…
                    </span>
                  ) : (
                    "-"
                  ),
                },
                { label: "Model latency", value: detail.latencyMs != null ? `${formatNumber(detail.latencyMs)} ms` : "-" },
                { label: "Retrieval", value: detail.retrievalSummary ?? "-" },
              ]}
            />
            <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-2)" }}>
              The prompt is hashed rather than stored: it contained source excerpts, and a stored prompt
              would be a log of them.
            </p>
          </div>
        </>
      ) : null}
    </div>
  );
}

/**
 * The model's text, with each `[En]` marker turned into a link to the evidence it names.
 *
 * The text is not Markdown-rendered on purpose: a renderer would hide the markers inside formatting
 * and could execute links the model wrote. What is shown is exactly what was stored.
 */
function AnswerText({ text, onCite }: { text: string; onCite: (key: string) => void }) {
  const parts = useMemo(() => text.split(/(\[E\d+\])/g), [text]);
  return (
    <>
      {parts.map((part, index) => {
        const marker = /^\[(E\d+)\]$/.exec(part);
        if (marker === null) {
          return <Fragment key={index}>{part}</Fragment>;
        }
        return (
          <button
            key={index}
            type="button"
            className="citation-chip"
            onClick={() => onCite(marker[1] as string)}
            title={`Show evidence ${marker[1]}`}
          >
            {marker[1]}
          </button>
        );
      })}
    </>
  );
}

function Claims({
  claims,
  evidenceKeys,
  onCite,
}: {
  claims: InvestigationClaim[];
  evidenceKeys: string[];
  onCite: (key: string) => void;
}) {
  if (claims.length === 0) {
    return (
      <Banner tone="warning" title="No claims to check">
        The answer contained no claim list, so there was nothing to verify. A prose answer without
        claims is never marked grounded.
      </Banner>
    );
  }
  const known = new Set(evidenceKeys);
  return (
    <div>
      <div className="list-item-title" style={{ marginBottom: "var(--space-2)" }}>
        Claims and verdicts
      </div>
      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th>#</th>
              <th>Claim (as the model wrote it)</th>
              <th>Kind</th>
              <th>Verdict</th>
            </tr>
          </thead>
          <tbody>
            {claims.map((claim) => (
              <tr key={claim.index}>
                <td className="table-num">{claim.index + 1}</td>
                <td style={{ wordBreak: "break-word" }}>
                  {claim.text}
                  {claim.notes != null && claim.verificationStatus !== "VERIFIED" ? (
                    <div className="rp-faint" style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-1)" }}>
                      {claim.notes}
                    </div>
                  ) : null}
                  <div style={{ marginTop: "var(--space-2)" }}>
                    {claim.citedKeys.length === 0 ? (
                      <span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                        cites no evidence
                      </span>
                    ) : (
                      claim.citedKeys.map((key) =>
                        known.has(key) ? (
                          <button
                            key={key}
                            type="button"
                            className="citation-chip"
                            onClick={() => onCite(key)}
                            title={`Show evidence ${key}`}
                          >
                            {key}
                          </button>
                        ) : (
                          <span key={key} className="badge badge-danger" title="This key was not in the prompt">
                            {key} not in prompt
                          </span>
                        ),
                      )
                    )}
                  </div>
                </td>
                <td className="nowrap">
                  <Badge tone={claimKindTone(claim.kind)}>{claim.kind.toLowerCase()}</Badge>
                </td>
                <td className="nowrap">
                  <Badge tone={claimVerificationTone(claim.verificationStatus)} dot>
                    {verificationLabel(claim.verificationStatus)}
                  </Badge>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Diagnostics({ detail }: { detail: InvestigationDetail }) {
  const diagnostics = detail.diagnostics;
  const nothingToSay =
    diagnostics.unanswered.length === 0 &&
    diagnostics.danglingMarkers.length === 0 &&
    diagnostics.retrievalNotes.length === 0 &&
    diagnostics.parserNotes.length === 0;
  if (nothingToSay) {
    return null;
  }
  return (
    <div>
      <div className="list-item-title" style={{ marginBottom: "var(--space-2)" }}>
        What the investigation could not do
      </div>
      <div className="stack-2">
        {diagnostics.unanswered.length > 0 ? (
          <DiagnosticList
            title="Unanswered by the model"
            items={diagnostics.unanswered}
            tone="warning"
          />
        ) : null}
        {diagnostics.danglingMarkers.length > 0 ? (
          <DiagnosticList
            title="Markers in the answer that were not in the prompt"
            items={diagnostics.danglingMarkers.map((key) => `${key} was never sent`)}
            tone="danger"
          />
        ) : null}
        {diagnostics.parserNotes.length > 0 ? (
          <DiagnosticList title="Normalised or refused from the response" items={diagnostics.parserNotes} />
        ) : null}
        {diagnostics.retrievalNotes.length > 0 ? (
          <DiagnosticList title="Retrieval notes" items={diagnostics.retrievalNotes} />
        ) : null}
      </div>
    </div>
  );
}

function DiagnosticList({
  title,
  items,
  tone = "neutral",
}: {
  title: string;
  items: string[];
  tone?: Tone;
}) {
  return (
    <div>
      <div className="rp-muted" style={{ fontSize: "var(--text-sm)" }}>
        <Badge tone={tone}>{title}</Badge>
      </div>
      <ul className="rp-faint" style={{ fontSize: "var(--text-xs)", margin: "var(--space-2) 0 0 0", paddingLeft: "var(--space-5)" }}>
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  );
}

function EvidenceList({ evidence, highlight }: { evidence: InvestigationEvidence[]; highlight: string | null }) {
  if (evidence.length === 0) {
    return null;
  }
  return (
    <div>
      <div className="list-item-title" style={{ marginBottom: "var(--space-2)" }}>
        Evidence the model was given
      </div>
      <div className="table-wrap">
        <table className="data">
          <thead>
            <tr>
              <th>Key</th>
              <th>Kind</th>
              <th>What it is</th>
              <th>Source check</th>
              <th className="table-num">Excerpt</th>
            </tr>
          </thead>
          <tbody>
            {evidence.map((item) => (
              <tr
                key={item.key}
                id={`evidence-${item.key}`}
                className={highlight === item.key ? "evidence-flash" : undefined}
              >
                <td className="nowrap">
                  <Badge tone={item.cited ? "accent" : "neutral"}>{item.key}</Badge>
                </td>
                <td className="nowrap">{item.kind.toLowerCase()}</td>
                <td style={{ overflowWrap: "anywhere" }}>
                  <div>{item.label}</div>
                  {item.detail != null ? (
                    <div className="rp-faint" style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-1)" }}>
                      {item.detail}
                    </div>
                  ) : null}
                  {/* A file item's label is already its path, so repeating it here would be noise.
                      The API omits null fields, so an absent line number arrives as undefined. */}
                  {item.path !== item.label || item.startLine != null ? (
                    <div
                      className="rp-mono rp-faint"
                      style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-1)" }}
                      title={item.path != null
                        ? `${item.path}${item.startLine != null ? `:${formatLineRange(item.startLine, item.endLine)}` : ""}`
                        : undefined}>
                      {item.path != null ? (
                        <>
                          {/* Break opportunities after the separators; wbr adds no characters to the text. */}
                          {item.path.split("/").map((segment, index, segments) => (
                            <Fragment key={index}>
                              {segment}
                              {index < segments.length - 1 ? "/" : ""}
                              {index < segments.length - 1 ? <wbr /> : null}
                            </Fragment>
                          ))}
                          {item.startLine != null ? `:${formatLineRange(item.startLine, item.endLine)}` : ""}
                        </>
                      ) : (
                        <span>no location</span>
                      )}
                    </div>
                  ) : null}
                  {item.retrievalMethod != null ? (
                    <div className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                      retrieved by {item.retrievalMethod}
                    </div>
                  ) : null}
                </td>
                <td className="nowrap">
                  <Badge tone={sourceStatusTone(item.sourceStatus)}>
                    {sourceStatusLabel(item.sourceStatus)}
                  </Badge>
                </td>
                <td className="table-num">
                  {item.excerptChars != null ? `${formatNumber(item.excerptChars)} chars` : "-"}
                  {item.cited ? (
                    <div className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>
                      cited
                    </div>
                  ) : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="rp-faint" style={{ fontSize: "var(--text-xs)", marginTop: "var(--space-2)" }}>
        The excerpt text is not stored. A hash of the exact lines sent is kept so the check can re-read
        the same range, and <em>source check</em> is that re-read: confirmed means the lines still
        match, changed means the file moved under the answer, and no location means there was no line
        to re-read.
      </p>
    </div>
  );
}

function investigationStatusTone(status: InvestigationStatus, grounded: boolean): Tone {
  switch (status) {
    case "SUCCEEDED":
      return grounded ? "success" : "warning";
    case "FAILED":
      return "danger";
    case "CANCELLED":
      return "neutral";
    case "RUNNING":
      return "info";
    default:
      return "neutral";
  }
}

function claimVerificationTone(status: ClaimVerification): Tone {
  switch (status) {
    case "VERIFIED":
      return "success";
    case "PARTIAL":
      return "warning";
    case "UNSUPPORTED":
      return "danger";
  }
}

function verificationLabel(status: ClaimVerification): string {
  switch (status) {
    case "VERIFIED":
      return "verified";
    case "PARTIAL":
      return "partly supported";
    case "UNSUPPORTED":
      return "unsupported";
  }
}

function claimKindTone(kind: InvestigationClaim["kind"]): Tone {
  switch (kind) {
    case "OBSERVATION":
      return "info";
    case "INFERENCE":
      return "warning";
    case "RECOMMENDATION":
      return "neutral";
  }
}

function sourceStatusTone(status: EvidenceSourceStatus | null): Tone {
  switch (status) {
    case "CONFIRMED":
      return "success";
    case "CHANGED":
      return "warning";
    case "MISSING":
      return "danger";
    case "NOT_LOCATED":
      return "neutral";
    default:
      return "neutral";
  }
}

function sourceStatusLabel(status: EvidenceSourceStatus | null): string {
  switch (status) {
    case "CONFIRMED":
      return "confirmed";
    case "CHANGED":
      return "changed since sent";
    case "MISSING":
      return "missing";
    case "NOT_LOCATED":
      return "no location";
    default:
      return "not checked";
  }
}

/** Kept next to the page so a future summary view can reuse the same status wording. */
export function investigationSummaryLabel(item: InvestigationSummary): string {
  if (item.status !== "SUCCEEDED") {
    return item.status.toLowerCase();
  }
  return item.grounded ? "grounded" : "not grounded";
}
