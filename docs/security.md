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

### What analysis does with the source it is allowed to read

The name-based exclusion decides what RepoPilot *reads*; the analyzers decide what RepoPilot
*repeats*. Three rules keep the second half as narrow as the first:

- **A rule that finds a credential never stores the value.** `RP005` records the location and
  the *name* of the field or variable, and its own message says the value is deliberately not
  recorded. The smoke test asserts that the fixture's fake secret appears nowhere in the raw
  findings JSON while the field name it was assigned to does — the check that stops a secret
  scanner from becoming a second copy of the secret it found.
- **PMD is handed content, not paths.** The engine's file collector is never used: text goes in
  through `TextFile.forCharSeq(content, FileId.fromPathLikeString(path), version)`. PMD cannot
  open a file RepoPilot did not open, cannot follow a symlink, and cannot reach a file the
  scanner excluded, because it is never given a handle to one.
- **No classpath, no execution.** PMD runs single-threaded, in process, with no classpath and
  with its incremental cache disabled. Analysis loads Java source as text; it never compiles,
  loads or executes repository code.

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
   **backend validates every citation** against the index and the filesystem (the key was in
   the prompt; the cited excerpt still hashes the same; any `path:line` the claim names is
   covered by its own citation) before the answer is stored or shown. A claim that fails is
   shown with an `UNSUPPORTED` verdict and makes the answer ungrounded; it is never displayed
   as if the evidence supported it.
5. **No tool execution.** Model output never triggers a shell command, a build, a file
   write, or an API call. Nothing a model says is applied anywhere: the `recommendation` table
   exists for the next phase and is not yet written to.
6. **Redaction before persistence.** Detected secrets are redacted from analyzer output
   before it is stored or sent to a model (`repopilot.privacy.redact-detected-secrets`).

A prompt injection can therefore at worst produce a misleading *hypothesis*, which is
labelled as such and is not actionable on its own.

### Implementation status

Controls 1–5 are implemented and exercised. The sixth is a property of the analysis path rather
than a filter on prompt text, and the two qualifications below say what that means:

- **The citation check is stronger than "the location exists".** A claim's key must have been in
  the prompt; the cited excerpt is re-read from disk, through the sandbox and the recorded
  revision, and its SHA-256 must match the one stored when it was sent
  (`CONFIRMED` / `CHANGED` / `MISSING` / `NOT_LOCATED`); and any `path:line` the claim names in
  its own sentence must be covered by the evidence it cites. A claim that cites nothing, cites a
  key that was never sent, or names a location outside its citation is `UNSUPPORTED`, and one
  failed claim makes the whole answer *ungrounded*.
- **A grounded answer is still a model's answer.** An injection inside a source file can steer
  what the model says, and the check cannot detect a plausible sentence built from real
  evidence. What it does prevent is the failure that matters most in this feature: an answer
  that invents a location, quotes a key it was never given, or cites evidence for a claim that
  evidence does not support is shown as unsupported, and the whole answer is marked ungrounded.
  The UI labels the answer as a hypothesis, lists what the investigation could not answer, and
  never renders a claim as an observation.

Redaction before persistence (control 6) protects stored analyzer output; it is not applied to
prompt text. An investigation sends the excerpts it retrieved, so a secret written in a
non-credential file could reach a *local* model. This build has no non-local provider; see §7.

The fence around an excerpt (` ``` `) is a formatting convention, not a security boundary: a file
that contains a fence can end the quoting early. What that buys an attacker is influence over the
*wording* of an answer, not over its citations — every claim still has to point at a key that was
sent and at a line range that still hashes the same. That is why the check exists and why the
answer is never rendered as an observation.

---

## 7. Data leaving the machine

By default, none. Ollama runs on loopback. That was exercised with a real model: Ollama 0.40.7 on
`127.0.0.1:11434` answered an investigation, so the prompt and its excerpts reached a process on this
machine and no network peer. This build contains only the Ollama and deterministic adapters.
The `repopilot.ai.openai-compatible.*` properties and `REPOPILOT_AI_OPENAI_*` variables are
reserved for a future adapter; they do not construct a provider, so no cloud path is active in
this build. The Ollama base URL is configurable: pointing it at a non-loopback host would send
the same excerpts there, so keep it on loopback for the local-only posture.

Logging of source content and prompts is off by default
(`repopilot.privacy.log-source-content=false`, `log-prompts=false`) so code does not end up in
logs read by log shippers.

An investigation sends the prompt — the question, the numbered evidence and at most 24 lines
of any one excerpt — to whichever provider is configured, and to nothing else. What is stored
is the prompt's SHA-256 and length, and per cited item the SHA-256 of the exact excerpt sent;
the prompt and the excerpt text are not persisted, so the database is not a copy of the source.
The deterministic extractor is not a fallback that could silently receive code: it is selected
only when the operator sets `repopilot.ai.provider=deterministic`, and it states in every
answer that no model was called.

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

Uploaded runtime artefacts (JFR recordings, heap/thread dumps, benchmark files) are written under
`<data-dir>/artifacts` and are **never executed** and never read by anything except the reader
that claims to understand them.

**Where the bytes go.** A stored artefact lives at
`<data-dir>/artifacts/<repositoryId>/<hash[0..2]>/<sha256>.<extension>`. The path is derived from
the repository id and the content hash, so nothing about it comes from the client. The filename the
client sent is stored as a **display label only**: `../../etc/passwd.jfr` is recorded as
`passwd.jfr` and stored under its hash. Uploading the same bytes twice keeps the first artefact —
identity is the content, not the name.

**Size is bounded in two places, deliberately.**

| Limit | Default | Enforced | What it is for |
| --- | --- | --- | --- |
| `spring.webflux.multipart.max-disk-usage-per-part` (`REPOPILOT_MAX_PART_DISK_BYTES`) | 2 GiB | While the part is being written | Stops an oversized stream before it can fill the disk |
| `repopilot.analysis.max-artifact-size-bytes` | 1 GiB | On the landed file | The published limit; refusal names the exact size and the exact limit |

The framework limit is set above the application limit on purpose: a file between the two is read
and then refused with a message that names both numbers, while a file past both is cut off
mid-stream. The two must be raised together. A refusal is `413 LIMIT_EXCEEDED`, nothing is stored,
and the scratch file is deleted.

**Reading is header-checked first.** A JFR recording starts with the bytes `FLR\0`; a gzip stream
starts with `1F 8B`. The header is checked before any `jdk.jfr` object is created, because the JDK's
`RecordingFile` leaks a file handle when it fails to parse (see `docs/adr/0008-runtime-evidence.md`).
A file that fails the check is refused by name, and a compressed recording is told to be
decompressed rather than being called "not a recording".

**A thread dump is read, and it is not process memory.** The plain-text dump written by
`jcmd <pid> Thread.print`, `jstack <pid>` or `kill -3 <pid>` is parsed in process, and the two
`Thread.dump_to_file` formats are refused by name at upload because they carry no thread states, no
lock ownership and no deadlock section. Two consequences are worth stating as guarantees:

- `containsProcessMemory` is `false` for a thread dump and `true` only for a heap dump. A dump names
  threads, classes and frames — it describes your code — but it is not a copy of the heap, and the
  upload warning for the two is different because the risk is.
- **The stacks are not copied anywhere.** They stay in the uploaded file, which is stored by hash and
  never executed. The provenance written to the database holds the JVM name and version, the dump's
  own timestamp (as text: the JVM records local time with no zone), and counts. Thread names are
  stored only in a deadlock cycle's own measurement, where naming the deadlocked threads is the entire
  point of the record.

**A heap dump is process memory, and it is treated as such.** `containsProcessMemory` is `true` only
for a heap dump, and the upload screen warns that the file is the literal contents of the process's
heap, including any string or credential that was live in it. What that means for storage:

- **Only the census is stored.** The reader walks the file in two streaming passes and writes counts
  and byte totals. Object field values are read to advance and are never retained, copied or logged.
- **The one piece of application data that reaches the database is a type name** — and only on the
  measurement row derived from it (`heapdump.types.bytes`, `scope = the type`), which is the whole
  point of that row. The provenance holds the header (format version, identifier width), the file size,
  the string count, the census and the fact that no retained size was computed; the smoke test asserts
  that no type name appears in it.
- **Object identifiers, field values and reference edges are not stored at all**, so a heap dump cannot
  be reconstructed — not even partially — from RepoPilot's database.
- **A compressed dump is refused rather than expanded.** A small gzip file can expand without bound, so
  the refusal names `gzip -d` instead of handing a worker an unbounded amount of work.
- **A class histogram is refused rather than read.** `jmap -histo` output is the file an operator most
  often mistakes for a dump; it is a JVM-generated summary with no objects behind it, and the refusal
  names the command that writes a real dump.
- **Deleting the repository deletes the bytes**, as above — and this is the case where that matters
  most.

**The encoding is read from the file, not assumed.** A dump captured with a shell redirect on Windows
is UTF-16LE with a byte-order mark, and RepoPilot honours the mark and strips it. Decoding such a file
as UTF-8 does not fail loudly — it interleaves NUL bytes through every line — so without this the
reader would refuse a valid dump as "not a thread dump", a refusal of evidence caused by the
operator's terminal rather than by anything in the file. A UTF-16 file *without* a mark is refused as
unknown rather than guessed at.

**Deleting a repository deletes its artefacts.** The records are removed in a transaction; the bytes
are removed after it commits, because filesystem work must not run inside a database transaction
(ADR 0006). A startup sweep then removes any artefact directory whose repository id is not in the
database, which covers a deletion that was interrupted between the two steps. This matters because a
heap dump contains process memory: "deleted" has to mean deleted, not "no longer listed".

**A benchmark result carries a command line, and the command line is not stored.** JMH writes the
JVM's `jvmArgs`, its own parameters and the path to the JVM binary into every result file. `-D` is
where a token, a connection string or a keystore password ends up, so the reader keeps only the
*count* of arguments and never touches the binary path; the provenance records the count and the
reader's own explanation. Declared `params` are stored, because they identify the measurement. The
figures themselves are quoted `AS_REPORTED` with the harness's unit, and no threshold or severity is
attached to any of them — the file is a statement by someone else's experiment, not an observation of
this process.

**Retention removes the file and keeps the evidence.** A pass deletes the stored bytes of an artefact
older than the configured window and marks its row `purged_at`; the artefact row, the analysis run and
every measurement stay. That is a deliberate asymmetry: the measurements are what the operator keeps,
and the file is the bulk. The properties that make it safe:

- **No unset value deletes anything.** `repopilot.storage.retention-days = 0` means pruning is off:
  the scheduler queues nothing and the plan names no candidate. `POST /runtime/retention/purge` with
  `olderThanDays` absent or ≤ 0 is a `400` whose message says it will not treat a missing window as
  "everything". The service refuses as well, so a job submitted below the HTTP layer cannot inherit
  the omission.
- **The plan is a live read.** It recomputes candidates against the clock on every request, with the
  bytes each would reclaim and the measurements that would survive. There is no stored plan to go
  stale and no "dry run" flag on the job.
- **Deletion order is chosen for failure.** Files are deleted first, then rows marked: a crash between
  the two leaves a row whose file is missing (visible, recoverable by re-upload), never bytes with no
  owner.
- **Only the window can delete.** There is no per-artefact delete endpoint, no age-based deletion of
  runs, and no path that removes measurements. Repository deletion remains the way to remove
  everything, and it still removes bytes after the transaction commits.
- **The scheduler queues rather than deletes.** One hourly pass (initial delay 120 s), a per-day
  idempotency key, a per-repository `try`/`catch`, and the window written into the job's parameters —
  so the pass records the policy it ran under even if the configuration changes afterwards.
- **Re-uploading the same bytes restores the artefact**, because identity is the content hash and the
  unique key is `(repositoryId, contentHash)`. Analysing a purged artefact before that is a `409`, not
  a job that fails on a missing file.

A startup orphan sweep is still the last line of defence: any artefact directory whose repository is
not in the database is removed, which covers a deletion interrupted between its two steps.

**Database deletion cascades; artefact bytes do not, except as described above.** Deleting a
workspace removes its repositories' rows at the database level; their artefact directories are
removed the next time RepoPilot starts, by the same orphan sweep. Deleting a repository removes
both immediately.


---

## 10. Summary of guarantees and non-guarantees

**Guaranteed**

- The API is authenticated (bearer) and loopback-bound by default.
- No API path reads outside the configured workspace roots, and symlink escapes are
  rejected.
- Credential files are never opened.
- Every credential-file exclusion is counted and reported per revision, so the boundary's
  operation is auditable rather than assumed.
- No static-analysis finding stores the value of a credential it reports.
- Every stored static finding's cited location was checked against the text that was analysed
  before it was written.
- Model output never executes, and a model's claim never renders as an observation: the prose
  is labelled a hypothesis and each sentence carries the verdict of a citation check that
  re-read the excerpt it cited.
- Every excerpt sent to a model is hashed and never stored: the database keeps the hash, so a
  citation can be re-checked without turning the schema into a prompt log.
- By default, no source leaves the machine: Ollama points at loopback, and this build has no separate cloud adapter.
- A recording never leaves the machine: it is read in process, under content-addressed storage
  that no client-supplied name can influence.
- A thread dump is read in process too, and only the counts and the JVM's own deadlock verdict are
  stored: the stack frames stay in the artefact, and thread names appear only where a deadlock
  cycle named them. A dump captured through a Windows shell (UTF-16 with a byte-order mark) is read
  rather than refused.
- A benchmark result is parsed in process and only its quoted figures are stored: the JVM's
  arguments are counted and discarded, and the path to the JVM binary is never read.
- A retention pass removes stored bytes and never a measurement, never a run, and never anything
  outside the window it was given; a window of zero removes nothing at all.
- Every upload is bounded twice, and a refusal stores nothing and leaves no scratch file.
- Deleting a repository removes its stored artefacts, not only its records.

**Not guaranteed**

- Protection against an attacker who already has same-user filesystem access.
- Detection of every secret (name-based heuristics have false negatives; content
  redaction is a mitigation, not a proof).
- Multi-user isolation; RepoPilot is a single-operator tool.
- Resistance to a compromised JVM or operating system.
- **An investigation's controls are enforced, but its answers are not facts.** Prompt delimiting,
  citation validation and the "model output is never an observation" rule are implemented and
  asserted by tests; the read boundary, the credential handling of `RP005`, the in-process engine
  with no classpath, and the citation check on every stored finding remain enforced as before.
- **An investigation is only as good as its evidence.** Selection is deterministic name and
  text matching, so a question that describes a subsystem without naming anything in it may
  retrieve little, and the answer is then thin rather than wrong — the diagnostics say what was
  retrieved and what was not. A grounded answer means the citations held up; it does not mean
  the model's reasoning did, and an injected instruction inside a file can still shape what the
  model says about real evidence.
- **A small model can follow the shape and not the intent.** The prompt now describes the JSON
  types rather than showing copyable prose, because Qwen2.5-Coder 3B once returned the schema's
  example strings as its answer. Wording reduces that; nothing in the code can reject a
  well-formed, correctly-cited answer that is merely unhelpful.
- **Analysis reads source as text and repeats what it finds.** A secret written in a file whose
  name is not credential-like will be read, and a rule may report its location (never its
  value). Redaction is a mitigation, not a proof.
- **Retention is a policy about bytes, and only about bytes.** A purge removes a stored file once it is
  past the window; it does not remove the row, the run or the measurements, and it does not overwrite
  the blocks. There is no per-artefact delete, no pinning and no per-type window — and a window of
  zero means nothing is pruned, which is a deliberate choice against a silent default.
- **An uploaded dump is stored, not sandboxed.** A heap dump is process memory in a file. RepoPilot
  does not read the ones it cannot parse, but it does write them to disk under the data directory,
  where they are protected by the filesystem's own permissions and nothing else. A thread dump is
  not process memory — it names threads and frames — but it is still a file on that disk, and it is
  stored the same way. Retention removes such a file once it is past the window, and the bytes are
  gone from the disk at that point — but until then, and for any file inside the window, the
  filesystem is the only control.
- **Retention is not secure erasure.** Deleting a file does not overwrite the blocks it occupied, so
  a heap dump removed by a purge may remain recoverable by someone with raw access to the disk. On
  an encrypted volume that is a much smaller problem; on a shared one it is not.

## Reporting a vulnerability

Open a private security advisory on the project's GitHub repository, or file an issue using
the bug-report template if the report contains no exploit detail. Please include the
RepoPilot version, the profile in use (`local`/`postgres`), and a minimal reproduction.
