import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Icon } from "@/components/icons";
import { ApiError, api } from "@/lib/api";
import { clearToken, setToken } from "@/lib/session";
import type { AuthBootstrap } from "@/lib/types";
import { useSession } from "@/state/session";

type Probe = "checking" | "up" | "down";

/**
 * The connection gate shown before any console content.
 *
 * Two things must both be true to use RepoPilot: the backend is reachable, and the
 * operator holds the bearer token. The gate checks the first and asks for the second,
 * and it verifies the token against a real authenticated endpoint rather than trusting
 * that a pasted string is correct — an invalid token should fail here, not three
 * screens later.
 */
export function ConnectionGate() {
  const { connect } = useSession();
  const [probe, setProbe] = useState<Probe>("checking");
  const [info, setInfo] = useState<AuthBootstrap | null>(null);
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const check = useCallback(async () => {
    setProbe("checking");
    try {
      const health = await api.readiness();
      setProbe(health.status === "UP" ? "up" : "down");
    } catch {
      setProbe("down");
    }
    try {
      setInfo(await api.bootstrap());
    } catch {
      // The bootstrap document is a convenience; the form works without it.
    }
  }, []);

  useEffect(() => {
    void check();
  }, [check]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const trimmed = value.trim();
    if (trimmed.length === 0) {
      setError("Paste the token to continue.");
      return;
    }
    setBusy(true);
    setError(null);
    setToken(trimmed);
    try {
      await api.overview();
      connect(trimmed);
    } catch (cause) {
      clearToken();
      if (cause instanceof ApiError && cause.isAuthFailure) {
        setError("The backend rejected that token. Check that it matches REPOPILOT_AUTH_TOKEN.");
      } else if (cause instanceof ApiError && cause.code === "NETWORK_ERROR") {
        setError("The backend is not reachable. Start it, then try again.");
      } else {
        setError(cause instanceof ApiError ? cause.message : "The token could not be verified.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="gate">
      <div className="gate-card">
        <div className="gate-brand">
          <span className="nav-logo" aria-hidden="true">
            RP
          </span>
          <h1>
            Repo<span style={{ color: "var(--signal-success)" }}>Pilot</span>
          </h1>
        </div>

        <div className="gate-probe">
          <span className={`status-dot ${probe}`} />
          <span>
            {probe === "up"
              ? "Backend reachable."
              : probe === "checking"
                ? "Looking for the backend…"
                : "Backend not reachable on this origin."}
          </span>
          <div className="rp-spacer" />
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => void check()}>
            <Icon name="refresh" size={14} />
            Recheck
          </button>
        </div>

        <form className="stack-3" onSubmit={submit}>
          <div className="field">
            <label htmlFor="gate-token">API token</label>
            <input
              id="gate-token"
              className="input rp-mono"
              type="password"
              value={value}
              autoFocus
              autoComplete="off"
              spellCheck={false}
              placeholder="Bearer token"
              onChange={(event) => setValue(event.target.value)}
            />
          </div>
          {error !== null ? <p className="gate-error">{error}</p> : null}
          <button type="submit" className="btn btn-primary" disabled={busy || probe === "down"}>
            <Icon name="plug" size={15} />
            {busy ? "Verifying…" : "Connect"}
          </button>
        </form>

        <div className="gate-steps">
          {info !== null ? (
            <>
              <p>
                Auth mode: <code>{info.authMode}</code> — send <code>{info.header}</code>: <code>{info.scheme}</code>{" "}
                <code>&lt;token&gt;</code>.
              </p>
              <p>
                {info.tokenGenerated
                  ? "This instance generated a token on first run. It is written to: "
                  : "This instance reads the token from REPOPILOT_AUTH_TOKEN. Location hint: "}
                <code>{info.tokenLocationHint}</code>
              </p>
            </>
          ) : (
            <>
              <p>
                The token is read from <code>REPOPILOT_AUTH_TOKEN</code>, or generated on first run and written next
                to the data directory.
              </p>
              <p className="rp-faint">
                The token is kept in this tab&apos;s session storage only. It is never written to disk by the browser,
                never placed in a cookie, and never logged.
              </p>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
