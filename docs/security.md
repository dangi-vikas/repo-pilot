# RepoPilot — Security and Privacy

This document is the threat model and the inventory of controls. It is meant to be
readable on its own and to stay honest about what RepoPilot does *not* defend against.
Where a control has a precondition, that precondition is stated and, where possible,
enforced in code.

RepoPilot is a local-first, single-operator tool. It reads arbitrary directories the user
selects, serves their contents back over HTTP, and can send code to a model. Each of those
is a place where a mistake becomes a vulnerability, so each is treated deliberately.

---

## 1. Threat model

### Assets

1. **Source code of the analysed repositories.** Usually proprietary. The whole point of
   the local-first design is that this does not leave the machine.
2. **Credentials that may exist on the machine** (SSH keys, cloud configs, `.env` files).
   RepoPilot must not read them, log them, store them, or send them to a model.
3. **The RepoPilot API token**, which grants access to everything the API can do.
4. **The integrity of RepoPilot's own data directory** (database, artifacts, token file).

### Adversaries considered

- **A web page the user visits** (`https://evil.example`) issuing requests to
  `http://127.0.0.1:8080`. Browsers allow the request to be *sent*; the concern is whether
  it can *read* responses or *cause side effects*.
- **Another local process** running as the same user.
- **Malicious or untrusted repository content.** Analysing an untrusted repository means
  parsing attacker-controlled bytes and, later, placing them in a model prompt.

### Adversaries explicitly **not** defended against

- An attacker who already has read access to the user's filesystem as that user. They can
  read the source directly; RepoPilot's token adds nothing.
- A network attacker. The API is loopback-only by default.
- A malicious operating-system or JVM. Out of scope.

---

## 2. Trust boundaries

```
  UNTRUSTED                    │  RepoPilot (trusted)                    │  UNTRUSTED
   source code, file names,    │  sandbox, parser, analyzers, API        │  model output
   paths, model output         │                                          │
  ─────────────────────────────┼─────────────────────────────────────────┼──────────────
   repository filesystem ──────┤▶ sandbox ─▶ enumerator ─▶ parser        │
                               │                                          │
   HTTP client ────────────────┤▶ auth filter ─▶ controller ─▶ service    │
                               │                                          │
                               │  evidence builder ──────────────────────▶│▶ model
                               │  citation validator ◀────────────────────│◀ output
```

Two rules follow and are enforced in code:

- **Nothing crosses from the untrusted side into a privileged operation without passing a
  validating boundary** (the sandbox for paths, the auth filter for requests, the citation
  validator for model output).
- **Model output is data, never instructions.** It is validated and rendered, never
  executed and never trusted to name a file or line that the index has not confirmed.

---

## 3. Authentication and network exposure

See [ADR 0005](./adr/0005-auth-model.md) for the reasoning. The controls:

| Control | Detail |
| --- | --- |
| Bearer token required | Every `/api/v1/**` request needs `Authorization: Bearer <token>`. |
| Token secrecy | Never logged, never returned by any endpoint, never embedded in the served UI bundle, never written into a job parameter. |
| Constant-time comparison | `MessageDigest.isEqual` over SHA-256 digests; does not leak the token byte-by-byte or its length. |
| Token generation | `SecureRandom`, 32 bytes, URL-safe Base64, written to `<data-dir>/token.txt` with owner-only POSIX permissions where supported. |
| Minimum operator token | A supplied token shorter than 16 characters fails startup. RepoPilot refuses to run with a guessable token. |
| Loopback binding | `server.address` defaults to `127.0.0.1`. Binding elsewhere requires an explicit opt-in and an explicit token. |
| No cookies | Stateless; the security context repository is a no-op. The UI keeps the token in `sessionStorage` for the life of the tab. |
| CSRF disabled deliberately | Safe *because* there are no cookies and no ambient credentials. **Precondition, asserted in `SecurityConfiguration`: if a cookie-based context repository is ever introduced, CSRF must be enabled in the same change.** |
| Narrow CORS | Explicit origin allow-list, only the methods/headers the UI uses, `allowCredentials = false`. No wildcard. |
| Actuator exposure | Only `health` and `info` answer without a credential. `/actuator/metrics`, `/actuator/metrics/**` and `/actuator/prometheus` require the same operator authority the token grants, and the token is compared before they are served. Nothing in the health group discloses repository data. |

### Why the "no cookies" decision matters

CSRF is an attack that exploits the browser *automatically attaching* ambient
credentials. RepoPilot issues none, so a cross-site request carries no credential and is
rejected with `401` exactly like an anonymous request. Disabling CSRF is therefore not a
hole; it is the correct configuration for a stateless bearer API. The moment that premise
changes, the decision must be revisited — which is why it is asserted in code rather than
left to memory.

---

## 4. Repository sandbox

Every filesystem path that reaches a read is validated by `RepositorySandbox`, a single
audited component. Scattering `resolve()` calls across services guarantees a missed case.

| Rule | Rationale |
| --- | --- |
| **Allow-list roots** | A repository may only live under a configured workspace root. Nothing outside is readable. |
| **Real-path containment** | Validation runs on `toRealPath()` output (symlinks resolved). A symlink inside the workspace pointing at `/etc` resolves outside and is rejected. Checking the un-resolved path is the classic bypass. |
| **Relative paths only for reads** | Absolute paths, drive-qualified paths, UNC paths, `..` segments, NUL bytes, and a set of special characters are rejected syntactically before any filesystem call. |
| **Re-validation on every read** | The recorded canonical root is re-checked per request, so a root later replaced by a symlink cannot widen access for an already-registered repository. This defeats the "register a safe directory, then swap it" attack. |
| **Self-exclusion** | RepoPilot refuses to import its own data directory or any ancestor of it, so its own artifacts can never be indexed as source or re-served. |
| **Symlinks not followed during enumeration** | `repopilot.workspace.follow-symlinks` defaults to `false`; symlinks are an escape vector. |
| **Bounded reads** | Per-file (`max-file-size-bytes`), per-repository file count and total size caps are always in force. |

### Documented limitations

- **TOCTOU.** Containment is checked on canonical paths, which does not defend against a
  concurrent rename between validation and read, on a filesystem the attacker can already
  write to. An attacker with that access already has equivalent capability.
- **Windows semantics.** Junctions and reparse points are resolved by `toRealPath`;
  POSIX permission semantics are not modelled.
- **Case-insensitive filesystems.** Containment uses `Path.startsWith`, which is
  provider-aware; case-folding behaviour differs across platforms.

---

## 5. Keeping credentials out of RepoPilot

`FileExclusionPolicy` draws a hard line between "not worth analysing" and **"must not be
read"**. Secret-bearing files are identified *by name and location only* and are never
opened. This check runs before size and binary inspection, because it is a security
boundary rather than an optimisation.

Never-read inputs include:

- Credential directories: `.ssh`, `.aws`, `.azure`, `.gcloud`, `.gnupg`, `.kube`, `.docker`.
- Secret files: `.env*`, `.netrc`, `.htpasswd`, `.npmrc`, `.pypirc`, `.pgpass`,
  `id_rsa`/`id_ed25519`/…, `credentials*`, `terraform.tfstate*`, `secrets.{yml,yaml,json}`,
  `keystore*`, `truststore*`.
- Secret extensions: `pem`, `key`, `p12`, `pfx`, `jks`, `keystore`, `truststore`, `ppk`,
  `asc`, `gpg`, `kdbx`, `ovpn`, `der`, `crt`, `cer`, `jceks`, `bcfks`.
- Name patterns matching `secret|credential|password|apikey|access-key|private-key|service-account`.

### Known limitations

- **False negatives are possible.** A secret committed inside a `.java` file, or in a
  credential store with a non-standard name, will be read like any other source file.
  This is why the next section exists.

### The exclusion is recorded, not silent

A security control that cannot be observed cannot be audited. Every refusal is counted and
persisted with the revision it belongs to, so an operator can confirm the boundary ran
rather than assume it did:

```jsonc
{
  "enumeratedFiles": 3008,     // files the walker inspected
  "excludedFiles": 5,          // inspected and deliberately not read
  "excludedByReason": "SECRET_BEARING=3,UNSUPPORTED_LANGUAGE=2"
}
```

`SECRET_BEARING=3` on a scan is the evidence that three credential files were recognised and
skipped by name, before any read. This was not always true: the exclusion counts used to be
derived from database rows, and since an excluded file never becomes a row, a scan that
skipped three credential files reported `excludedFiles: 0`. The control was working while its
report said nothing had been excluded — which is exactly the kind of silent gap that makes
the guarantee below unverifiable. The counts now come from the scan manifest
(`V2__revision_scan_accounting.sql`) and are asserted by integration tests.

---

## 6. Prompt injection and untrusted content in prompts

An analysed repository is untrusted input. A file can contain text crafted to make a model
misbehave ("ignore previous instructions and …"). Controls:

1. **Source content is data, never instructions.** It is inserted into a clearly delimited
   evidence block with an explicit system instruction that evidence is untrusted data to
   be described, not commands to be followed.
2. **Structural delimiting.** Retrieved evidence is wrapped in delimiters and labelled with
   its origin (path, symbol, lines) so it cannot be confused with the model's instructions.
3. **Bounded prompt budget.** A context character budget caps how much untrusted text can
   enter a prompt at once, limiting the room an injection has to work in.
4. **Output is not trusted to name locations.** The model may cite a file and line; the
   **backend validates every citation** against the index (the file exists in this
   revision; the line is in range) before it is stored or shown. A fabricated location is
   dropped, not displayed.
5. **No tool execution.** Model output never triggers a shell command, a build, a file
   write, or an API call. A `recommendation` is a *proposed* patch that a human reviews;
   `applied` defaults to `false`.
6. **Redaction before persistence.** Detected secrets are redacted from analyzer output
   before it is stored or sent to a model (`repopilot.privacy.redact-detected-secrets`).

A prompt injection can therefore at worst produce a misleading *hypothesis*, which is
labelled as such and is not actionable on its own.

### Implementation status

Controls 1–6 are the design of the investigation path, and **items 4 and 6 are not yet
exercised**: no code assembles an investigation prompt or validates a citation, because the
investigation flow is not implemented. They are stated here as the rules the implementation
must satisfy, not as controls this snapshot enforces. What *is* enforced today is the read
boundary (§4, §5) and the claim boundary: nothing in the API renders a model's assertion as
an observation, because nothing asks a model anything yet.

---

## 7. Data leaving the machine

By default, none. Ollama runs on loopback. The only path by which source could leave the
machine is the optional OpenAI-compatible provider, which is:

- **disabled by default** (`repopilot.ai.openai-compatible.enabled=false`), and
- **disabled unless fully configured** (base URL, API key, model).

Enabling it is an explicit, deliberate act by the operator. Logging of source content and
prompts is off by default (`repopilot.privacy.log-source-content=false`,
`log-prompts=false`) so code does not end up in logs read by log shippers.

---

## 8. Logging and error responses

- Secrets (tokens, API keys, redacted values) are never logged.
- Job error messages are truncated and normalised; they do not contain source.
- The API returns a stable error envelope (`errorCode`, `message`, `status`, `timestamp`,
  `correlationId`, `path`) and never a stack trace or internal identifier to a client.
- Filesystem paths in errors are redacted to a `~`-relative form, and the sandbox root
  allow-list is never disclosed by the API.
- A correlation id ties a user-visible error to server logs without exposing internals.

---

## 9. Artefacts and retention

- Uploaded runtime artefacts (JFR recordings, heap/thread dumps, benchmark files) are
  stored by reference under the data directory and are capped in size
  (`repopilot.analysis.max-artifact-size-bytes`).
- Artefacts are content-hashed so the same upload is not duplicated and so a run's
  inputs are identifiable.
- A retention task prunes artefacts and runs older than `repopilot.storage.retention-days`
  (default 30; 0 disables pruning).
- Deleting a repository cascades deletion of everything derived from it, at the database
  level and on disk.

---

## 10. Summary of guarantees and non-guarantees

**Guaranteed**

- The API is authenticated (bearer) and loopback-bound by default.
- No API path reads outside the configured workspace roots, and symlink escapes are
  rejected.
- Credential files are never opened.
- Every credential-file exclusion is counted and reported per revision, so the boundary's
  operation is auditable rather than assumed.
- Model output never executes and never names an unvalidated location.
- No source leaves the machine unless the operator explicitly enables a cloud provider.

**Not guaranteed**

- Protection against an attacker who already has same-user filesystem access.
- Detection of every secret (name-based heuristics have false negatives; content
  redaction is a mitigation, not a proof).
- Multi-user isolation; RepoPilot is a single-operator tool.
- Resistance to a compromised JVM or operating system.
- **The AI-related guarantees are design rules, not live behaviour.** The investigation
  flow, static analysis and runtime-artefact analysis are not implemented; their controls
  (prompt delimiting, citation validation, redaction before persistence) are described here
  as requirements for the implementation. The read boundary and the authentication boundary
  *are* enforced and tested.

## Reporting a vulnerability

Open a private security advisory on the project's GitHub repository, or file an issue using
the bug-report template if the report contains no exploit detail. Please include the
RepoPilot version, the profile in use (`local`/`postgres`), and a minimal reproduction.
