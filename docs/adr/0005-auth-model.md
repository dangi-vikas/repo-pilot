# ADR 0005 — Authentication model: single-operator bearer token

- **Status:** Accepted
- **Date:** 2026-10-17
- **Deciders:** RepoPilot maintainers

## Context

RepoPilot is a local-first tool. In its default deployment it runs on one developer's
machine, bound to loopback, and is used by exactly one person. It nevertheless exposes
powerful operations: reading repository source, spawning analysis jobs, and (later)
invoking a model over that source.

That creates two opposing pressures:

- **Requiring an account system** (users, passwords, sessions, password reset, roles)
  would be security theatre for a tool whose realistic threat model is "another process or
  a stray browser tab on the same machine", and it would hurt the local-first goal.
- **Requiring nothing at all** would mean any local process, any script, and any web page
  that the user's browser visits could reach the API. A malicious web page can issue
  requests to `http://127.0.0.1:8080`; it cannot read responses without CORS, but it can
  still trigger side effects unless the API demands a credential a browser will not
  attach ambiently.

The threat model in full is in `docs/security.md`. In brief: protect against other local
processes and cross-origin web pages; do not attempt to defend a single-user desktop
against a local attacker who already has the user's filesystem.

## Decision

Use **stateless bearer authentication with a single operator token**.

1. **One token, obtained one of two ways.** Either supplied by the operator
   (`REPOPILOT_AUTH_TOKEN` / `repopilot.security.token`, minimum 16 characters, rejected
   at startup if shorter) or generated with `SecureRandom` (32 bytes, URL-safe Base64) and
   written to `<data-dir>/token.txt` with owner-only permissions where POSIX permissions
   are available.
2. **The token is a capability and is treated as a secret.** It is never logged, never
   returned by any endpoint, never embedded in the served UI bundle, and never written to
   a job parameter. Comparison is constant-time
   (`MessageDigest.isEqual` over SHA-256 digests) so a comparison cannot leak the token
   byte-by-byte or its length.
3. **The UI stores it in `sessionStorage` only**, for the life of the tab. It is never
   persisted to `localStorage` or a cookie, so closing the tab ends the session and there
   is no ambient credential for a cross-site request to abuse.
4. **Every `/api/v1/**` request requires it**; only liveness/readiness, the auth bootstrap
   description, OpenAPI docs, and static UI assets are public. The bootstrap endpoint
   states *where* the token can be found and contains no secret.
5. **Loopback binding by default.** `server.address` defaults to `127.0.0.1`, so the API
   is not reachable from the network unless the operator changes it deliberately.
6. **CSRF protection is deliberately disabled — with a precondition.** RepoPilot issues no
   cookies and the security context repository is a no-op (stateless), so a cross-site
   request carries no credentials and is rejected exactly like an anonymous one.
   `SecurityConfiguration` asserts this precondition programmatically: if a cookie-based
   context repository is ever introduced, CSRF must be enabled in the same change.
7. **CORS is a narrow allow-list** (configured dev-server origins, the methods and headers
   the UI uses, `allowCredentials = false`), not a wildcard.

## Consequences

**Positive**

- No account system to build, secure, or explain; the tool stays a tool.
- A cross-origin web page cannot read API responses (no CORS grant, no credentials) and
  cannot trigger authenticated side effects (no ambient credential, bearer required).
- The token handoff is auditable in one place (`AuthTokenService`), and the value never
  appears in logs or responses.

**Negative / risks**

- A local process running as the same user can read `token.txt` and call the API. This is
  accepted: that process can read the source code directly anyway. The token defends
  against *other* processes learning nothing new about *this* app's privileged surface; it
  is not a boundary against a same-user attacker.
- If a user sets `server.address=0.0.0.0` to analyse remotely, a single shared token is a
  weak multi-user story. Multi-user is explicitly out of scope; the security docs say so.
- No token rotation or revocation beyond editing the config/restarting.

## Alternatives considered

- **No authentication.** Rejected: leaves a side-effecting local API open to any local
  process and to drive-by requests from web pages.
- **Full account system (sessions, passwords).** Rejected: disproportionate for a
  single-operator local tool, and cookie sessions would reintroduce the CSRF surface this
  design avoids.
- **Mutual TLS.** Rejected: certificate management is a worse first-run experience than
  pasting one token, with no benefit at this threat level.

## Revisit when

- RepoPilot is deployed for more than one user, or on a non-loopback interface by default.
- Cookie-based sessions are introduced (CSRF must then be enabled — enforced in code).
