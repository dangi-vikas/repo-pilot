/**
 * Renders a source file with a line-number gutter.
 *
 * Files are indexed up to a size limit, so a "truncated" response is normal rather
 * than exceptional; the caller decides whether to say so. Nothing here parses or
 * colours the code — RepoPilot's claims about a file come from the index, not from
 * a syntax highlighter guessing at it.
 */
export function CodeView({
  content,
  highlightStart = null,
  highlightEnd = null,
}: {
  content: string;
  highlightStart?: number | null;
  highlightEnd?: number | null;
}) {
  const lines = content.length === 0 ? [] : content.split(/\r\n|\r|\n/);
  const from = highlightStart ?? null;
  const to = highlightEnd ?? from;

  if (lines.length === 0) {
    return <div className="code code-empty">This file is empty.</div>;
  }

  return (
    <div className="code">
      {lines.map((line, index) => {
        const number = index + 1;
        const highlighted = from !== null && number >= from && number <= (to ?? from);
        return (
          <div key={number} className={highlighted ? "code-line highlight" : "code-line"}>
            <span className="code-gutter">{number}</span>
            <span className="code-text">{line.length > 0 ? line : " "}</span>
          </div>
        );
      })}
    </div>
  );
}
