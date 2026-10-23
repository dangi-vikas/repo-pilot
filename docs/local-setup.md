# Local setup

RepoPilot is designed to run entirely on your machine. This guide covers the
source-based setup, the optional Docker Compose stack, the optional local model, and
the problems that are most likely to block a first run.

The short version:

- **Source run:** JDK 21, the bundled Maven wrapper, Node 20+ and npm. No Docker,
  PostgreSQL or Ollama is required.
- **Database:** the default `local` Spring profile uses an embedded H2 file database.
- **AI:** optional. Indexing, static analysis, the Code Universe, runtime readers and
  findings work without a model.
- **Network:** the backend binds `127.0.0.1:8080`; the Vite dev server runs at
  `http://localhost:5173` and proxies `/api` and `/actuator` to the backend.

---

## What runs

| Process | Address | Purpose |
| --- | --- | --- |
| Spring Boot backend | `http://127.0.0.1:8080` | API, indexing jobs, analysis, H2 database |
| Vite dev server | `http://localhost:5173` | React UI and development proxy |
| Ollama (optional) | `http://127.0.0.1:11434` | Local language model for AI Investigations |

The browser only talks to the Vite origin in development, so there is no CORS setup
to do. The backend still requires a bearer token.

---

## Prerequisites

| Tool | Version | Check |
| --- | --- | --- |
| JDK | 21 | `java -version` |
| Maven | Bundled `mvnw` / `mvnw.cmd` | `cd backend` then `./mvnw -v` (Windows: `.\mvnw.cmd -v`) |
| Node.js | 20 or newer | `node --version` |
| npm | Bundled with Node | `npm --version` |
| Ollama | Optional | `ollama --version` |
| Docker | Optional, for Compose only | `docker --version` and `docker compose version` |

Ports `8080` and `5173` must be free. The Docker path additionally uses port `3000`.

On macOS/Linux, if `./mvnw` is not executable (the repository stores it without the
executable bit on some checkouts), run `chmod +x mvnw` once from `backend/`, or invoke
`bash mvnw ...` instead.

On Windows, if PowerShell refuses to run `npm.ps1`, use `npm.cmd` instead, or allow
scripts for the current terminal only:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

---

## Option A — run from source (recommended)

This is the path that has been exercised in the repository's development environment.
It needs no external services.

### 1. Open a terminal at the repository root

All commands below assume the repository root is the current directory. The backend
and frontend run in separate terminals.

### 2. Choose a workspace root

`REPOPILOT_WORKSPACE_ROOTS` is the allow-list of directories RepoPilot may read. A
repository you register must resolve inside one of those roots; RepoPilot never reads
outside them. The value can be one directory or a comma-separated list.

For the bundled sample, pointing the root at this repository is enough:

**PowerShell**

```powershell
$env:REPOPILOT_WORKSPACE_ROOTS = "C:\Users\you\Projects\repo-pilot"
```

**macOS / Linux**

```bash
export REPOPILOT_WORKSPACE_ROOTS="$HOME/Projects/repo-pilot"
```

For your own work, point it at the directory that contains your repositories:

```powershell
$env:REPOPILOT_WORKSPACE_ROOTS = "C:\Users\you\Projects"
```

```bash
export REPOPILOT_WORKSPACE_ROOTS="$HOME/Projects"
```

Then register a repository by its relative name (`my-service`) or by its absolute
path, provided the path is under that root.

If you leave the variable unset, RepoPilot creates and uses
`~/repo-pilot-workspace` (or `%USERPROFILE%\repo-pilot-workspace` on Windows). Put the
repository you want to index inside that directory, or set the variable explicitly.

> Environment variables set this way live only in the current terminal. Start the
> backend from the same terminal, or put the values in your IDE run configuration.
> Spring Boot does not read `.env` files automatically.

### 3. Choose a token

The backend requires a bearer token of at least 16 characters. Supplying one
explicitly is recommended for local development because it stays stable across
restarts. Use a random value rather than the literal example below.

**PowerShell**

```powershell
$env:REPOPILOT_AUTH_TOKEN = [guid]::NewGuid().ToString()
```

**macOS / Linux**

```bash
export REPOPILOT_AUTH_TOKEN="$(openssl rand -hex 24)"
```

Alternatively, leave it unset. The backend then generates a fresh token on **every**
start and writes it to `<data-dir>/token.txt`. With the commands below, that is
`backend/data/token.txt`; read it from the `backend` directory:

```powershell
Get-Content .\data\token.txt
```

```bash
cat data/token.txt
```

A generated token changes on the next restart, so a browser tab holding the old token
will be rejected. Use an explicit token if you expect to restart often.

### 4. Start the backend

**PowerShell**

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

**macOS / Linux**

```bash
cd backend
./mvnw spring-boot:run
```

The first run downloads Maven dependencies and takes longer. Wait for
`Started RepoPilotApplication` in the log. The backend is then at
`http://127.0.0.1:8080`.

Useful checks:

- Health: <http://127.0.0.1:8080/actuator/health>
- OpenAPI document: <http://127.0.0.1:8080/api/docs>
- Swagger UI: <http://127.0.0.1:8080/api/docs/ui>

### 5. Start the frontend

Open a second terminal at the repository root:

**PowerShell**

```powershell
cd frontend
npm.cmd ci
npm.cmd run dev
```

**macOS / Linux**

```bash
cd frontend
npm ci
npm run dev
```

`npm ci` installs exactly the versions in `package-lock.json`. If you intentionally
need to update dependencies, use `npm install` instead. The dev server runs at
<http://localhost:5173>.

The Vite proxy target is `http://127.0.0.1:8080`. If you change the backend port,
update `frontend/vite.config.ts` as well.

### 6. Connect and index the sample repository

1. Open <http://localhost:5173>.
2. The connection gate checks that the backend is reachable. Paste the token you set,
   or the contents of `backend/data/token.txt`, and choose **Connect**.
3. RepoPilot creates a **Default workspace** on first start. Use it, or create another
   workspace from the workspace menu.
4. Choose **Register repository**.
5. Enter one of these paths:
   - `examples/sample-java` if the workspace root is this repository;
   - `sample-java` if you placed the sample in the default
     `~/repo-pilot-workspace` directory;
   - an absolute path inside the configured root.
6. Leave the display name blank to derive it from the directory, then choose
   **Register**.
7. Select the repository in the top bar and press **Index**. The sample is small and
   should finish quickly.
8. Open **Code Universe** or **Code Explorer** to inspect the indexed revision. The
   Universe is deliberately bounded and states the slice it shows.

The `fixtures/quality-demo` directory can be registered the same way when you want to
see the built-in static-analysis rules fire.

### 7. Optional: AI Investigations

AI is not required for indexing, the Code Universe, findings, runtime readers or
memory/concurrency analysis. Without a model, those screens still work and the AI
areas report that the provider is unavailable.

To disable AI explicitly:

```powershell
$env:REPOPILOT_AI_ENABLED = "false"
```

```bash
export REPOPILOT_AI_ENABLED=false
```

To exercise the investigation flow without a model, select the deterministic
extractor:

```powershell
$env:REPOPILOT_AI_PROVIDER = "deterministic"
```

```bash
export REPOPILOT_AI_PROVIDER=deterministic
```

It quotes retrieved evidence verbatim, states that no model was called, and is not a
reasoning model.

To use a real local model:

1. Install [Ollama](https://ollama.com) and make sure it is running (`ollama serve`).
2. Pull the model that was verified on CPU-only hardware:

   ```bash
   ollama pull qwen2.5-coder:3b
   ```

3. Set the model and start the backend:

   ```powershell
   $env:REPOPILOT_AI_PROVIDER = "ollama"
   $env:REPOPILOT_AI_CHAT_MODEL = "qwen2.5-coder:3b"
   ```

   ```bash
   export REPOPILOT_AI_PROVIDER=ollama
   export REPOPILOT_AI_CHAT_MODEL=qwen2.5-coder:3b
   ```

The default `qwen2.5-coder:7b` was not run in this repository's verification
environment. On a CPU-only machine the 3B model took 54–79 seconds per investigation
against the 120-second default timeout. A 7B model is likely to need a GPU or a longer
`REPOPILOT_AI_REQUEST_TIMEOUT_SECONDS`.

> **Cloud AI is not implemented in this build.** The
> `REPOPILOT_AI_OPENAI_*` and `repopilot.ai.openai-compatible.*` settings are reserved
> configuration. Only the `ollama` and `deterministic` providers are constructed, so
> setting a cloud base URL and key does not add a provider.

---

## Option B — Docker Compose (optional)

The Compose stack runs PostgreSQL, the backend and an nginx-served frontend. It is
useful when you want PostgreSQL or a production-like topology without installing
PostgreSQL locally.

### Prerequisites

- Docker Desktop, or Docker Engine with the Compose v2 plugin.
- A free loopback port `3000` (frontend) and `8080` (backend API).

### 1. Create `.env` and set a token

**PowerShell**

```powershell
Copy-Item .env.example .env
```

**macOS / Linux**

```bash
cp .env.example .env
```

Open `.env` and set a strong value for `REPOPILOT_AUTH_TOKEN`:

```env
REPOPILOT_AUTH_TOKEN=local-dev-token-change-me
```

The token must be at least 16 characters; use a random value, not the literal example.
With PowerShell, `[guid]::NewGuid().ToString()` produces one; on macOS/Linux,
`openssl rand -hex 24` does.

The Compose file requires an explicit token because the backend binds `0.0.0.0` inside
its container; the startup security validator refuses a generated token on a non-loopback
bind. Compose also sets `REPOPILOT_SECURITY_ALLOW_REMOTE_BIND=true` for that container
bind. The published ports are still bound to loopback, so the stack is not reachable from
the network.

### 2. Choose what to mount as the workspace

The default Compose mount is the repository's `examples` directory:

```env
REPOPILOT_WORKSPACE_HOST_DIR=./examples
```

To analyse another directory, set it to the parent directory that contains your
repositories:

```env
REPOPILOT_WORKSPACE_HOST_DIR=C:/Users/you/Projects
```

The directory is mounted read-only at `/workspace`; RepoPilot only reads under
`REPOPILOT_WORKSPACE_ROOTS`, which Compose sets to `/workspace`.

### 3. Start the stack

```bash
docker compose up --build
```

Open <http://localhost:3000> and paste the same token. With the default mount,
register `sample-java`. With a custom mount, register the repository's directory name
relative to that mount, or its absolute container path.

The API is also published at <http://127.0.0.1:8080>; the UI at port `3000` proxies
`/api` to the backend container.

### 4. Stop and reset

```bash
docker compose down        # stop; named volumes and indexed data survive
docker compose down -v     # stop and delete PostgreSQL and RepoPilot data
```

`down -v` is destructive: it removes the database, indexed repositories and stored
artefacts.

### Docker and Ollama

Compose points the AI provider at `http://host.docker.internal:11434` by default, and
the file adds a `host-gateway` mapping for Linux. If Ollama on the host only listens on
loopback, the container may not be able to reach it. Options are:

- run Ollama with `OLLAMA_HOST=0.0.0.0` for the duration of the session and keep it
  behind a firewall;
- run Ollama in a container on the same Compose network and point
  `REPOPILOT_AI_BASE_URL` at it;
- leave AI disabled with `REPOPILOT_AI_ENABLED=false`.

Do not expose Ollama to an untrusted network.

> **Verification note.** The container files have not been executed in the environment
> where this repository was assembled: no Docker daemon was available. The Compose
> file, backend image and nginx configuration have been reviewed, but
> `docker compose up --build` itself is unverified. Report what you see if you run it.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `server.address=0.0.0.0 is not a loopback address, but repopilot.security.allow-remote-bind is false` | A container or process is binding a non-loopback address without the explicit opt-in. | For source runs, use `SERVER_ADDRESS=127.0.0.1`. For a container, set `REPOPILOT_SECURITY_ALLOW_REMOTE_BIND=true` **and** an explicit token, then keep the published port on loopback. |
| `Remote binding requires an operator-supplied token` | The backend generated a token while binding a non-loopback address. | Set `REPOPILOT_AUTH_TOKEN` to at least 16 characters before starting. |
| `REPOPILOT_AUTH_TOKEN must be at least 16 characters` | The configured token is too short. | Use a longer value. |
| The connection gate rejects the token after a restart | No token was configured, so a new one was generated. | Set an explicit `REPOPILOT_AUTH_TOKEN`, or read the new `backend/data/token.txt`. |
| `Configured workspace root does not exist` | `REPOPILOT_WORKSPACE_ROOTS` names a missing directory. | Create the directory or correct/remove the variable, then restart the backend. |
| `Repository paths must be inside a configured RepoPilot workspace root` | The path is outside the allow-list, or the backend was started before the variable was set. | Set `REPOPILOT_WORKSPACE_ROOTS` in the backend's environment and restart it; use a relative path under the primary root. |
| `The path overlaps the RepoPilot data directory` | You tried to register a directory that contains the data directory, such as the repository root when the H2 data lives under `backend/data`. | Register a subdirectory (for example `examples/sample-java`) or move `REPOPILOT_DATA_DIR` outside the repository. |
| Backend not reachable from the UI | The backend is not running, is on another port, or the Vite proxy target is wrong. | Check <http://127.0.0.1:8080/actuator/health>; start the backend; update `frontend/vite.config.ts` if the port changed. |
| `Port 5173 is already in use` | Another Vite instance is running, or `strictPort` cannot bind. | Stop the other process or change `server.port` in `frontend/vite.config.ts`. |
| `Port 8080 is already in use` | Another backend or process owns the port. | Stop it or set `SERVER_PORT` and update the Vite proxy target. |
| AI investigation says the provider is unavailable | Ollama is not running, the model is not pulled, or AI is disabled. | Start Ollama, pull the configured model, or use `REPOPILOT_AI_ENABLED=false`. The rest of the app still works. |
| H2 reports the database is locked | Two backend processes are using the same `REPOPILOT_DATA_DIR`. | Stop the duplicate process; use one backend per data directory. |
| Docker Compose exits before the backend starts | `.env` has no token, or the token is too short. | Set `REPOPILOT_AUTH_TOKEN` to at least 16 characters in `.env` and run `docker compose up --build` again. |

### Reset a local source run

Stop the backend first. With the default data directory, these paths are under
`backend/data`:

- `repopilot.mv.db` (and possibly `repopilot.trace.db`) — the H2 database, index and
  analysis records;
- `token.txt` — a generated token, if you did not set one;
- `artifacts/` — uploaded JFR recordings, dumps and benchmark files.

Deleting the database resets the index and all analysis. Deleting `artifacts/` removes
the stored bytes for runtime analyses. Do this only when you intend to lose that
state. `REPOPILOT_DATA_DIR` changes the location.

For Docker, `docker compose down -v` resets both the PostgreSQL volume and the
RepoPilot data volume.

---

## Security notes for local use

- Keep the backend on `127.0.0.1`. Do not set `SERVER_ADDRESS=0.0.0.0` for a source
  run just to reach it from another machine.
- The token is a capability, not a login. Do not paste it into screenshots, commit
  `.env`, or put it in frontend source.
- The Vite dev server and the Compose frontend are development conveniences. If you
  expose either beyond loopback, put TLS and a strong token in front and restrict the
  workspace roots to the smallest useful directory.
- AI is local by default. Only Ollama and the deterministic extractor are implemented;
  this build has no active cloud provider.
