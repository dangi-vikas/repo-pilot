# Benchmark corpus

A reproducible Java repository for measuring RepoPilot's indexing pipeline, plus the
fixtures that make a measurement trustworthy.

The corpus is **generated**, not committed: `scripts/generate-corpus.ps1` writes it from a
seed, so two machines produce byte-identical input and their numbers are comparable. See
[RESULTS.md](RESULTS.md) for the recorded measurements.

```powershell
# PowerShell blocks unsigned scripts by default; bypass it for this script only.
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\generate-corpus.ps1 `
    -Packages 100 -TypesPerPackage 6 -MethodsPerType 6

# The default destination is .\corpus\generated\jdk21-synthetic
```

## What the corpus contains

| Content | Count (default parameters) | Purpose |
| --- | --- | --- |
| Record types | 600 | Value carriers, each with a `String`/`BigDecimal`/`Instant` component list |
| Enums | 600 | 3–5 constants, drives enum-constant resolution |
| Persistence interfaces | 600 | `Optional`/`List` return types, generic boundaries |
| Service classes | 600 | 6 methods each, cross-type references, `@Service` |
| Controllers | 600 | `@RestController`, `@RequestMapping`, `@GetMapping`, `@PostMapping` |
| Shared interface | 1 | Referenced from every package through an explicit import |
| **Total Java files** | **3 002** | 3 001 generated types plus one deliberately broken file |

### The fixtures that are not Java

A corpus of well-formed Java alone cannot fail. Every scan would report
`failedFiles = 0` and `excludedFiles = 0`, so a scanner that silently dropped files would
look identical to one that did not. The generator therefore also writes:

| Fixture | Expected classification | What it catches |
| --- | --- | --- |
| `config/credentials.json` | excluded, `SECRET_BEARING` | A credential file being read. It must never reach the database or a prompt. |
| `config/id_rsa` | excluded, `SECRET_BEARING` | Same, by file name rather than by extension |
| `config/server.pem` | excluded, `SECRET_BEARING` | Same, by extension |
| `docs/architecture.md`, `docs/notes.txt` | excluded, `UNSUPPORTED_LANGUAGE` | Files not being counted at all, which is how "0 excluded" becomes a false statement |
| `src/main/resources/application.properties` | recorded, `RECORDED` | The configuration analyzer's input not being enumerated, and not being fed to the Java parser either |
| `src/main/java/com/example/problems/_SyntaxBreak.java` | indexed, `PARSED_WITH_PROBLEMS` | A broken file being reported as fully parsed |

## Reading a measurement

Two numbers have to be read together, and the API returns both:

```jsonc
{
  "totalFiles": 3003,          // rows: files that were read
  "parsedFiles": 3001,         // of those, parsed cleanly
  "problemFiles": 1,           // of those, parsed with recoverable problems
  "recordedFiles": 1,          // of those, recorded but not parsed at all
  "enumeratedFiles": 3008,     // files the walker inspected
  "excludedFiles": 5,          // inspected and deliberately not read
  "excludedByReason": "SECRET_BEARING=3,UNSUPPORTED_LANGUAGE=2",
  "prunedDirectories": 0       // directories never walked; their contents are unknown
}
```

`enumeratedFiles = totalFiles + excludedFiles`. The quote is safe only when both sides
agree: a report that says "3 003 files indexed" without mentioning the other five is a
half-truth about a scan, and the corpus exists partly to make that half-truth visible.

There is one further number to check before claiming "no parse failures": the
`_SyntaxBreak.java` fixture must appear as `problemFiles`, never as `parsedFiles`.

## Why a synthetic corpus

A real open-source repository would be a better sample of real code and a worse
measurement: file counts change on every commit, so a recorded number could never be
reproduced. The corpus trades realism for repeatability. RepoPilot is also analysed
against itself (`examples/sample-java`, and the repository in the parent directory) for
correctness, which is where real-world code shapes matter.
