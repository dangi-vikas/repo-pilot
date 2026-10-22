import type { ReactNode } from "react";
import { Icon, type IconName } from "@/components/icons";
import type { Tone } from "@/lib/format";

/** Reusable presentational primitives shared across every screen. */

export function Badge({
  tone = "neutral",
  children,
  dot = false,
  className,
}: {
  tone?: Tone;
  children: ReactNode;
  dot?: boolean;
  className?: string;
}) {
  const classes = ["badge", `badge-${tone}`, className].filter(Boolean).join(" ");
  return (
    <span className={classes}>
      {dot ? <span className="badge-dot" /> : null}
      {children}
    </span>
  );
}

export function Card({
  title,
  actions,
  children,
  flush = false,
  className,
  id,
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  flush?: boolean;
  className?: string;
  id?: string;
}) {
  return (
    <section id={id} className={["card", className].filter(Boolean).join(" ")}>
      {title !== undefined || actions !== undefined ? (
        <header className="card-header">
          {typeof title === "string" ? <h3 className="card-title">{title}</h3> : title}
          {actions !== undefined ? <div className="rp-spacer" /> : null}
          {actions}
        </header>
      ) : null}
      <div className={flush ? "card-body flush" : "card-body"}>{children}</div>
    </section>
  );
}

export function Metric({
  label,
  value,
  foot,
  accent = false,
}: {
  label: string;
  value: ReactNode;
  foot?: ReactNode;
  accent?: boolean;
}) {
  return (
    <div className={accent ? "metric metric-accent" : "metric"}>
      <span className="metric-label">{label}</span>
      <span className="metric-value">{value}</span>
      {foot !== undefined ? <span className="metric-foot">{foot}</span> : null}
    </div>
  );
}

export function Spinner({ large = false }: { large?: boolean }) {
  return <span className={large ? "spinner spinner-lg" : "spinner"} aria-hidden="true" />;
}

export function Loading({ label = "Loading" }: { label?: string }) {
  return (
    <div className="state" role="status" aria-live="polite">
      <Spinner large />
      <p className="rp-muted">{label}…</p>
    </div>
  );
}

export function EmptyState({
  icon = "info",
  title,
  message,
  action,
}: {
  icon?: IconName;
  title: string;
  message?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="state">
      <span className="state-icon">
        <Icon name={icon} size={20} />
      </span>
      <h3>{title}</h3>
      {message !== undefined ? <p>{message}</p> : null}
      {action}
    </div>
  );
}

export function ErrorState({
  title = "Something went wrong",
  message,
  onRetry,
}: {
  title?: string;
  message: ReactNode;
  onRetry?: () => void;
}) {
  return (
    <div className="state">
      <span className="state-icon" style={{ color: "var(--signal-danger)" }}>
        <Icon name="alert" size={20} />
      </span>
      <h3>{title}</h3>
      <p>{message}</p>
      {onRetry ? (
        <button type="button" className="btn btn-sm" onClick={onRetry}>
          <Icon name="refresh" size={14} />
          Retry
        </button>
      ) : null}
    </div>
  );
}

export function Banner({
  tone = "info",
  title,
  children,
}: {
  tone?: Tone;
  title?: string;
  children: ReactNode;
}) {
  return (
    <div className={`banner banner-${tone}`} role={tone === "danger" ? "alert" : "status"}>
      <div>
        {title ? <div className="banner-title">{title}</div> : null}
        <div>{children}</div>
      </div>
    </div>
  );
}

export function ProgressBar({ value, indeterminate = false }: { value: number; indeterminate?: boolean }) {
  const clamped = Math.max(0, Math.min(100, value));
  return (
    <div
      className={indeterminate ? "progress progress-indeterminate" : "progress"}
      role="progressbar"
      aria-valuenow={indeterminate ? undefined : clamped}
      aria-valuemin={0}
      aria-valuemax={100}
    >
      <div className="progress-bar" style={{ width: `${clamped}%` }} />
    </div>
  );
}

export function SearchInput({
  value,
  onChange,
  placeholder,
  ariaLabel,
}: {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  ariaLabel: string;
}) {
  return (
    <div className="search">
      <span className="search-icon">
        <Icon name="search" size={14} />
      </span>
      <input
        className="input"
        type="search"
        value={value}
        placeholder={placeholder}
        aria-label={ariaLabel}
        onChange={(event) => onChange(event.target.value)}
      />
    </div>
  );
}

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: ReactNode | undefined;
  actions?: ReactNode | undefined;
}) {
  return (
    <header className="page-head">
      <div>
        <h1>{title}</h1>
        {description !== undefined ? <p>{description}</p> : null}
      </div>
      {actions !== undefined ? <div className="page-head-actions">{actions}</div> : null}
    </header>
  );
}

export function KeyValue({ items }: { items: Array<{ label: string; value: ReactNode }> }) {
  return (
    <dl className="kv">
      {items.map((item) => (
        <div key={item.label} style={{ display: "contents" }}>
          <dt>{item.label}</dt>
          <dd>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Renders text that came from an LLM, labelled so it can never read as a measurement. */
export function HypothesisNotice({ children }: { children?: ReactNode }) {
  return (
    <Banner tone="warning" title="AI hypothesis — not a measurement">
      {children ??
        "This text was generated by a language model. It is an interpretation, not an observed fact. Any file or line it cites has been checked against the index, but the reasoning itself is not verified."}
    </Banner>
  );
}
