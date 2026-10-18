<#
.SYNOPSIS
    End-to-end smoke test for a running RepoPilot backend.

.DESCRIPTION
    Exercises the whole vertical slice against a live server: readiness, workspace
    listing, repository registration, indexing, job polling, and every read endpoint
    the UI depends on (coverage, files, mappings, symbols, graph, overview).

    It then exercises static analysis end to end: submit, poll, read the run back, and
    check the evidence contract on every finding — a confirmed citation, a non-blank
    limitation, and an evidence category that says "static analysis" rather than
    "measurement". Findings from a re-run must not replace the first run's.

    It asserts the identity contract as it goes: a create endpoint that returns a
    resource must return an id. That is not a formality — RepoPilot's HTTP layer
    serialises the entity it just created, so a null id would leave a client with
    nothing to poll (this happened once; see the identity notes in
    AbstractAuditedEntity).

    The analysis checks run against fixtures/quality-demo, which contains one deliberate
    instance of each built-in rule. That makes the expected result knowable in advance:
    eight rules fire, two of PMD's overlap them, and a clean file in the same project
    produces nothing. A check that cannot fail is not a check.

    It finally exercises runtime evidence: it generates a JFR recording with the local JDK
    and uploads it, then checks what the reader is allowed to claim and what it must not.
    The refusals matter as much as the successes — a text file is refused by name, a heap
    dump is stored but reported as unsupported, and analysing it is refused before any run
    exists. Every measurement must carry its unit, its reduction and the run it came from;
    an allocation sampling weight must never be presented as bytes; and a threshold-gated
    channel must carry its caveat on the measurement itself.

    When the data directory is known, it also checks the one thing the API cannot show:
    that deleting a repository removes the bytes it had stored, and leaves another
    repository's bytes alone. That check is skipped, loudly, when -DataDir is not given.

.PARAMETER BaseUrl
    Backend root. Defaults to loopback on port 8080.

.PARAMETER Token
    Bearer token. Defaults to $env:REPOPILOT_AUTH_TOKEN.

.PARAMETER DataDir
    Backend data directory, so the artefact store can be inspected on disk.
    Defaults to $env:REPOPILOT_DATA_DIR. The retention checks are skipped when it is empty.

.PARAMETER RepositoryPath
    Path (relative to an allowed workspace root, or absolute) of a sample Java repo.

.EXAMPLE
    $env:REPOPILOT_AUTH_TOKEN = "test-token-1234567890"
    ./scripts/smoke-test.ps1
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = "http://127.0.0.1:8080",
    [string] $Token = $env:REPOPILOT_AUTH_TOKEN,
    [string] $RepositoryPath = "examples/sample-java",
    [string] $FixturePath = "fixtures/quality-demo",
    [string] $DataDir = $env:REPOPILOT_DATA_DIR,
    [int]    $ReadyTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# Loaded once, here, because the HTTP helpers below are defined before they are used and the
# multipart upload needs it too. Without it, PowerShell 5.1 cannot resolve HttpClient at all.
Add-Type -AssemblyName System.Net.Http

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "No bearer token. Set REPOPILOT_AUTH_TOKEN or pass -Token."
}

$api = "$BaseUrl/api/v1"
$headers = @{ Authorization = "Bearer $Token" }
$jsonHeaders = @{ Authorization = "Bearer $Token"; "Content-Type" = "application/json" }

$failures = New-Object System.Collections.Generic.List[string]
$skipped = 0

function Assert-That([bool] $condition, [string] $message) {
    if ($condition) {
        Write-Host "  [ok]   $message" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] $message" -ForegroundColor Red
        $failures.Add($message) | Out-Null
    }
}

function Get-Json([string] $uri) {
    return Invoke-RestMethod -Uri $uri -Headers $headers -Method Get
}

function Post-Json([string] $uri, [string] $body) {
    return Invoke-RestMethod -Uri $uri -Headers $jsonHeaders -Method Post -Body $body
}

<#
Reads a property that the server may have omitted.

The backend is configured to leave null-valued properties out of its JSON, so an absent
key and an explicit null both mean "no value". Under Set-StrictMode, dotting into a
missing property is an error rather than $null, so optional fields are read through here.
#>
function Get-Prop($object, [string] $name) {
    if ($null -eq $object) { return $null }
    $property = $object.PSObject.Properties[$name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Wait-ForJob([string] $jobId, [int] $timeoutSeconds = 180) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    $job = $null
    while ((Get-Date) -lt $deadline) {
        $job = Get-Json "$api/jobs/$jobId"
        if (@("SUCCEEDED", "FAILED", "CANCELLED") -contains $job.status) { return $job }
        Start-Sleep -Milliseconds 500
    }
    return $job
}

# ---------------------------------------------------------------- readiness
Write-Host "`n== readiness ==" -ForegroundColor Cyan
$ready = $false
$deadline = (Get-Date).AddSeconds($ReadyTimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    try {
        $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health/readiness" -Headers $headers -TimeoutSec 5
        if ($health.status -eq "UP") { $ready = $true; break }
    } catch {
        Start-Sleep -Milliseconds 750
    }
}
Assert-That $ready "readiness endpoint reports UP"
if (-not $ready) {
    Write-Host "Backend is not ready; aborting." -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- overview
Write-Host "`n== overview ==" -ForegroundColor Cyan
$overview = Get-Json "$api/overview"
Write-Host ("  workspaces={0} repositories={1} active={2} aiUsable={3}" -f `
        $overview.workspaceCount, $overview.repositoryCount, $overview.activeJobCount, $overview.ai.usable)
Assert-That ($overview.workspaceCount -ge 1) "overview reports at least one workspace"
Assert-That ($null -ne $overview.ai) "overview reports an AI availability block (even when no provider)"

# ---------------------------------------------------------------- workspace
Write-Host "`n== workspace ==" -ForegroundColor Cyan
$workspaces = Get-Json "$api/workspaces"
$workspace = @($workspaces)[0]
Assert-That ($null -ne $workspace -and -not [string]::IsNullOrWhiteSpace($workspace.id)) `
    "workspace list returns an id"
Assert-That ($workspaces -isnot [System.Management.Automation.PSCustomObject]) `
    "workspace list is a JSON array (documented shape)"
$workspaceId = $workspace.id

# ---------------------------------------------------------------- register
Write-Host "`n== register repository ==" -ForegroundColor Cyan
$name = "smoke-" + (Get-Date -Format "yyyyMMddHHmmss")
$body = @{ name = $name; path = $RepositoryPath } | ConvertTo-Json -Compress
$repository = Post-Json "$api/workspaces/$workspaceId/repositories" $body
Assert-That (-not [string]::IsNullOrWhiteSpace($repository.id)) `
    "register returns a generated repository id (202/201 must be addressable)"
Assert-That ($null -ne $repository.createdAt) "register returns createdAt"
$repositoryId = $repository.id

# ---------------------------------------------------------------- submit index
Write-Host "`n== submit index job ==" -ForegroundColor Cyan
$job = Post-Json "$api/repositories/$repositoryId/index" "{}"
Assert-That (-not [string]::IsNullOrWhiteSpace($job.id)) `
    "submit returns a generated job id (otherwise the client cannot poll)"
Assert-That ($job.status -eq "QUEUED" -or $job.status -eq "RUNNING") "submitted job starts QUEUED"
$jobId = $job.id

# ---------------------------------------------------------------- poll
Write-Host "`n== poll job ==" -ForegroundColor Cyan
$terminal = @("SUCCEEDED", "FAILED", "CANCELLED")
$final = $null
$pollDeadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $pollDeadline) {
    $final = Get-Json "$api/jobs/$jobId"
    if ($terminal -contains $final.status) { break }
    Start-Sleep -Milliseconds 500
}
Assert-That ($final -ne $null -and $terminal -contains $final.status) "job reached a terminal state"
Assert-That ($final.status -eq "SUCCEEDED") "index job succeeded (was: $($final.status))"
Write-Host ("  summary: {0}" -f $final.resultSummary)

# ---------------------------------------------------------------- reads
Write-Host "`n== code intelligence ==" -ForegroundColor Cyan
$coverage = Get-Json "$api/repositories/$repositoryId/coverage"
Write-Host ("  coverage: inspected={0} total={1} parsed={2} problems={3} failed={4} recorded={5} excluded={6}" -f `
        $coverage.enumeratedFiles, $coverage.totalFiles, $coverage.parsedFiles, `
        $coverage.problemFiles, $coverage.failedFiles, $coverage.recordedFiles, `
        $coverage.excludedFiles)
Assert-That ($coverage.totalFiles -gt 0) "coverage reports files"

# Two partitions, asserted separately. Every file the indexer read ended in exactly one parse
# outcome; every file the walker inspected was either read or deliberately skipped.
# `excludedFiles` is not a row count and does not belong in the first sum: an excluded file
# never becomes a row, so counting it against `totalFiles` would hide it instead of reporting it.
Assert-That (($coverage.parsedFiles + $coverage.problemFiles + $coverage.failedFiles + `
        $coverage.recordedFiles) -eq $coverage.totalFiles) `
    "every file the indexer read has exactly one parse outcome"
Assert-That (($coverage.totalFiles + $coverage.excludedFiles) -eq $coverage.enumeratedFiles) `
    "every file the walker inspected was either read or deliberately skipped"

$files = Get-Json "$api/repositories/$repositoryId/files?size=200"
Assert-That ($files.totalItems -gt 0) "files endpoint returns indexed files"
Assert-That ($files.items.Count -gt 0) "files page is non-empty"
$firstFile = $files.items[0]
Assert-That (-not [string]::IsNullOrWhiteSpace($firstFile.relativePath)) "file view includes a relative path"

$contentUri = "$api/repositories/$repositoryId/files/content?path=" + [uri]::EscapeDataString($firstFile.relativePath)
$content = Get-Json $contentUri
Assert-That ($null -ne $content.content) "file content endpoint returns source text"

$symbols = Get-Json "$api/repositories/$repositoryId/symbols?size=200"
Write-Host ("  symbols: total={0}" -f $symbols.totalItems)
Assert-That ($symbols.totalItems -gt 0) "symbol index is populated"

$mappings = Get-Json "$api/repositories/$repositoryId/mappings"
Write-Host ("  mappings: {0}" -f @($mappings).Count)
Assert-That (@($mappings).Count -ge 0) "mappings endpoint responds"

$graph = Get-Json "$api/repositories/$repositoryId/graph"
Write-Host ("  graph: {0} of {1} matching edges (limit {2}, truncated {3})" -f `
        @($graph.items).Count, $graph.totalEdges, $graph.limit, $graph.truncated)
Assert-That (@($graph.items).Count -gt 0) "graph endpoint returns dependency edges"
# The graph is bounded on purpose, so the envelope has to state the total it sliced. A view
# that cannot tell "the whole graph" from "the first page of it" is a misleading one.
Assert-That ($graph.totalEdges -ge @($graph.items).Count) `
    "graph view states a total that is at least the slice it returned"
Assert-That (@($graph.items).Count -le $graph.limit) "graph view never exceeds its stated limit"

$revisions = Get-Json "$api/repositories/$repositoryId/revisions"
Assert-That (@($revisions).Count -ge 1) "revisions endpoint lists the indexed revision"

# ---------------------------------------------------------------- jobs
Write-Host "`n== jobs ==" -ForegroundColor Cyan
$counters = Get-Json "$api/jobs/counters"
Write-Host ("  queued={0} running={1} succeeded={2} failed={3} cancelled={4}" -f `
        $counters.queued, $counters.running, $counters.succeeded, $counters.failed, $counters.cancelled)
Assert-That ($counters.succeeded -ge 1) "job counters count the successful index run"

# ---------------------------------------------------------------- static analysis
Write-Host "`n== static analysis ==" -ForegroundColor Cyan
$analysisJob = Post-Json "$api/repositories/$repositoryId/analyze" "{}"
Assert-That (-not [string]::IsNullOrWhiteSpace($analysisJob.id)) "submit analysis returns a job id"
Assert-That ($analysisJob.jobType -eq "STATIC_ANALYSIS") "submitted job is a STATIC_ANALYSIS job"
$analysisJob = Wait-ForJob $analysisJob.id
Assert-That ($analysisJob.status -eq "SUCCEEDED") "analysis job succeeded (was: $($analysisJob.status))"
Write-Host ("  summary: {0}" -f $analysisJob.resultSummary)

$runSummary = Get-Json "$api/repositories/$repositoryId/findings/summary"
Assert-That (-not [string]::IsNullOrWhiteSpace($runSummary.runId)) `
    "a completed run is addressable by its own id"
Assert-That ($runSummary.runStatus -eq "SUCCEEDED") "run records its own terminal status"
Assert-That ($runSummary.total -ge 0) "run reports a total (zero is a result, not an error)"
Assert-That ($runSummary.revisionId -eq $revisions[0].id) `
    "the run names the revision it analysed"

# The summary is a count; the list is the evidence. They must agree, or one of them is wrong.
$runFindings = Get-Json "$api/repositories/$repositoryId/findings?runId=$($runSummary.runId)&size=200"
Assert-That ($runFindings.totalItems -eq $runSummary.total) `
    "the run-scoped list totals what the summary counts ($($runFindings.totalItems) vs $($runSummary.total))"
Assert-That (@($runFindings.items).Count -le 200) "findings endpoint respects its page size"

# ---------------------------------------------------------------- the evidence contract
Write-Host "`n== findings evidence contract (fixture) ==" -ForegroundColor Cyan
$fixtureName = "fixture-" + (Get-Date -Format "yyyyMMddHHmmss")
$fixtureBody = @{ name = $fixtureName; path = $FixturePath } | ConvertTo-Json -Compress
$fixture = Post-Json "$api/workspaces/$workspaceId/repositories" $fixtureBody
$fixtureIndex = Wait-ForJob (Post-Json "$api/repositories/$($fixture.id)/index" "{}").id
Assert-That ($fixtureIndex.status -eq "SUCCEEDED") "fixture index job succeeded"

$fixtureJob = Wait-ForJob (Post-Json "$api/repositories/$($fixture.id)/analyze" "{}").id
Assert-That ($fixtureJob.status -eq "SUCCEEDED") "fixture analysis job succeeded"
$fixtureSummary = Get-Json "$api/repositories/$($fixture.id)/findings/summary"
$fixtureFindings = Get-Json "$api/repositories/$($fixture.id)/findings?size=200"
Write-Host ("  fixture: {0} finding(s) from {1}" -f @($fixtureFindings.items).Count, (Get-Prop $fixtureSummary "analyzerVersions"))

Assert-That (@($fixtureFindings.items).Count -ge 10) "the fixture produces findings to check"

# Every built-in rule is expected to fire on exactly one construct in the fixture. A rule
# that quietly stops matching is invisible in a clean repository; here it is not.
$ruleIds = @($fixtureFindings.items | ForEach-Object { Get-Prop $_ "ruleId" })
foreach ($rule in @("RP001", "RP002", "RP003", "RP004", "RP005", "RP006", "RP007", "RP008")) {
    Assert-That ($ruleIds -contains $rule) "$rule fires on the fixture"
}
Assert-That ($ruleIds -contains "EmptyCatchBlock") `
    "PMD's own EmptyCatchBlock also fires (the two engines are reported separately)"

# Both engines see the same line when they agree. That is only checkable because the
# findings keep an analyzerId rather than being merged into one indistinguishable list.
$rp001 = $fixtureFindings.items | Where-Object { (Get-Prop $_ "ruleId") -eq "RP001" } | Select-Object -First 1
$pmdCatch = $fixtureFindings.items | Where-Object { (Get-Prop $_ "ruleId") -eq "EmptyCatchBlock" } | Select-Object -First 1
Assert-That ($null -ne $rp001 -and $null -ne $pmdCatch -and `
        (Get-Prop $rp001 "sourcePath") -eq (Get-Prop $pmdCatch "sourcePath") -and `
        (Get-Prop $rp001 "startLine") -eq (Get-Prop $pmdCatch "startLine")) `
    "the two engines are reported separately and agree on the location they found"

$badContract = New-Object System.Collections.Generic.List[string]
foreach ($finding in $fixtureFindings.items) {
    $status = Get-Prop $finding "verificationStatus"
    $limitations = Get-Prop $finding "limitations"
    $evidence = Get-Prop $finding "evidenceCategory"
    $line = Get-Prop $finding "startLine"
    $path = Get-Prop $finding "sourcePath"
    if ($status -ne "SOURCE_CONFIRMED") {
        $badContract.Add("$path has verificationStatus $status") | Out-Null
    }
    if ([string]::IsNullOrWhiteSpace($limitations)) {
        $badContract.Add("$(Get-Prop $finding 'ruleId') at $path states no limitation") | Out-Null
    }
    if ($evidence -ne "STATIC_FINDING") {
        $badContract.Add("$path claims evidence category $evidence") | Out-Null
    }
    if ($null -ne $line -and [int]$line -lt 1) {
        $badContract.Add("$path cites line $line, which is not a line") | Out-Null
    }
    if ([string]::IsNullOrWhiteSpace($path)) {
        $badContract.Add("$(Get-Prop $finding 'ruleId') cites no file") | Out-Null
    }
}
Assert-That ($badContract.Count -eq 0) `
    "every finding confirms its citation, states a limitation and claims static evidence"
if ($badContract.Count -gt 0) {
    $badContract | Select-Object -First 5 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
}

# The credential rule is the one place where a finding could leak the thing it found. The
# rule stores the name of the field, never the value, and this is the assertion that keeps
# that true: the fixture's fake secret must appear nowhere in the response.
$rawFindings = (Invoke-WebRequest -Uri "$api/repositories/$($fixture.id)/findings?size=200" `
        -Headers $headers -Method Get -UseBasicParsing).Content
Assert-That (-not $rawFindings.Contains("demo-api-key-not-a-real-secret")) `
    "the credential literal never appears in a finding response"
Assert-That ($rawFindings.Contains("apiKey")) `
    "the credential rule does name the field it found (without the value)"

# ---------------------------------------------------------------- filters
Write-Host "`n== findings filters ==" -ForegroundColor Cyan
$high = Get-Json "$api/repositories/$($fixture.id)/findings?severity=HIGH&size=200"
Assert-That (@($high.items).Count -ge 1) "severity filter returns the fixture's high findings"
Assert-That (@($high.items | Where-Object { (Get-Prop $_ "severity") -ne "HIGH" }).Count -eq 0) `
    "severity filter returns only the severity asked for"

$pmd = Get-Json "$api/repositories/$($fixture.id)/findings?analyzerId=pmd&size=200"
Assert-That (@($pmd.items).Count -ge 1) "analyzer filter returns PMD's findings"
Assert-That (@($pmd.items | Where-Object { (Get-Prop $_ "analyzerId") -ne "pmd" }).Count -eq 0) `
    "analyzer filter returns only the analyzer asked for"

$byPath = Get-Json ("$api/repositories/$($fixture.id)/findings?path=" + `
        [uri]::EscapeDataString("src/main/java/com/example/quality/OrderImporter.java") + "&size=200")
Assert-That (@($byPath.items).Count -ge 5) "path filter returns the findings in one file"
Assert-That (@($byPath.items | Where-Object { (Get-Prop $_ "sourcePath") -notlike "*OrderImporter.java" }).Count -eq 0) `
    "path filter returns only the file asked for"

$clean = Get-Json ("$api/repositories/$($fixture.id)/findings?path=" + `
        [uri]::EscapeDataString("src/main/java/com/example/quality/OrderService.java") + "&size=200")
Assert-That (@($clean.items).Count -eq 0) `
    "the fixture's clean file has no findings (an empty result is still an answer)"

# ---------------------------------------------------------------- re-runs are additive
Write-Host "`n== re-run keeps the previous run ==" -ForegroundColor Cyan
$secondJob = Wait-ForJob (Post-Json "$api/repositories/$($fixture.id)/analyze" "{}").id
Assert-That ($secondJob.status -eq "SUCCEEDED") "a second analysis job succeeded"
$secondSummary = Get-Json "$api/repositories/$($fixture.id)/findings/summary"
Assert-That ($secondSummary.runId -ne $fixtureSummary.runId) "the re-run is a new run, not a mutation of the old one"
Assert-That ($secondSummary.total -eq $fixtureSummary.total) `
    "re-analysing unchanged code reports the same number of findings"

$firstRunStillThere = Get-Json "$api/repositories/$($fixture.id)/findings?runId=$($fixtureSummary.runId)&size=200"
Assert-That ($firstRunStillThere.totalItems -eq $fixtureSummary.total) `
    "the first run's findings are still readable by run id after the second run"

# ---------------------------------------------------------------- runtime evidence
Write-Host "`n== runtime evidence (JFR) ==" -ForegroundColor Cyan

# The multipart upload below uses System.Net.Http rather than curl.exe, so the script's only
# dependency stays the .NET framework that ships with PowerShell — but the assembly is not
# loaded by default in a fresh session.
Add-Type -AssemblyName System.Net.Http

<#
Uploads a file as a multipart part and returns the status and body whatever the status is.

Invoke-RestMethod is used everywhere else in this script because the happy path is the point
there; here the refusals are part of the contract, so the response has to be read rather than
thrown. System.Net.Http is used instead of curl.exe so the script has no dependency beyond the
.NET framework that ships with PowerShell.
#>
function Send-Artifact([string] $repository, [string] $path, [string] $filename, [string] $artifactType) {
    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.Authorization =
        (New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $Token))
    $content = New-Object System.Net.Http.MultipartFormDataContent
    $stream = [System.IO.File]::OpenRead($path)
    try {
        $part = New-Object System.Net.Http.StreamContent($stream)
        $part.Headers.ContentType =
            [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/octet-stream")
        $content.Add($part, "file", $filename)
        $uri = "$api/repositories/$repository/runtime/artifacts"
        if (-not [string]::IsNullOrWhiteSpace($artifactType)) {
            $uri = "${uri}?artifactType=$artifactType"
        }
        $response = $client.PostAsync($uri, $content).Result
        return @{ Status = [int]$response.StatusCode; Body = $response.Content.ReadAsStringAsync().Result }
    } finally {
        $content.Dispose()
        $client.Dispose()
    }
}

<# Posts a request whose rejection is the assertion, and returns its status and body. #>
function Post-ExpectingFailure([string] $uri, [string] $body) {
    return Invoke-ExpectingFailure -Method Post -Uri $uri -Body $body
}

<# Reads a request whose rejection is the assertion, and returns its status and body. #>
function Get-ExpectingFailure([string] $uri) {
    return Invoke-ExpectingFailure -Method Get -Uri $uri
}

<# Deletes a repository. 204 on success, so there is no body to read. #>
function Remove-Repository([string] $repositoryId) {
    Invoke-WebRequest -Uri "$api/repositories/$repositoryId" -Headers $headers `
        -Method Delete -UseBasicParsing | Out-Null
}

<#
Invoke-WebRequest throws on a non-2xx response, which is exactly what these callers want to
observe rather than propagate. The error body is read the awkward way because PowerShell 5.1
drains the response stream before the exception surfaces.
#>
function Invoke-ExpectingFailure {
    param([string] $Method, [string] $Uri, [string] $Body)
    try {
        $response = Invoke-WebRequest -Uri $Uri -Headers $jsonHeaders -Method $Method `
            -Body $Body -UseBasicParsing
        return @{ Status = $response.StatusCode; Body = $response.Content }
    } catch {
        # $_.ErrorDetails.Message is where PowerShell 5.1 puts the error body; the response stream
        # is already drained by the time the exception surfaces, and reading it yields nothing.
        $details = $_.ErrorDetails
        if ($null -ne $details -and -not [string]::IsNullOrWhiteSpace($details.Message)) {
            return @{ Status = [int]$_.Exception.Response.StatusCode; Body = $details.Message }
        }
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) { throw }
        $reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream())
        try {
            return @{ Status = [int]$webResponse.StatusCode; Body = $reader.ReadToEnd() }
        } finally {
            $reader.Dispose()
        }
    }
}

<#
The recording is generated by the JDK on this machine rather than checked in.

A binary fixture would pin these assertions to whichever JDK wrote it, and the numbers below
would then be measuring the fixture's age rather than the reader's behaviour. Generating it here
means the workload and the JDK agree by construction.
#>
$runtimeDir = Join-Path ([System.IO.Path]::GetTempPath()) "repopilot-smoke"
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
$generator = Join-Path $runtimeDir "RepopilotSmokeRecording.java"
$recordingPath = Join-Path $runtimeDir "smoke-recording.jfr"

$generatorSource = @'
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class RepopilotSmokeRecording {
    private static volatile long sink;

    public static void main(String[] args) throws Exception {
        Path target = Path.of(args[0]);
        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.setName("repopilot-smoke");
            recording.enable("jdk.ThreadSleep").with("threshold", "0 ns");
            recording.enable("jdk.FileRead").with("threshold", "0 ns");
            recording.enable("jdk.FileWrite").with("threshold", "0 ns");
            recording.enable("jdk.ExceptionStatistics");
            recording.enable("jdk.JavaExceptionThrow").with("threshold", "0 ns");
            recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(10));
            recording.enable("jdk.ObjectAllocationSample");
            recording.start();

            Path payload = Files.createTempFile("repopilot-smoke-", ".bin");
            byte[] block = new byte[1 << 19];
            for (int i = 0; i < 8; i++) {
                Files.write(payload, block);
                Files.readAllBytes(payload);
            }
            Files.deleteIfExists(payload);

            long deadline = System.nanoTime() + java.time.Duration.ofMillis(1200).toNanos();
            long accumulator = 1;
            while (System.nanoTime() < deadline) {
                for (int i = 0; i < 4096; i++) {
                    accumulator = accumulator * 31 + i;
                }
            }
            sink = accumulator;

            for (int i = 0; i < 10; i++) {
                Thread.sleep(25);
            }
            for (int i = 0; i < 120; i++) {
                try {
                    throw new IllegalStateException("smoke-" + i);
                } catch (IllegalStateException expected) {
                    sink = i;
                }
            }

            List<Object> retained = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                retained.add(new byte[64 * 1024]);
            }
            sink = retained.size();

            for (int i = 0; i < 3; i++) {
                System.gc();
            }
            Thread.sleep(50);
            recording.stop();
            recording.dump(target);
        }
        System.out.println("wrote " + target + " (" + Files.size(target) + " bytes)");
    }
}
'@

# WriteAllText, not Set-Content -Encoding UTF8: PowerShell 5.1's UTF8 encoding writes a
# byte-order mark, and the JDK's source launcher rejects a BOM as an illegal character.
[System.IO.File]::WriteAllText($generator, $generatorSource, (New-Object System.Text.UTF8Encoding($false)))
& java $generator $recordingPath | Out-Null
$recordingLength = 0
if (Test-Path $recordingPath) { $recordingLength = (Get-Item $recordingPath).Length }
Assert-That ($recordingLength -gt 0) "a JFR recording was generated locally ($([math]::Round($recordingLength / 1KB)) KiB)"

$upload = Send-Artifact $repositoryId $recordingPath "smoke-recording.jfr" "JFR_RECORDING"
Assert-That ($upload.Status -eq 201) "a JFR recording uploads (was $($upload.Status))"
$artifact = $upload.Body | ConvertFrom-Json
Assert-That (-not [string]::IsNullOrWhiteSpace($artifact.id)) `
    "the upload returns a generated artefact id"
Assert-That ($artifact.contentHash.Length -eq 64) "the artefact is addressed by its SHA-256"
Assert-That ($artifact.sizeBytes -eq $recordingLength) "the stored size is the size that was uploaded"
Assert-That ($artifact.supported -eq $true) "a JFR recording is reported as readable"
Assert-That ($artifact.measurementCount -eq 0) "a freshly uploaded artefact has no measurements yet"

# One recording, one identity: identical bytes must not be stored twice, and a filename that
# looks like a path must not become one.
$again = Send-Artifact $repositoryId $recordingPath "../../escape.jfr" "JFR_RECORDING"
$againArtifact = $again.Body | ConvertFrom-Json
Assert-That ($againArtifact.id -eq $artifact.id) `
    "re-uploading identical bytes reuses the existing artefact"
Assert-That ($againArtifact.originalFilename -eq "smoke-recording.jfr") `
    "the first upload's name is kept and the hostile path is discarded"
Assert-That ($artifact.originalFilename -notlike "*..*") `
    "no artefact filename contains a path traversal sequence"

# A file that is not a recording is refused by name, before anything is queued.
$notRecording = Join-Path $runtimeDir "not-a-recording.jfr"
[System.IO.File]::WriteAllText($notRecording, "this is a text file, not a recording",
        (New-Object System.Text.UTF8Encoding($false)))
$refused = Send-Artifact $repositoryId $notRecording "not-a-recording.jfr" "JFR_RECORDING"
Assert-That ($refused.Status -eq 422) "a file without the JFR header is refused (was $($refused.Status))"
$refusal = $refused.Body | ConvertFrom-Json
Assert-That ($refusal.errorCode -eq "UNSUPPORTED_INPUT") "the refusal carries a stable error code"
Assert-That ($refusal.message -like "*FLR*") "the refusal says what was expected instead"

# An artefact this build cannot read is stored and reported as such, because throwing away
# evidence the operator already collected is worse than admitting the reader is not written.
$heap = Send-Artifact $repositoryId $notRecording "smoke.hprof" "HEAP_DUMP"
Assert-That ($heap.Status -eq 201) "an artefact type this build cannot read is still stored"
$heapArtifact = $heap.Body | ConvertFrom-Json
Assert-That ($heapArtifact.supported -eq $false) "the unsupported artefact is reported as unreadable"
Assert-That (-not [string]::IsNullOrWhiteSpace($heapArtifact.unsupportedReason)) `
    "the unsupported artefact names the reason it cannot be read"
Assert-That ($heapArtifact.containsProcessMemory -eq $true) "a heap dump is flagged as process memory"

$heapAnalysis = Post-ExpectingFailure `
    "$api/repositories/$repositoryId/runtime/artifacts/$($heapArtifact.id)/analyze" "{}"
Assert-That ($heapAnalysis.Status -eq 422) `
    "analysing an unreadable artefact is refused (was $($heapAnalysis.Status))"
$heapRefusal = $heapAnalysis.Body | ConvertFrom-Json
Assert-That ((Get-Prop $heapRefusal "errorCode") -eq "UNSUPPORTED_INPUT") `
    "the refusal names the unsupported input"
$noRunYet = Get-Json "$api/repositories/$repositoryId/runtime/summary"
Assert-That ((Get-Prop $noRunYet "runId") -eq $null) `
    "the refused analysis created no run at all"

# Analyse the recording: a durable job, then a run.
$runtimeJob = Post-Json "$api/repositories/$repositoryId/runtime/artifacts/$($artifact.id)/analyze" "{}"
Assert-That ($runtimeJob.jobType -eq "JFR_ANALYSIS") "the analysis is queued as a JFR job"
$runtimeFinal = Wait-ForJob $runtimeJob.id 300
Assert-That ($runtimeFinal.status -eq "SUCCEEDED") "the JFR analysis succeeded (was $($runtimeFinal.status))"
Write-Host ("  summary: {0}" -f $runtimeFinal.resultSummary)
Assert-That ($runtimeFinal.resultSummary -like "*no threshold applied*") `
    "the run summary states that no threshold was applied"
Assert-That ($runtimeFinal.resultSummary -like "*no severity assigned*") `
    "the run summary states that no severity was assigned"
Assert-That ($runtimeFinal.resultSummary -notlike "*64-Bit Server VM Java HotSpot*") `
    "the run summary does not name the JVM twice"
$runtimeRunId = $runtimeFinal.analysisRunId
Assert-That (-not [string]::IsNullOrWhiteSpace($runtimeRunId)) "the run is addressable by id"

# The provenance travels with the measurements: a number whose machine is unknown is not
# comparable to anything.
$runtimeSummary = Get-Json "$api/repositories/$repositoryId/runtime/summary"
Assert-That ((Get-Prop $runtimeSummary "runId") -eq $runtimeRunId) `
    "the runtime summary points at the run that just finished"
Assert-That ((Get-Prop $runtimeSummary "status") -eq "SUCCEEDED") "the runtime summary reports the run status"
Assert-That ((Get-Prop $runtimeSummary "measurementCount") -gt 0) "the run produced measurements"
$familyObject = Get-Prop $runtimeSummary "byMetricFamily"
$familyNames = @()
if ($null -ne $familyObject) { $familyNames = @($familyObject.PSObject.Properties | ForEach-Object { $_.Name }) }
Assert-That ($familyNames -contains "execution") "measurements are grouped by metric family"
$provenance = Get-Prop $runtimeSummary "provenanceJson"
Assert-That ($null -ne $provenance) "the recording's provenance is stored with the run"
Assert-That (-not [string]::IsNullOrWhiteSpace((Get-Prop (Get-Prop $provenance "environment") "jvmName"))) `
    "the provenance names the JVM the recording came from"
Assert-That ((Get-Prop (Get-Prop $provenance "produced") "thresholdsApplied") -eq $false) `
    "the provenance records that no threshold was applied"
$provenanceText = $provenance | ConvertTo-Json -Depth 10 -Compress
Assert-That (-not $provenanceText.Contains("java.class.path")) `
    "the provenance carries no class-path setting (a credential carrier)"
Assert-That (-not $provenanceText.Contains("CLASSPATH")) `
    "the provenance carries no environment-derived classpath"

$measurements = Get-Json "$api/repositories/$repositoryId/runtime/measurements?size=500"
Assert-That ($measurements.totalItems -gt 0) "the measurements endpoint returns rows"
$rows = @($measurements.items)
Assert-That (@($rows | Where-Object { [string]::IsNullOrWhiteSpace($_.unit) }).Count -eq 0) `
    "every measurement carries its unit"
# A sample count of zero is not a defect: it is the backend saying the figure reduces no samples
# at all. The invariant is that only such a figure claims it — everything else says how many.
Assert-That (@($rows | Where-Object { $_.sampleCount -lt 1 -and $_.metricKey -ne "jfr.recording.window" }).Count -eq 0) `
    "only a figure that reduces no samples reports a sample count of zero"
$window = @($rows | Where-Object { $_.metricKey -eq "jfr.recording.window" })
Assert-That ($window.Count -eq 1) "the recording reports its own window"
Assert-That ($window[0].sampleCount -eq 0) `
    "the recording window states that it reduces no samples rather than implying one"
Assert-That ($window[0].unit -eq "ms") "the recording window is a duration"
Assert-That (@($rows | Where-Object { (Get-Prop $_ "analysisRunId") -ne $runtimeRunId }).Count -eq 0) `
    "every measurement is tied to the run that produced it"
Assert-That (@($rows | Where-Object { (Get-Prop $_ "artifactId") -ne $artifact.id }).Count -eq 0) `
    "every measurement is tied to the artefact it was read from"
Assert-That (@($rows | Where-Object { @("COUNT", "SUM", "MEAN", "MAX") -notcontains $_.aggregation }).Count -eq 0) `
    "every measurement states one of the four supported reductions (no percentiles are implied)"

# A sampling weight is a weight, never bytes. The recording's allocation samples carry a
# sampling weight, and presenting it as memory would understate allocation by more than half.
$weights = @($rows | Where-Object { $_.metricKey -like "jfr.allocation*" })
Assert-That ($weights.Count -gt 0) "the recording reports its allocation samples"
Assert-That (@($weights | Where-Object { $_.unit -eq "bytes" }).Count -eq 0) `
    "an allocation sample is reported as a weight, never as bytes"

# Shares are drawn from samples, so they cannot add up to more samples than were taken.
$samples = @($rows | Where-Object { $_.metricKey -eq "jfr.execution.samples" })
Assert-That ($samples.Count -eq 1) "the recording reports its execution sample count"
$hotFrames = @($rows | Where-Object { $_.metricKey -eq "jfr.execution.hotFrame" })
Assert-That ($hotFrames.Count -gt 0) "the recording reports hot frames"
$shareSum = 0.0
$hotFrames | ForEach-Object { $shareSum = $shareSum + [double]$_.value }
Assert-That ($shareSum -le [double]$samples[0].value) `
    "hot-frame counts do not exceed the samples they were drawn from"

# A threshold-gated channel says so, on the measurement rather than in a manual: the value is
# the count of events that exceeded a threshold, which is not the count of occurrences.
$sleepEvents = @($rows | Where-Object { $_.metricKey -eq "jfr.sleep.count" })
Assert-That ($sleepEvents.Count -eq 1) "the recording reports its thread sleeps"
Assert-That ($sleepEvents[0].attributesJson -like "*thresholdGated*") `
    "a threshold-gated channel carries the caveat that explains it on the row itself"

# A GC pause is a duration in milliseconds, not a bare number.
$pauses = @($rows | Where-Object { $_.metricKey -eq "jfr.gc.pause.total" })
Assert-That ($pauses.Count -eq 1) "the recording reports its total GC pause time"
Assert-That ($pauses[0].unit -eq "ms") "the total GC pause time is reported in milliseconds"
Assert-That ($pauses[0].metricKind -eq "DURATION") "the total GC pause time is a duration, not a count"

$durationRows = Get-Json "$api/repositories/$repositoryId/runtime/measurements?kind=DURATION&size=500"
Assert-That (@($durationRows.items).Count -gt 0) "the kind filter returns the durations"
Assert-That (@($durationRows.items | Where-Object { $_.metricKind -ne "DURATION" }).Count -eq 0) `
    "the kind filter returns only the kind asked for"

$prefixRows = Get-Json "$api/repositories/$repositoryId/runtime/measurements?metric=jfr.gc&size=500"
Assert-That (@($prefixRows.items).Count -gt 0) "the metric filter matches a key prefix"
Assert-That (@($prefixRows.items | Where-Object { $_.metricKey -notlike "jfr.gc*" }).Count -eq 0) `
    "the metric filter returns only keys under the prefix"

# A runtime run is not a static-analysis run: it must not report findings, and it must not
# become the repository's current static-analysis run.
$staticSummary = Get-Json "$api/repositories/$repositoryId/findings/summary"
Assert-That ((Get-Prop $staticSummary "runId") -ne $runtimeRunId) `
    "a runtime run does not become the repository's static-analysis run"

# Analysing the same unchanged recording again is a new run with the same numbers, and the
# first run's measurements stay readable by run id.
$secondRuntimeJob = Post-Json "$api/repositories/$repositoryId/runtime/artifacts/$($artifact.id)/analyze" "{}"
$secondRuntimeFinal = Wait-ForJob $secondRuntimeJob.id 300
Assert-That ($secondRuntimeFinal.status -eq "SUCCEEDED") "re-analysing the recording succeeded"
Assert-That ($secondRuntimeFinal.analysisRunId -ne $runtimeRunId) "the re-analysis is a new run"
$secondRunRows = Get-Json `
    "$api/repositories/$repositoryId/runtime/measurements?runId=$($secondRuntimeFinal.analysisRunId)&size=1"
Assert-That ($secondRunRows.totalItems -eq $measurements.totalItems) `
    "reading the same recording twice yields the same number of measurements"
$firstRunRows = Get-Json "$api/repositories/$repositoryId/runtime/measurements?runId=$runtimeRunId&size=1"
Assert-That ($firstRunRows.totalItems -eq $measurements.totalItems) `
    "the first run's measurements are still readable by run id"

# ---------------------------------------------------------------- retention on delete
Write-Host "`n== artefact store on delete ==" -ForegroundColor Cyan
if ([string]::IsNullOrWhiteSpace($DataDir)) {
    # Not a pass and not a failure: an unchecked check. Said out loud, because a check that
    # silently does nothing is worse than no check at all.
    Write-Host "  SKIPPED: -DataDir (or REPOPILOT_DATA_DIR) is not set, so the artefact" -ForegroundColor Yellow
    Write-Host "           store cannot be inspected on disk. The same assertions run" -ForegroundColor Yellow
    Write-Host "           unconditionally in RuntimeUploadLimitIntegrationTest." -ForegroundColor Yellow
    $skipped++
} else {
    $disposable = Post-Json "$api/workspaces/$workspaceId/repositories" `
        (@{ name = "smoke-disposable-$name"; path = $FixturePath } | ConvertTo-Json -Compress)
    $disposableUpload = Send-Artifact $disposable.id $recordingPath "disposable.jfr" "JFR_RECORDING"
    Assert-That ($disposableUpload.Status -eq 201) `
        "a recording uploads to a throwaway repository (was $($disposableUpload.Status))"

    $disposableStore = Join-Path $DataDir "artifacts/$($disposable.id)"
    $survivingStore = Join-Path $DataDir "artifacts/$repositoryId"
    Assert-That (Test-Path $disposableStore) "the uploaded bytes are on disk in the artefact store"
    Assert-That (Test-Path $survivingStore) "the analysed repository has its own store"

    Remove-Repository $disposable.id

    # The records go in a transaction, the bytes go after it commit. Both must go: a heap dump
    # is process memory, and "deleted" has to mean deleted rather than "no longer listed".
    Assert-That (-not (Test-Path $disposableStore)) `
        "deleting a repository removes the bytes it had stored"
    Assert-That (Test-Path $survivingStore) `
        "deleting one repository leaves another repository's bytes untouched"
    $gone = Get-ExpectingFailure "$api/repositories/$($disposable.id)"
    Assert-That ($gone.Status -eq 404) "the deleted repository is gone from the API"
}

# ---------------------------------------------------------------- summary
Write-Host "`n== summary ==" -ForegroundColor Cyan
if ($skipped -gt 0) {
    Write-Host ("{0} check(s) skipped (see the notes above)." -f $skipped) -ForegroundColor Yellow
}
if ($failures.Count -eq 0) {
    Write-Host "All checks passed." -ForegroundColor Green
    exit 0
} else {
    Write-Host ("{0} check(s) failed:" -f $failures.Count) -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
