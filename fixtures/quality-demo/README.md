# Static-analysis fixture

A deliberately defective Java project used to exercise RepoPilot's static analysis — in the
integration test, in the smoke test, and in the screenshots.

**Every problem in here is intentional.** This project exists so that a finding on screen can be
traced back to the exact line that produced it. Nothing in it is a real credential, and it must not
be copied into anything.

| File | Demonstrates |
| --- | --- |
| `OrderImporter.java` | RP001 empty catch, RP002 ignored exception, RP003 `printStackTrace`, RP004 console output, RP006 sleep in a loop, RP008 catching `Throwable` |
| `LegacyConfig.java` | RP005 credential in a literal (field and local), RP007 `SimpleDateFormat` in a field, plus a private field PMD reports as unused |
| `OrderService.java` | a clean file, so "no findings in this file" is visible next to the others |

Two engines read these files and both report some of the same lines. That is deliberate: RepoPilot's
own rules and PMD's ruleset are independent, and keeping them distinguishable by `analyzerId` is what
lets a reader see where two engines agree.

Register this directory as a repository, index it, then run static analysis:

```powershell
curl.exe -X POST "http://127.0.0.1:8080/api/v1/workspaces/<workspace-id>/repositories" `
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" `
  -d "{\"name\":\"quality-fixture\",\"path\":\"fixtures/quality-demo\"}"
```
