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
    The refusals matter as much as the successes — a file with no recording header is
    refused by name, and the refusal comes before any run exists. Every measurement must
    carry its unit, its reduction and the run it came from; an allocation sampling weight
    must never be presented as bytes; and a threshold-gated channel must carry its caveat on
    the measurement itself.

    Then the same for thread dumps, which is where a second reader proved the difference
    between "runtime evidence" and "a JFR run": a real dump of this very backend is taken
    with jcmd, uploaded, analysed and read back. All four thread states must be reported
    even when one of them is empty, the JVM's own threads must be counted apart from the
    Java ones, the deadlock count must be the JVM's own rather than a search RepoPilot ran,
    the RUNNABLE count must carry the caveat that HotSpot reports a blocked native call as
    runnable, and no stack frame may reach the database. The two thin Thread.dump_to_file
    shapes are refused by name, with the command that produces a readable dump.

    Then the same again for heap dumps, where the reader makes an exact claim (a census) and
    must refuse a larger one (a retained size) on every row it writes. A real dump of this
    backend is taken with jcmd GC.heap_dump, uploaded, deduplicated by content hash, analysed
    and read back: every measurement must be a count or a byte figure with a stated basis,
    the two type tables must disclose that they are cut at twenty, the three parts of the
    census must sum to the whole, and no threshold or severity may appear anywhere. The three
    files an operator most often has instead — a class histogram, a gzipped dump and a thread
    dump — are each refused by name, with the command that produces a readable one.

    Then benchmark results, where the reader computes nothing at all: the recorded two-fork JMH
    run is uploaded, imported, and read back against the exact figures its file states. Every
    value must be quoted rather than reduced, the file's own unit must survive, the command line
    must be counted and not stored, and the other shapes JMH writes must be refused with the
    command that produces the readable one. An array that is not a result file is stored and then
    fails, naming the field it lacked, because JSON has no header to check at upload.

    Then artefact retention: the plan must count what the repository holds, name no candidate on
    a fresh server rather than showing a bare empty list, and refuse a zero-day window instead of
    reading it as "everything". The deletion path itself - file gone, mark set, measurements kept,
    re-upload restoring the artefact - needs a file older than the window and runs in
    ArtifactRetentionIntegrationTest, which backdates created_at rather than waiting thirty days.

    Then AI investigation, when a provider is usable: a question about the fixture is asked, the
    job is followed, and the answer is read back against RepoPilot's own rules. Every claim must
    be verified, every cited key must have been in the prompt, the grounded flag must be true
    because those held rather than because the answer read well, the prompt must be recorded as a
    hash instead of the text, and no response field may carry an excerpt. A repository's id must
    not read another repository's investigation, a question made only of stop words must be
    refused before a job exists, and a cancelled investigation must stay cancelled. The section is
    skipped, loudly, when no provider is reachable; the refusal path is asserted in
    InvestigationProviderUnavailableIntegrationTest.

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
# dependency stays the .NET framework that ships with PowerShell (loaded at the top of the file).

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
    return Invoke-ExpectingFailure -Method "POST" -Uri $uri -Body $body
}

<# Reads a request whose rejection is the assertion, and returns its status and body. #>
function Get-ExpectingFailure([string] $uri) {
    return Invoke-ExpectingFailure -Method "GET" -Uri $uri
}

<# Deletes a repository. 204 on success, so there is no body to read. #>
function Remove-Repository([string] $repositoryId) {
    Invoke-WebRequest -Uri "$api/repositories/$repositoryId" -Headers $headers `
        -Method Delete -UseBasicParsing | Out-Null
}

<#
Sends a request and returns its status and body whatever that status is.

Invoke-RestMethod is used everywhere else in this script because the happy path is the point
there; here the refusal is the assertion, so the response has to be read rather than thrown.

HttpClient rather than Invoke-WebRequest with a try/catch: PowerShell 5.1's error record for a
non-2xx response varies with the release, and reaching for `$_.Exception.Response` throws under
Set-StrictMode when the property is absent — which is how this helper failed the first time it
was asked for a 404.
#>
function Invoke-ExpectingFailure {
    param([string] $Method, [string] $Uri, [string] $Body)
    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.Authorization =
        (New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $Token))
    $request = New-Object System.Net.Http.HttpRequestMessage(([System.Net.Http.HttpMethod]::new($Method)), $Uri)
    if (-not [string]::IsNullOrWhiteSpace($Body)) {
        $request.Content = New-Object System.Net.Http.StringContent(
            $Body, [System.Text.Encoding]::UTF8, "application/json")
    }
    try {
        $response = $client.SendAsync($request).Result
        return @{ Status = [int]$response.StatusCode; Body = $response.Content.ReadAsStringAsync().Result }
    } finally {
        $request.Dispose()
        $client.Dispose()
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

# A heap dump is a supported type as of this phase, so the file that proves a refusal is one that
# is not a dump at all. It is refused at upload rather than stored: an artefact nothing can read
# and nothing can analyse is not evidence, it is a row that makes the artefact list lie.
$notADump = Send-Artifact $repositoryId $notRecording "smoke.hprof" "HEAP_DUMP"
Assert-That ($notADump.Status -eq 422) `
    "a file with no HPROF header is refused as a heap dump (was $($notADump.Status))"
$notADumpRefusal = $notADump.Body | ConvertFrom-Json
Assert-That ($notADumpRefusal.errorCode -eq "UNSUPPORTED_INPUT") `
    "the heap-dump refusal carries a stable error code"
Assert-That ($notADumpRefusal.message -like "*HPROF*") `
    "the heap-dump refusal names the format it expected"
$noRunYet = Get-Json "$api/repositories/$repositoryId/runtime/summary"
Assert-That ((Get-Prop $noRunYet "runId") -eq $null) `
    "the refused upload created no run at all"

<#
There is no longer an artefact type this build stores and cannot read: all four have a reader. The
mechanism survives — `supported` and `unsupportedReason` are how the API answers for a format a build
does not read — but a check that asserted "stored, unread" would now have to invent a type, and an
invented type is refused at the upload rather than stored. So what is asserted here is the pair that
is still true: an unknown type is a bad request, and a known type carrying the wrong bytes is refused
by name. The second is already proven twice above (a recording without a header, a heap dump without
one); benchmarks prove it a third time in their own section below.
#>
$unknownType = Send-Artifact $repositoryId $notRecording "smoke-unknown.json" "NOT_A_RUNTIME_TYPE"
Assert-That ($unknownType.Status -eq 400) `
    "an artefact type the API does not know is a bad request (was $($unknownType.Status))"

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

# ---------------------------------------------------------------- runtime evidence (thread dumps)
Write-Host "`n== runtime evidence (thread dumps) ==" -ForegroundColor Cyan

<#
Asks the JVM on the PATH where its home is, so jcmd can be found without trusting JAVA_HOME.

Only a JDK ships jcmd, and a JRE does not, so the checks that need a real dump skip themselves
rather than failing for a reason that has nothing to do with the code. The reader against a real
dump is also asserted unconditionally in ThreadDumpReaderTest, which takes its dump the same way.
#>
$javaHome = $null
try {
    # Through cmd, because PowerShell 5.1 turns a native command's output on stderr — which is where
    # `java -version` writes — into a terminating error under $ErrorActionPreference = "Stop".
    $javaProperties = @(& cmd /c "java -XshowSettings:properties -version 2>&1")
    $homeLines = @($javaProperties | Where-Object { $_ -like "*java.home = *" })
    if ($homeLines.Count -gt 0) { $javaHome = ($homeLines[0] -split "=", 2)[1].Trim() }
} catch {
    $javaHome = $null
}
$jcmdPath = $null
if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
    $candidate = Join-Path $javaHome "bin\jcmd.exe"
    if (Test-Path $candidate) { $jcmdPath = $candidate }
}

<#
The two thin Thread.dump_to_file shapes are refused by name, before anything is stored.

Written here as literals rather than captured, because the refusal is the assertion: they record no
thread states, no monitor ownership and no deadlock section, so a report built from them would be a
page of zeros indistinguishable from a healthy JVM.
#>
$thinPlain = Join-Path $runtimeDir "thread-dump-thin.txt"
$thinPlainText = "9568`n2026-10-19T04:34:15.300714Z`n21.0.5+9-LTS-239`n`n#9 `"Reference Handler`"`n" +
    "      java.base/java.lang.ref.Reference.waitForReferencePendingList(Native Method)"
[System.IO.File]::WriteAllText($thinPlain, $thinPlainText, (New-Object System.Text.UTF8Encoding($false)))
$refusedThin = Send-Artifact $repositoryId $thinPlain "thread-dump-thin.txt" "THREAD_DUMP"
Assert-That ($refusedThin.Status -eq 422) `
    "a Thread.dump_to_file -format=plain dump is refused (was $($refusedThin.Status))"
$thinRefusal = $refusedThin.Body | ConvertFrom-Json
Assert-That ($thinRefusal.errorCode -eq "UNSUPPORTED_INPUT") `
    "the thin-dump refusal carries a stable error code"
Assert-That ($thinRefusal.message -like "*Thread.dump_to_file*") `
    "the thin-dump refusal names the command that wrote the file"
Assert-That ($thinRefusal.message -like "*Thread.print*") `
    "the thin-dump refusal names the command that produces a readable dump"

$thinJson = Join-Path $runtimeDir "thread-dump-thin.json"
$thinJsonText = "{`n  `"threadDump`": {`n    `"processId`": `"9568`",`n    `"time`": `"2026-10-19T04:32:11Z`"`n  }`n}"
[System.IO.File]::WriteAllText($thinJson, $thinJsonText, (New-Object System.Text.UTF8Encoding($false)))
$refusedJson = Send-Artifact $repositoryId $thinJson "thread-dump-thin.json" "THREAD_DUMP"
Assert-That ($refusedJson.Status -eq 422) `
    "a Thread.dump_to_file -format=json dump is refused too (was $($refusedJson.Status))"
Assert-That ((($refusedJson.Body | ConvertFrom-Json).message) -like "*Thread.print*") `
    "the JSON refusal names a command that produces a readable dump"

<#
A real dump of the JVM under test, taken with the tool an operator would use.

Not a checked-in fixture: the reader is built on HotSpot's exact wording, so a captured file would
test the fixture's age rather than the reader's behaviour. The redirect is PowerShell's on purpose —
on Windows it re-encodes the tool's output as UTF-16 with a byte-order mark, which is how a dump
taken at a Windows console actually arrives.
#>
if ($null -eq $jcmdPath) {
    Write-Host "  SKIPPED: no jcmd in the JVM on the PATH, so no real dump can be taken. The same" -ForegroundColor Yellow
    Write-Host "           assertions run unconditionally in ThreadDumpAnalysisIntegrationTest." -ForegroundColor Yellow
    $skipped++
} else {
    $attachable = @(& $jcmdPath -l | Where-Object { $_ -like "*repopilot-backend*" })
    if ($attachable.Count -eq 0) {
        Write-Host "  SKIPPED: jcmd found nothing attachable running repopilot-backend." -ForegroundColor Yellow
        $skipped++
    } else {
        $backendPid = (@($attachable[0] -split "\s+"))[0]
        $dumpPath = Join-Path $runtimeDir "thread-dump.txt"
        & $jcmdPath $backendPid "Thread.print" > $dumpPath
        $dumpLength = 0
        if (Test-Path $dumpPath) { $dumpLength = (Get-Item $dumpPath).Length }
        Assert-That ($dumpLength -gt 0) "a thread dump of the backend was taken ($dumpLength bytes)"

        $dumpUpload = Send-Artifact $repositoryId $dumpPath "thread-dump.txt" "THREAD_DUMP"
        Assert-That ($dumpUpload.Status -eq 201) "a thread dump uploads (was $($dumpUpload.Status))"
        $dumpArtifact = $dumpUpload.Body | ConvertFrom-Json
        Assert-That ($dumpArtifact.artifactType -eq "THREAD_DUMP") "the artefact is recorded as a thread dump"
        Assert-That ($dumpArtifact.supported -eq $true) "a thread dump is reported as readable"
        # The contrast with a heap dump, which is flagged as process memory because it is a literal
        # copy of the heap: a dump names threads and frames, which is your code's structure, but it
        # is not a copy of the heap and must not be labelled as one — a warning that is wrong where
        # it matters elsewhere stops being read.
        Assert-That ($dumpArtifact.containsProcessMemory -eq $false) `
            "a thread dump is not flagged as process memory"

        $dumpJob = Post-Json "$api/repositories/$repositoryId/runtime/artifacts/$($dumpArtifact.id)/analyze" "{}"
        Assert-That ($dumpJob.jobType -eq "THREAD_DUMP_ANALYSIS") `
            "the dump is analysed by the thread-dump job, not by the JFR one"
        $dumpFinal = Wait-ForJob $dumpJob.id 300
        Assert-That ($dumpFinal.status -eq "SUCCEEDED") "the thread-dump analysis succeeded (was $($dumpFinal.status))"
        Write-Host ("  summary: {0}" -f $dumpFinal.resultSummary)
        Assert-That ($dumpFinal.resultSummary -like "*thread dump*") "the run summary says what was read"
        Assert-That ($dumpFinal.resultSummary -like "*one instant*") `
            "the run summary states that a dump describes one instant"
        Assert-That ($dumpFinal.resultSummary -like "*no threshold applied*") `
            "the run summary states that no threshold was applied"
        Assert-That ($dumpFinal.resultSummary -like "*no severity assigned*") `
            "the run summary states that no severity was assigned"

        # The newest runtime evidence is now the dump's run, even though the newest *JFR* run is
        # older. A "current run" query that named one pipeline would report that this repository has
        # no runtime evidence at all the moment a second reader appeared.
        $dumpSummary = Get-Json "$api/repositories/$repositoryId/runtime/summary"
        Assert-That ((Get-Prop $dumpSummary "runId") -eq $dumpFinal.analysisRunId) `
            "the runtime summary now points at the thread-dump run"
        Assert-That ((Get-Prop $dumpSummary "artifactType") -eq "THREAD_DUMP") `
            "the summary names the kind of artefact the run read"
        $dumpFamilies = @()
        $dumpFamilyObject = Get-Prop $dumpSummary "byMetricFamily"
        if ($null -ne $dumpFamilyObject) {
            $dumpFamilies = @($dumpFamilyObject.PSObject.Properties | ForEach-Object { $_.Name })
        }
        Assert-That (($dumpFamilies -contains "threads") -and ($dumpFamilies -contains "monitors")) `
            "the dump's measurements are grouped under its own families"
        Assert-That ($dumpFamilies -notcontains "gc") "a thread-dump run does not report JFR families"

        $dumpProvenance = Get-Prop $dumpSummary "provenanceJson"
        Assert-That ($null -ne $dumpProvenance) "the dump's header is stored with the run"
        Assert-That ((Get-Prop (Get-Prop $dumpProvenance "dump") "threads") -gt 0) `
            "the provenance counts the threads the dump reported"
        $dumpProvenanceText = $dumpProvenance | ConvertTo-Json -Depth 10 -Compress
        # The stacks stay in the file the operator uploaded. They are the application's own structure,
        # and copying them into a database column would put the source into the evidence store.
        Assert-That (-not $dumpProvenanceText.Contains("java.lang.Thread.run")) `
            "the provenance carries no thread stack"
        Assert-That (-not $dumpProvenanceText.Contains("\tat ")) "the provenance carries no stack frames"
        Assert-That ((Get-Prop (Get-Prop $dumpProvenance "produced") "findings") -eq 0) `
            "the dump run classifies nothing"

        $dumpRows = @((Get-Json "$api/repositories/$repositoryId/runtime/measurements?metric=threaddump&size=500").items)
        Assert-That ($dumpRows.Count -gt 0) "the dump produced measurements"
        Assert-That (@($dumpRows | Where-Object { $_.metricKey -notlike "threaddump.*" }).Count -eq 0) `
            "every measurement the filter returns is the dump's own"
        Assert-That (@($dumpRows | Where-Object { $_.metricKind -ne "COUNTER" }).Count -eq 0) `
            "every measurement of a dump is a count of something the dump reports"
        Assert-That (@($dumpRows | Where-Object { $_.unit -ne "count" }).Count -eq 0) `
            "every measurement of a dump carries the count unit"
        Assert-That (@($dumpRows | Where-Object { (Get-Prop $_ "analysisRunId") -ne $dumpFinal.analysisRunId }).Count -eq 0) `
            "every dump measurement is tied to the run that produced it"

        # All four states are reported, including an empty one: a state nobody was in has to be
        # stated, or "0 blocked" cannot be told apart from a state this build does not know.
        $stateRows = @($dumpRows | Where-Object { $_.metricKey -eq "threaddump.threads.state.count" })
        Assert-That ($stateRows.Count -eq 4) "all four thread states are reported ($($stateRows.Count))"
        $stateScopes = @($stateRows | ForEach-Object { $_.scope })
        foreach ($expectedState in @("RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING")) {
            Assert-That ($stateScopes -contains $expectedState) "the dump reports the $expectedState state"
        }
        $javaThreads = @($dumpRows | Where-Object { $_.metricKey -eq "threaddump.threads.java.count" })
        Assert-That ($javaThreads.Count -eq 1) "the dump reports how many threads have a Java state"
        $statedThreads = 0
        $stateRows | ForEach-Object { $statedThreads = $statedThreads + [int]$_.value }
        $unknownState = @($dumpRows | Where-Object { $_.metricKey -eq "threaddump.threads.unknownState.count" })
        $unknownCount = 0
        if ($unknownState.Count -gt 0) { $unknownCount = [int]$unknownState[0].value }
        Assert-That (($statedThreads + $unknownCount) -eq [int]$javaThreads[0].value) `
            "the four states plus any unrecognised one account for every Java thread"
        $runnable = @($stateRows | Where-Object { $_.scope -eq "RUNNABLE" })[0]
        Assert-That ($runnable.attributesJson -like "*native call*") `
            "the RUNNABLE count carries the caveat that it is not a count of threads on a CPU"

        # The JVM always reports its own runtime threads, and they carry no Java state; counting them
        # as Java threads would inflate every state's share.
        Assert-That (@($dumpRows | Where-Object { $_.metricKey -eq "threaddump.threads.nonjava.count" }).Count -eq 1) `
            "the dump reports the JVM's own threads separately"

        # Deadlocks are reported, never searched for. This process is not deadlocked, and the row says
        # so in the JVM's own terms rather than by RepoPilot's silence.
        $deadlockRows = @($dumpRows | Where-Object { $_.metricKey -eq "threaddump.threads.deadlock.count" })
        Assert-That ($deadlockRows.Count -eq 1) "the dump reports a deadlock count"
        Assert-That ($deadlockRows[0].value -eq 0) "the backend is not deadlocked at this instant"
        Assert-That ($deadlockRows[0].attributesJson -like "*does not search for cycles*") `
            "the deadlock row states that the count is the JVM's, not RepoPilot's"
        Assert-That (@($dumpRows | Where-Object { $_.metricKey -eq "threaddump.threads.deadlocked.count" }).Count -eq 0) `
            "no deadlocked threads are named when the detector reported none"

        # Contention is whatever the dump said, including nothing. The assertion is the contract: the
        # row exists, and the table cannot list more monitors than were observed.
        $contendedRows = @($dumpRows | Where-Object { $_.metricKey -eq "threaddump.monitors.contended.count" })
        Assert-That ($contendedRows.Count -eq 1) "the dump reports how many monitors were contended"
        $observedRows = @($dumpRows | Where-Object { $_.metricKey -eq "threaddump.monitors.waiters.observed" })
        $waitersRows = @($dumpRows | Where-Object { $_.metricKey -eq "threaddump.monitors.waiters" })
        Assert-That ($waitersRows.Count -le 20) "the contention table is bounded (TOP_N)"
        Assert-That (@($waitersRows | Where-Object { $_.attributesJson -notlike "*ownerThread*" }).Count -eq 0) `
            "every contended monitor says who holds it, or that nobody reported holding it"
        if ([int]$contendedRows[0].value -eq 0) {
            # Nothing was contended, so there is no table to disclose the size of: the count row is
            # the whole answer, and an "observed 0" row would be a second way of saying it.
            Assert-That ($waitersRows.Count -eq 0) "no contention table is written when nothing was contended"
            Assert-That ($observedRows.Count -eq 0) `
                "no truncation disclosure is written for a table that does not exist"
        } else {
            Assert-That ($observedRows.Count -eq 1) "the point-in-time contention count is stated"
            if ([int]$observedRows[0].value -le 20) {
                Assert-That ($waitersRows.Count -eq [int]$observedRows[0].value) `
                    "the table lists every contended monitor when there are fewer than the cap"
            } else {
                Assert-That ($waitersRows.Count -eq 20) "the table stops at the cap and says how many there were"
            }
        }

        # Two readers, one repository: the dump's run is newer, and the recording's rows are untouched.
        $jfrStillThere = Get-Json "$api/repositories/$repositoryId/runtime/measurements?runId=$runtimeRunId&size=1"
        Assert-That ($jfrStillThere.totalItems -eq $measurements.totalItems) `
            "a thread-dump run does not replace the recording's measurements"
    }
}

# ---------------------------------------------------------------- runtime evidence (heap dumps)
Write-Host "`n== runtime evidence (heap dumps) ==" -ForegroundColor Cyan

<#
The three files an operator most often has instead of a heap dump, each refused by name.

A class histogram is the common case and the most dangerous: `jmap -histo` is a command people run
first, and its output has counts and byte totals that look like a report. It has no objects behind
them, so the refusal says so and names the command that writes a real dump.

A gzip stream is refused rather than decompressed inside the job: a small file can expand without
bound, and no upload-time size limit can defend against that. The refusal names the one-line fix.

A thread dump is a different artefact type with its own reader, so this one is redirected rather than
diagnosed.
#>
$histogram = Join-Path $runtimeDir "class-histogram.hprof"
$histogramLines = @(
    " num     #instances         #bytes  class name (module)",
    "-------------------------------------------------------",
    "   1:          1352        1793216  [B (java.base@21.0.5)",
    "   2:           250          64000  [I (java.base@21.0.5)",
    "   3:           500          16000  dev.repopilot.sample.Node",
    "Total          2102        1873216"
)
[System.IO.File]::WriteAllText($histogram, ($histogramLines -join "`n"),
        (New-Object System.Text.UTF8Encoding($false)))
$refusedHistogram = Send-Artifact $repositoryId $histogram "class-histogram.hprof" "HEAP_DUMP"
Assert-That ($refusedHistogram.Status -eq 422) `
    "a class histogram is refused as a heap dump (was $($refusedHistogram.Status))"
$histogramRefusal = $refusedHistogram.Body | ConvertFrom-Json
Assert-That ($histogramRefusal.errorCode -eq "UNSUPPORTED_INPUT") `
    "the histogram refusal carries a stable error code"
Assert-That ($histogramRefusal.message -like "*jmap -histo*") `
    "the histogram refusal names the command that wrote the file"
Assert-That ($histogramRefusal.message -like "*GC.heap_dump*") `
    "the histogram refusal names the command that writes a real dump"
Assert-That ($histogramRefusal.message -like "*no object graph*") `
    "the histogram refusal says why a histogram cannot be analysed"

# Two bytes of the gzip magic is all it takes to be refused, and the file here is not even a valid
# stream: the detection is a header check, not a decompression attempt.
$gzipped = Join-Path $runtimeDir "heap-dump.hprof.gz"
[System.IO.File]::WriteAllBytes($gzipped, [byte[]](0x1F, 0x8B, 0x08, 0x00, 0x61, 0x62, 0x63, 0x64))
$refusedGzip = Send-Artifact $repositoryId $gzipped "heap-dump.hprof.gz" "HEAP_DUMP"
Assert-That ($refusedGzip.Status -eq 422) `
    "a gzipped dump is refused (was $($refusedGzip.Status))"
$gzipRefusal = $refusedGzip.Body | ConvertFrom-Json
Assert-That ($gzipRefusal.message -like "*gz=*") `
    "the compressed-dump refusal names the option that produced it"
Assert-That ($gzipRefusal.message -like "*does not decompress*") `
    "the refusal says RepoPilot will not decompress inside the job"
Assert-That ($gzipRefusal.message -like "*gzip -d*") `
    "the refusal names the command that uncompresses the file"

$threadText = Join-Path $runtimeDir "threads-as-heap.hprof"
[System.IO.File]::WriteAllText($threadText,
        "2026-10-19 12:00:00`nFull thread dump OpenJDK 64-Bit Server VM (21.0.5+11 mixed mode, sharing):`n`n",
        (New-Object System.Text.UTF8Encoding($false)))
$refusedThreadText = Send-Artifact $repositoryId $threadText "threads-as-heap.hprof" "HEAP_DUMP"
Assert-That ($refusedThreadText.Status -eq 422) `
    "a thread dump uploaded as a heap dump is refused (was $($refusedThreadText.Status))"
$threadTextRefusal = $refusedThreadText.Body | ConvertFrom-Json
Assert-That ($threadTextRefusal.message -like "*Full thread dump*") `
    "the refusal quotes what the file actually contains"
Assert-That ($threadTextRefusal.message -like "*THREAD_DUMP*") `
    "the refusal sends the operator to the reader that handles it"

<#
A real heap dump of the JVM under test, taken with the tool an operator would use.

A census is exact, so the numbers are checkable against the process that produced them: the backend
always holds more than a thousand objects and more than twenty types, and every heap of a running
Spring application has all three parts of the census. What is asserted below is the contract rather
than a particular count, because the counts change between runs and the contract does not.
#>
if ($null -eq $jcmdPath) {
    Write-Host "  SKIPPED: no jcmd in the JVM on the PATH, so no heap dump can be taken. The same" -ForegroundColor Yellow
    Write-Host "           assertions run unconditionally in HeapDumpAnalysisIntegrationTest." -ForegroundColor Yellow
    $skipped++
} else {
    $dumpable = @(& $jcmdPath -l | Where-Object { $_ -like "*repopilot-backend*" })
    if ($dumpable.Count -eq 0) {
        Write-Host "  SKIPPED: jcmd found nothing attachable running repopilot-backend." -ForegroundColor Yellow
        $skipped++
    } else {
        $backendPid = (@($dumpable[0] -split "\s+"))[0]
        $heapPath = Join-Path $runtimeDir "heap-dump.hprof"
        Remove-Item $heapPath -ErrorAction SilentlyContinue
        & $jcmdPath $backendPid "GC.heap_dump" $heapPath | Out-Null
        Assert-That (Test-Path $heapPath) "a heap dump of the backend was taken"
        $heapLength = 0
        if (Test-Path $heapPath) { $heapLength = (Get-Item $heapPath).Length }
        Write-Host ("  dump: {0} MB" -f [math]::Round($heapLength / 1MB, 1))
        Assert-That ($heapLength -gt 1024) "the dump has content ($heapLength bytes)"

        $heapUpload = Send-Artifact $repositoryId $heapPath "heap-dump.hprof" "HEAP_DUMP"
        Assert-That ($heapUpload.Status -eq 201) "a heap dump uploads (was $($heapUpload.Status))"
        $heapArtifact = $heapUpload.Body | ConvertFrom-Json
        Assert-That ($heapArtifact.artifactType -eq "HEAP_DUMP") "the artefact is recorded as a heap dump"
        Assert-That ($heapArtifact.supported -eq $true) "a heap dump is reported as readable"
        # Not a formality: the UI's warning is driven by this flag, and a heap dump is a literal copy
        # of the process's memory. A thread dump is deliberately false; this one must not become so.
        Assert-That ($heapArtifact.containsProcessMemory -eq $true) `
            "a heap dump is flagged as process memory"

        $heapAgain = Send-Artifact $repositoryId $heapPath "heap-dump-again.hprof" "HEAP_DUMP"
        $heapAgainArtifact = $heapAgain.Body | ConvertFrom-Json
        Assert-That ($heapAgainArtifact.id -eq $heapArtifact.id) `
            "re-uploading the same dump reuses the existing artefact"

        $heapJob = Post-Json "$api/repositories/$repositoryId/runtime/artifacts/$($heapArtifact.id)/analyze" "{}"
        Assert-That ($heapJob.jobType -eq "HEAP_ANALYSIS") `
            "the dump is analysed by the heap job, not by the JFR or thread-dump one"
        $heapFinal = Wait-ForJob $heapJob.id 600
        Assert-That ($heapFinal.status -eq "SUCCEEDED") `
            "the heap-dump analysis succeeded (was $($heapFinal.status))"
        Write-Host ("  summary: {0}" -f $heapFinal.resultSummary)
        Assert-That ($heapFinal.resultSummary -like "*heap dump*") "the run summary says what was read"
        Assert-That ($heapFinal.resultSummary -like "*recorded byte*") `
            "the run summary states that the bytes are recorded bytes"
        Assert-That ($heapFinal.resultSummary -like "*headers and class metadata excluded*") `
            "the run summary says what the byte figures leave out"
        Assert-That ($heapFinal.resultSummary -like "*no retained size computed*") `
            "the run summary refuses a retained size"
        Assert-That ($heapFinal.resultSummary -like "*one instant*") `
            "the run summary states that a dump describes one instant"
        Assert-That ($heapFinal.resultSummary -like "*no threshold applied*") `
            "the run summary states that no threshold was applied"
        Assert-That ($heapFinal.resultSummary -like "*no severity assigned*") `
            "the run summary states that no severity was assigned"

        # The newest runtime evidence is now the heap run, three readers into one repository.
        $heapSummary = Get-Json "$api/repositories/$repositoryId/runtime/summary"
        Assert-That ((Get-Prop $heapSummary "runId") -eq $heapFinal.analysisRunId) `
            "the runtime summary now points at the heap-dump run"
        Assert-That ((Get-Prop $heapSummary "artifactType") -eq "HEAP_DUMP") `
            "the summary names the kind of artefact the run read"
        $heapFamilies = @()
        $heapFamilyObject = Get-Prop $heapSummary "byMetricFamily"
        if ($null -ne $heapFamilyObject) {
            $heapFamilies = @($heapFamilyObject.PSObject.Properties | ForEach-Object { $_.Name })
        }
        foreach ($expectedFamily in @("objects", "instances", "arrays", "types", "roots", "classes")) {
            Assert-That ($heapFamilies -contains $expectedFamily) `
                "the dump's measurements include the $expectedFamily family"
        }
        Assert-That ($heapFamilies -notcontains "gc") "a heap-dump run does not report JFR families"
        Assert-That ($heapFamilies -notcontains "threads") "a heap-dump run does not report thread-dump families"

        $heapProvenance = Get-Prop $heapSummary "provenanceJson"
        Assert-That ($null -ne $heapProvenance) "the dump's header is stored with the run"
        $heapProvenanceText = $heapProvenance | ConvertTo-Json -Depth 10 -Compress
        Assert-That ((Get-Prop (Get-Prop $heapProvenance "environment") "formatVersion") -like "1.0.*") `
            "the provenance records the format version the header states"
        Assert-That ((Get-Prop (Get-Prop $heapProvenance "environment") "identifierSize") -gt 0) `
            "the provenance records the width of an object identifier"
        Assert-That ((Get-Prop (Get-Prop $heapProvenance "dump") "strings") -gt 0) `
            "the provenance records how many strings the file held"
        Assert-That ((Get-Prop (Get-Prop $heapProvenance "dump") "takenAt") -ne $null) `
            "the provenance records the instant the header states"
        Assert-That ((Get-Prop (Get-Prop $heapProvenance "heap") "objects") -gt 1000) `
            "the provenance records the census the measurements came from"
        Assert-That ((Get-Prop (Get-Prop $heapProvenance "produced") "retainedSizesComputed") -eq $false) `
            "the provenance states that no retained size was computed"
        Assert-That ((Get-Prop (Get-Prop $heapProvenance "produced") "thresholdsApplied") -eq $false) `
            "the provenance states that no threshold was applied"
        # The census is a shape, not the application's data. A type name belongs on the row derived
        # from it and nowhere else, so the whole provenance is checked rather than a field of it.
        Assert-That (-not $heapProvenanceText.Contains("java.lang.String")) `
            "the provenance carries no type names"

        $heapRows = @((Get-Json "$api/repositories/$repositoryId/runtime/measurements?metric=heapdump&size=500").items)
        Assert-That ($heapRows.Count -gt 0) "the dump produced measurements"
        Assert-That (@($heapRows | Where-Object { $_.metricKey -notlike "heapdump.*" }).Count -eq 0) `
            "every measurement the filter returns is the dump's own"
        Assert-That (@($heapRows | Where-Object { $_.metricKind -notin @("COUNTER", "GAUGE", "SIZE") }).Count -eq 0) `
            "every heap measurement is a count, a size or a gauge"
        Assert-That (@($heapRows | Where-Object { $_.unit -notin @("count", "bytes") }).Count -eq 0) `
            "every heap measurement is a count or a byte figure"
        Assert-That (@($heapRows | Where-Object { $_.aggregation -notin @("COUNT", "SUM", "MAX") }).Count -eq 0) `
            "every heap measurement is a count, a sum or a maximum"
        Assert-That (@($heapRows | Where-Object { (Get-Prop $_ "analysisRunId") -ne $heapFinal.analysisRunId }).Count -eq 0) `
            "every heap measurement is tied to the run that produced it"

        # The basis is the whole reason a byte row is allowed to exist. A row that says "1.7 MB"
        # without saying what was counted would read as occupied memory, or as a retained size.
        $heapByteRows = @($heapRows | Where-Object { $_.unit -eq "bytes" })
        Assert-That ($heapByteRows.Count -gt 0) "the dump reports byte figures ($($heapByteRows.Count))"
        Assert-That (@($heapByteRows | Where-Object { $_.attributesJson -notlike "*basis*" }).Count -eq 0) `
            "every byte figure carries the basis it was counted on"
        Assert-That (@($heapByteRows | Where-Object { $_.attributesJson -notlike "*not a retained size*" }).Count -eq 0) `
            "every byte figure states that it is not a retained size"
        Assert-That (@($heapByteRows | Where-Object { $_.attributesJson -notlike "*header*" }).Count -eq 0) `
            "every byte figure states that object headers are excluded"
        # Rows with no attributes are omitted-property rows: the backend leaves null fields out of its
        # JSON, so a plain count carries no attributesJson at all. Read through Get-Prop, which is what
        # the absence means.
        Assert-That (@($heapRows | Where-Object { (Get-Prop $_ "attributesJson") -like "*severity*" }).Count -eq 0) `
            "no heap measurement carries a severity"
        Assert-That (@($heapRows | Where-Object { (Get-Prop $_ "attributesJson") -like "*threshold*" }).Count -eq 0) `
            "no heap measurement carries a threshold"

        # The census is three parts of one whole, and each part has a count and a byte figure. If the
        # parts and the whole disagree, one of them is wrong and the other is not the answer.
        $heapCount = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.objects.count" })[0]
        $heapBytes = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.objects.bytes" })[0]
        $instanceCount = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.instances.count" })[0]
        $objectArrayCount = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.arrays.object.count" })[0]
        $primitiveCount = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.arrays.primitive.count" })[0]
        Assert-That (($instanceCount.value + $objectArrayCount.value + $primitiveCount.value) -eq $heapCount.value) `
            "the three parts of the census account for every object"
        $instanceBytes = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.instances.bytes" })[0]
        $objectArrayBytes = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.arrays.object.bytes" })[0]
        $primitiveBytes = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.arrays.primitive.bytes" })[0]
        Assert-That (($instanceBytes.value + $objectArrayBytes.value + $primitiveBytes.value) -eq $heapBytes.value) `
            "the three parts of the census account for every recorded byte"

        # The tables are cut at twenty and say so. A table that quietly showed twenty of nine hundred
        # types would be read as the whole heap.
        $observedTypes = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.types.bytes.observed" })[0]
        $reportedTypes = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.types.bytes.reported" })[0]
        Assert-That ($observedTypes.value -gt 20) `
            "the dump holds more types than the table lists ($($observedTypes.value))"
        Assert-That ($reportedTypes.value -eq 20) "the type table stops at the cap (TOP_N)"
        $typeByteRows = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.types.bytes" })
        Assert-That ($typeByteRows.Count -eq 20) "exactly twenty type rows are written"
        Assert-That (@($typeByteRows | Where-Object { [string]::IsNullOrWhiteSpace((Get-Prop $_ "scope")) }).Count -eq 0) `
            "every type row names the type it is about"
        Assert-That (@($typeByteRows | Where-Object { (Get-Prop $_ "attributesJson") -notlike "*shareBasis*" }).Count -eq 0) `
            "every type row says what its share is a share of"
        $typeObjectRows = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.types.objects" })
        Assert-That ($typeObjectRows.Count -eq 20) "the table by object count is written too"

        # Roots are where a collector starts, and the kinds are a fact about the JVM. The count of
        # kinds must add up to the count of roots, or one of the two numbers is not what it says.
        $rootCount = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.roots.count" })[0]
        $rootKindRows = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.roots.kind.count" })
        Assert-That ($rootKindRows.Count -gt 0) "the dump reports the kinds of root it found"
        $rootKindsTotal = 0
        $rootKindRows | ForEach-Object { $rootKindsTotal = $rootKindsTotal + [int]$_.value }
        Assert-That ($rootKindsTotal -eq [int]$rootCount.value) `
            "the root kinds account for every root ($rootKindsTotal of $($rootCount.value))"

        $largestObject = @($heapRows | Where-Object { $_.metricKey -eq "heapdump.object.bytes.max" })[0]
        Assert-That (-not [string]::IsNullOrWhiteSpace((Get-Prop $largestObject "scope"))) `
            "the largest single object is reported with its type"
        Assert-That ($largestObject.value -gt 0) "the largest single object has a size"

        # Three readers, one repository: the heap run is newer, and neither older run's rows moved.
        $jfrStillThere = Get-Json "$api/repositories/$repositoryId/runtime/measurements?runId=$runtimeRunId&size=1"
        Assert-That ($jfrStillThere.totalItems -eq $measurements.totalItems) `
            "a heap-dump run does not replace the recording's measurements"
        $heapRowsAgain = Get-Json "$api/repositories/$repositoryId/runtime/measurements?runId=$($heapFinal.analysisRunId)&size=1"
        Assert-That ($heapRowsAgain.totalItems -eq $heapRows.Count) `
            "a heap-dump run keeps its own measurements under its own run id"
    }
}

# ---------------------------------------------------------------- benchmarks
Write-Host "`n== runtime evidence (benchmarks) ==" -ForegroundColor Cyan

<#
The file is the two-fork JMH run recorded for the unit tests, uploaded unchanged. Its provenance is
corpus/benchmarks/README.md: JMH 1.37 on JDK 21.0.5, two forks of three iterations, and explicitly not
a performance claim. Asserting its exact figures here is the point — the reader's job is to repeat a
number it did not compute, so a live import must return the same number the file states.
#>
$benchmarkPath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../backend/src/test/resources/benchmarks/jmh-result-2-forks.json"))
$benchmarkArtifact = $null
if (-not (Test-Path $benchmarkPath)) {
    Write-Host "  SKIPPED: the recorded JMH fixture is not present at $benchmarkPath" -ForegroundColor Yellow
    $skipped++
} else {
    # The type is inferred from the extension here, and stated nowhere: a `.json` upload that needs a
    # query parameter to be recognised would mean the detection table is lying.
    $benchmarkUpload = Send-Artifact $repositoryId $benchmarkPath "jmh-result-2-forks.json" ""
    Assert-That ($benchmarkUpload.Status -eq 201) `
        "a JMH result file uploads (was $($benchmarkUpload.Status))"
    $benchmarkArtifact = $benchmarkUpload.Body | ConvertFrom-Json
    Assert-That ($benchmarkArtifact.artifactType -eq "BENCHMARK_RESULT") `
        "the type is inferred from the .json extension"
    Assert-That ($benchmarkArtifact.supported -eq $true) "a benchmark result is reported as readable"
    Assert-That ($benchmarkArtifact.containsProcessMemory -eq $false) `
        "a benchmark result is not process memory and is not flagged as such"
    Assert-That ((Get-Prop $benchmarkArtifact "purgedAt") -eq $null) `
        "a freshly uploaded artefact has not been purged"

    $benchmarkJob = Post-Json "$api/repositories/$repositoryId/runtime/artifacts/$($benchmarkArtifact.id)/analyze" "{}"
    Assert-That ($benchmarkJob.jobType -eq "BENCHMARK_IMPORT") `
        "a benchmark result is imported by its own job type"
    $benchmarkFinal = Wait-ForJob $benchmarkJob.id 180
    Assert-That ($benchmarkFinal.status -eq "SUCCEEDED") `
        "the benchmark import succeeded (was $($benchmarkFinal.status))"
    Write-Host ("  summary: {0}" -f $benchmarkFinal.resultSummary)
    Assert-That ($benchmarkFinal.resultSummary -like "*277 measurement(s) from 14 benchmark result entries*") `
        "the run read all 14 entries and produced 277 rows"
    Assert-That ($benchmarkFinal.resultSummary -like "*4 in throughput mode*") `
        "the summary counts the entries by mode"
    Assert-That ($benchmarkFinal.resultSummary -like "*nothing compared with anything*") `
        "the summary states that nothing was compared"
    Assert-That ($benchmarkFinal.resultSummary -like "*no threshold applied*") `
        "the summary states that no threshold was applied"
    Assert-That ($benchmarkFinal.resultSummary -like "*no severity assigned*") `
        "the summary states that no severity was assigned"

    $benchmarkSummary = Get-Json "$api/repositories/$repositoryId/runtime/summary"
    Assert-That ((Get-Prop $benchmarkSummary "runId") -eq $benchmarkFinal.analysisRunId) `
        "the runtime summary now points at the benchmark run"
    Assert-That ((Get-Prop $benchmarkSummary "artifactType") -eq "BENCHMARK_RESULT") `
        "the summary names the kind of artefact the run read"

    $benchmarkProvenance = Get-Prop $benchmarkSummary "provenanceJson"
    Assert-That ($null -ne $benchmarkProvenance) "the result file's own facts are stored with the run"
    $benchmarkProvenanceText = $benchmarkProvenance | ConvertTo-Json -Depth 10 -Compress
    Assert-That ((Get-Prop (Get-Prop $benchmarkProvenance "environment") "jmhVersion") -eq "1.37") `
        "the provenance records the harness version that wrote the file"
    Assert-That ((Get-Prop (Get-Prop $benchmarkProvenance "benchmark") "entries") -eq 14) `
        "the provenance records how many entries the file held"
    Assert-That ((Get-Prop (Get-Prop $benchmarkProvenance "benchmark") "jvmArgumentsRecorded") -eq 0) `
        "the provenance counts the JVM arguments rather than carrying them"
    Assert-That ((Get-Prop (Get-Prop $benchmarkProvenance "produced") "comparisonsMade") -eq $false) `
        "the provenance states that no comparison was made"
    Assert-That ((Get-Prop (Get-Prop $benchmarkProvenance "produced") "thresholdsApplied") -eq $false) `
        "the provenance states that no threshold was applied"

    $benchmarkPage = Get-Json "$api/repositories/$repositoryId/runtime/measurements?metric=benchmark&size=500"
    $benchmarkRows = @($benchmarkPage.items)
    Assert-That ($benchmarkPage.totalItems -eq 277) `
        "the API counts every row the run wrote ($($benchmarkPage.totalItems))"
    Assert-That ($benchmarkPage.totalItems -gt $benchmarkRows.Count) `
        "a run with more rows than one page states the total it sliced"
    Assert-That (@($benchmarkRows | Where-Object { $_.metricKey -notlike "benchmark.*" }).Count -eq 0) `
        "every measurement the filter returns is the benchmark reader's own"
    Assert-That (@($benchmarkRows | Where-Object { [string]::IsNullOrWhiteSpace($_.unit) }).Count -eq 0) `
        "every benchmark measurement states its unit"
    Assert-That (@($benchmarkRows | Where-Object { $_.aggregation -notin @("AS_REPORTED", "COUNT") }).Count -eq 0) `
        "no benchmark value claims a reduction RepoPilot did not perform"
    Assert-That (@($benchmarkRows | Where-Object { $_.metricKey -like "*baseline*" -or $_.metricKey -like "*delta*" }).Count -eq 0) `
        "no row names a baseline or a delta"

    # One score, exactly as the file states it: throughput, size=16, 10.359395201829896 ops/us. The
    # row must say who computed it and that nothing beside it confirms it.
    $throughputRows = @((Get-Json "$api/repositories/$repositoryId/runtime/measurements?metric=benchmark.throughput&size=300").items)
    $size16Scores = @($throughputRows | Where-Object {
            $_.metricKey -eq "benchmark.throughput.score" -and (Get-Prop $_ "scope") -like "*size=16*"
        })
    Assert-That ($size16Scores.Count -eq 2) "one throughput score per method at size 16"
    $mapLookup = @($size16Scores | Where-Object { (Get-Prop $_ "scope") -like "*mapLookup*" })[0]
    # Compared with a tolerance because ConvertFrom-Json yields a decimal for a value with this many
    # digits, and decimal-versus-double equality in PowerShell converts through a 15-digit string.
    Assert-That ([math]::Abs([double]$mapLookup.value - [double]10.359395201829896) -lt 1e-9) `
        "the score is the number the file states, not a recomputation ($($mapLookup.value))"
    Assert-That ($mapLookup.unit -eq "ops/us") "the score keeps the harness's own unit"
    Assert-That ($mapLookup.aggregation -eq "AS_REPORTED") "the score is recorded as quoted"
    Assert-That ((Get-Prop $mapLookup "sampleCount") -eq 6) `
        "the score states the six per-iteration values it was checked against"
    Assert-That ((Get-Prop $mapLookup "attributesJson") -like "*quoted from the benchmark result file*") `
        "the row says where the number came from"
    Assert-That ((Get-Prop $mapLookup "attributesJson") -like "*No threshold was applied*") `
        "the row repeats that no threshold was applied"

    $benchmarkRow = @($benchmarkRows | Where-Object { $_.metricKey -eq "benchmark.file.entries" })[0]
    Assert-That ($benchmarkRow.value -eq 14) "the file-level row counts the entries the file held"

    # The other shapes JMH writes are refused by name, at upload, before a job is spent.
    $csvPath = Join-Path $runtimeDir "results.csv"
    [System.IO.File]::WriteAllText($csvPath,
        "`"Benchmark`",`"Mode`",`"Threads`",`"Score`",`"Unit`"`n`"fixture.mapLookup`",thrpt,1,9.86,ops/us`n",
        (New-Object System.Text.UTF8Encoding($false)))
    $csvRefusal = Send-Artifact $repositoryId $csvPath "results.csv" "BENCHMARK_RESULT"
    Assert-That ($csvRefusal.Status -eq 422) `
        "the CSV output is refused (was $($csvRefusal.Status))"
    $csvError = $csvRefusal.Body | ConvertFrom-Json
    Assert-That ((Get-Prop $csvError "errorCode") -eq "UNSUPPORTED_INPUT") `
        "the CSV refusal carries a stable error code"
    Assert-That ((Get-Prop $csvError "message") -like "*-rf json*") `
        "the refusal names the command that produces a readable file"

    $objectPath = Join-Path $runtimeDir "one-result.json"
    [System.IO.File]::WriteAllText($objectPath, '{"benchmark":"fixture.mapLookup"}',
        (New-Object System.Text.UTF8Encoding($false)))
    $objectRefusal = Send-Artifact $repositoryId $objectPath "one-result.json" "BENCHMARK_RESULT"
    Assert-That ($objectRefusal.Status -eq 422) `
        "a single JSON object is refused (was $($objectRefusal.Status))"
    $objectError = $objectRefusal.Body | ConvertFrom-Json
    Assert-That ((Get-Prop $objectError "message") -like "*array*") `
        "the refusal says the file must be the array JMH writes"

    # An array that is not a result file is stored and then fails, because JSON has no header to check
    # and the shape is only knowable once it is parsed. The failure must say what was missing.
    $wrongArrayPath = Join-Path $runtimeDir "not-a-result.json"
    [System.IO.File]::WriteAllText($wrongArrayPath, '[{"hello":"world"}]',
        (New-Object System.Text.UTF8Encoding($false)))
    $wrongArrayUpload = Send-Artifact $repositoryId $wrongArrayPath "not-a-result.json" "BENCHMARK_RESULT"
    Assert-That ($wrongArrayUpload.Status -eq 201) `
        "a JSON array is stored even when it is not a result file"
    $wrongArrayArtifact = $wrongArrayUpload.Body | ConvertFrom-Json
    $wrongArrayJob = Wait-ForJob (Post-Json `
            "$api/repositories/$repositoryId/runtime/artifacts/$($wrongArrayArtifact.id)/analyze" "{}").id 120
    Assert-That ($wrongArrayJob.status -eq "FAILED") `
        "the import of a non-result array fails rather than storing nothing (was $($wrongArrayJob.status))"
    Assert-That ((Get-Prop $wrongArrayJob "errorMessage") -like '*has no*benchmark*field*') `
        "the failure names the field the file is missing"
}

# ---------------------------------------------------------------- artefact retention
Write-Host "`n== artefact retention ==" -ForegroundColor Cyan

# What a purge would remove is computed against the clock on every read, so a fresh server has
# candidates only if something is genuinely older than the window. Nothing here is: the point of these
# checks is that the plan says so instead of showing an empty list that could mean anything, and that a
# window of zero is refused rather than read as "everything". The deletion path itself — file gone,
# mark set, measurements kept, re-upload restores — needs an artefact older than the window, so it is
# exercised in ArtifactRetentionIntegrationTest, which backdates created_at rather than waiting 30 days.
if ($null -eq $benchmarkArtifact) {
    Write-Host "  SKIPPED: no benchmark artefact was uploaded, so retention has nothing to describe" -ForegroundColor Yellow
    $skipped++
} else {
    $retentionPlan = Get-Json "$api/repositories/$repositoryId/runtime/retention"
    Assert-That ($retentionPlan.retentionDays -gt 0) `
        "the plan names the configured window in days ($($retentionPlan.retentionDays))"
    Assert-That ($retentionPlan.automatic -eq $true) "automatic pruning is on under the default window"
    Assert-That ($retentionPlan.storedArtefacts -ge 4) `
        "the plan counts the files this repository holds ($($retentionPlan.storedArtefacts))"
    Assert-That ($retentionPlan.storedBytes -gt 0) "the plan states how many bytes they occupy"
    Assert-That ($retentionPlan.purgedArtefacts -eq 0) "nothing has been purged on a fresh repository"
    Assert-That (@($retentionPlan.candidates).Count -eq 0) `
        "nothing on a fresh repository is past the window"
    Assert-That ($null -ne (Get-Prop $retentionPlan "cutoff")) `
        "the plan states the cutoff it was computed against"

    # A window of zero switches pruning off: the plan reports the repository and names no candidate,
    # rather than treating "no window" as "no age limit".
    $offPlan = Get-Json "$api/repositories/$repositoryId/runtime/retention?days=0"
    Assert-That ($offPlan.automatic -eq $false) "a zero window reports pruning as off"
    Assert-That (@($offPlan.candidates).Count -eq 0) "a zero window names no candidate"

    # And a purge asked for with no window is refused, in words that say which setting is off. It is a
    # 400 rather than an empty success: "delete everything" is not a default anyone should inherit.
    $zeroPurge = Post-ExpectingFailure "$api/repositories/$repositoryId/runtime/retention/purge" '{"olderThanDays":0}'
    Assert-That ($zeroPurge.Status -eq 400) `
        "a purge with a zero-day window is a bad request (was $($zeroPurge.Status))"
    $zeroError = $zeroPurge.Body | ConvertFrom-Json
    Assert-That ((Get-Prop $zeroError "errorCode") -eq "VALIDATION_FAILED") `
        "the refusal carries a stable error code"
    Assert-That ((Get-Prop $zeroError "message") -like "*by omission*") `
        "the refusal says it will not read a missing window as everything"

    # A pass that runs is a job, produces no analysis run, and states what it did. Ten years removes
    # nothing from a server started minutes ago, and the summary has to say that rather than "purged 0".
    $retentionJob = Post-Json "$api/repositories/$repositoryId/runtime/retention/purge" '{"olderThanDays":3650}'
    Assert-That ($retentionJob.jobType -eq "ARTIFACT_RETENTION") `
        "a purge is queued as a maintenance job"
    $retentionFinal = Wait-ForJob $retentionJob.id 120
    Assert-That ($retentionFinal.status -eq "SUCCEEDED") `
        "the maintenance pass succeeded (was $($retentionFinal.status))"
    Assert-That ($null -eq (Get-Prop $retentionFinal "analysisRunId")) `
        "a maintenance pass produces no analysis run"
    Write-Host ("  summary: {0}" -f $retentionFinal.resultSummary)
    Assert-That ($retentionFinal.resultSummary -like "*Nothing to purge*") `
        "the pass says nothing was old enough"

    $retentionAfter = Get-Json "$api/repositories/$repositoryId/runtime/retention"
    Assert-That ($retentionAfter.storedArtefacts -eq $retentionPlan.storedArtefacts) `
        "a pass that purges nothing leaves the stored count alone"
    $benchmarkAfter = Get-Json "$api/repositories/$repositoryId/runtime/artifacts/$($benchmarkArtifact.id)"
    Assert-That ((Get-Prop $benchmarkAfter "purgedAt") -eq $null) `
        "the artefact is still stored and still analysable"
}

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

# ---------------------------------------------------------------- investigations
Write-Host "`n== AI investigations ==" -ForegroundColor Cyan
if (-not $overview.ai.usable) {
    # The section needs a provider. Without one the API refuses with 503, which is checked
    # below only if a provider exists; running the whole section against a server with no model
    # would report failures for a configuration choice rather than a defect.
    Write-Host "  SKIPPED: no AI provider is usable on this server, so no investigation can run." -ForegroundColor Yellow
    Write-Host "           Restart with REPOPILOT_AI_PROVIDER=deterministic (or a reachable" -ForegroundColor Yellow
    Write-Host "           Ollama) to exercise this section. The offline path is asserted in" -ForegroundColor Yellow
    Write-Host "           InvestigationProviderUnavailableIntegrationTest." -ForegroundColor Yellow
    $skipped++
} else {
    Write-Host ("  provider: {0} (usable={1})" -f $overview.ai.providerId, $overview.ai.usable)

    # The fixture carries one deliberate instance of every built-in rule, including the empty
    # catch, so the question below has something to retrieve and the answer has something to cite.
    $askBody = @{ question = "Why does the empty catch block in the fixture hide failures?" } |
        ConvertTo-Json -Compress
    $submission = Post-Json "$api/repositories/$($fixture.id)/investigations" $askBody
    Assert-That (-not [string]::IsNullOrWhiteSpace($submission.investigationId)) `
        "asking a question returns an investigation id"
    Assert-That (-not [string]::IsNullOrWhiteSpace($submission.jobId)) `
        "asking a question returns a job id, so the client can follow it"
    Assert-That ($submission.status -eq "QUEUED") "the investigation starts QUEUED"

    $investigationId = $submission.investigationId
    $pending = Get-Json "$api/repositories/$($fixture.id)/investigations/$investigationId"
    Assert-That (@("QUEUED", "RUNNING") -contains $pending.status) `
        "the investigation is readable before the job finishes"
    Assert-That ((Get-Prop $pending "answer") -eq $null) `
        "an unfinished investigation has no answer, rather than an empty one"

    $investigationJob = Wait-ForJob $submission.jobId 240
    Assert-That ($investigationJob.status -eq "SUCCEEDED") `
        "the investigation job succeeded (was: $($investigationJob.status))"
    Write-Host ("  summary: {0}" -f $investigationJob.resultSummary)

    $detail = Get-Json "$api/repositories/$($fixture.id)/investigations/$investigationId"
    $claims = @($detail.claims)
    $evidence = @($detail.evidence)

    Assert-That ($detail.status -eq "SUCCEEDED") "the investigation is SUCCEEDED"
    Assert-That ($detail.grounded -eq $true) "the answer is grounded"
    Assert-That ($claims.Count -gt 0) "the answer has claims to check"
    Assert-That ($evidence.Count -gt 0) "the prompt was built from evidence"
    Assert-That ([int] $detail.evidenceCount -eq $evidence.Count) `
        "the recorded evidence count matches the evidence rows"
    # The summary is a sentence a person reads, so it has to name the count it counted.
    Assert-That ([string] $detail.retrievalSummary `
            -match ("^" + [int] $detail.evidenceCount + " item\(s\) of evidence")) `
        "the retrieval summary states how many items it counted"
    Assert-That ([int] $detail.verifiedClaims -eq $claims.Count) `
        "every claim is VERIFIED (verified=$($detail.verifiedClaims) of $($claims.Count))"
    Assert-That ([int] $detail.unsupportedClaims -eq 0) "no claim is unsupported"
    Assert-That ([int] $detail.partialClaims -eq 0) "no claim is partly supported"
    Assert-That (@($claims | Where-Object { $_.verificationStatus -ne "VERIFIED" }).Count -eq 0) `
        "no claim carries a verdict other than VERIFIED"

    Assert-That ((Get-Prop $detail "promptSha256").Length -eq 64) `
        "the prompt SHA-256 is recorded (64 hex characters)"
    Assert-That ([int] $detail.promptChars -gt 0) "the prompt size is recorded"
    Assert-That ([int] $detail.contextChars -gt 0) "the evidence size within the prompt is recorded"
    Assert-That ((Get-Prop $detail "completedAt") -ne $null) "the investigation records when it finished"
    Assert-That ((Get-Prop $detail "modelProvider") -eq "deterministic") `
        "the provider that answered is recorded"

    # The deterministic extractor is a test double. If it is in use, the answer must say so: a
    # reader has to be able to tell this apart from a model's answer.
    $limitations = [string] (Get-Prop $detail "limitations")
    Assert-That ($limitations -like "*No language model was called*") `
        "the extractor states that no model was called"

    # Evidence discipline: keys are numbered, every key was cited, and the interesting ones carry a
    # location that was re-read from disk. No excerpt text may appear in the payload at all.
    Assert-That (@($evidence | Where-Object { $_.key -notmatch '^E\d+$' }).Count -eq 0) `
        "every evidence key is numbered En"
    Assert-That (@($evidence | Where-Object { -not $_.cited }).Count -eq 0) `
        "every item in the prompt was cited"
    Assert-That (@($evidence | Where-Object { $_.kind -eq "FINDING" }).Count -gt 0) `
        "retrieval reached the static findings, not only the symbol table"
    $located = @($evidence | Where-Object { (Get-Prop $_ "path") -ne $null })
    Assert-That ($located.Count -gt 0) "at least one item carries a file location"
    Assert-That (@($located | Where-Object { $_.sourceStatus -eq "CONFIRMED" }).Count -gt 0) `
        "at least one located item was re-read and confirmed"
    Assert-That (@($evidence | Where-Object { (Get-Prop $_ "excerptChars") -gt 0 }).Count -gt 0) `
        "the excerpt length is recorded without the excerpt"
    $fields = @($evidence[0].PSObject.Properties.Name)
    Assert-That ($fields -notcontains "excerpt") "no response field carries the excerpt text"
    Assert-That ($fields -notcontains "excerptSha256") `
        "the excerpt hash is not exposed through the API"

    # Diagnostics say what the investigation could not do. The extractor leaves every conclusion
    # to the reader, so it always has one thing to say.
    Assert-That (@($detail.diagnostics.unanswered).Count -gt 0) `
        "the investigation records what it could not answer"

    # The list endpoint agrees with the detail endpoint.
    $investigationPage = Get-Json "$api/repositories/$($fixture.id)/investigations?size=50"
    $listed = @($investigationPage.items | Where-Object { $_.id -eq $investigationId })
    Assert-That ($listed.Count -eq 1) "the investigation appears once in the repository's list"
    Assert-That ($listed[0].grounded -eq $true) "the list reports the same grounding verdict"

    # A repository's identifier cannot be used to read another repository's investigation.
    $crossRepo = Get-ExpectingFailure "$api/repositories/$repositoryId/investigations/$investigationId"
    Assert-That ($crossRepo.Status -eq 404) `
        "an investigation is scoped to its repository (was $($crossRepo.Status))"

    # A question made only of stop words names nothing; it is refused before a job exists.
    $emptyAsk = Post-ExpectingFailure "$api/repositories/$($fixture.id)/investigations" `
        (@{ question = "how does the code work" } | ConvertTo-Json -Compress)
    Assert-That ($emptyAsk.Status -eq 400) `
        "a question with nothing to search for is refused (was $($emptyAsk.Status))"
    Assert-That ([string] $emptyAsk.Body -like "*names nothing to search for*") `
        "the refusal says the question names nothing"

    # Cancellation: a queued investigation can be withdrawn, and a withdrawn one cannot be
    # cancelled twice. The second answer is 409, not a second acceptance.
    $cancellable = Post-Json "$api/repositories/$($fixture.id)/investigations" $askBody
    Assert-That ($cancellable.status -eq "QUEUED") "a second question is queued before it is cancelled"
    Post-Json "$api/repositories/$($fixture.id)/investigations/$($cancellable.investigationId)/cancel" "{}" |
        Out-Null
    $cancelled = Get-Json "$api/repositories/$($fixture.id)/investigations/$($cancellable.investigationId)"
    Assert-That ($cancelled.status -eq "CANCELLED") `
        "a cancelled investigation is recorded as CANCELLED, not left queued"
    Assert-That ((Get-Prop $cancelled "answer") -eq $null) `
        "a cancelled investigation has no answer"
    $cancelAgain = Post-ExpectingFailure `
        "$api/repositories/$($fixture.id)/investigations/$($cancellable.investigationId)/cancel" "{}"
    Assert-That ($cancelAgain.Status -eq 409) `
        "cancelling a finished investigation is a conflict (was $($cancelAgain.Status))"

    # The whole surface is behind the bearer token: no token, no evidence about the repository.
    # A bare HttpClient is used rather than Invoke-WebRequest's error record, which differs between
    # PowerShell releases.
    $anonymous = New-Object System.Net.Http.HttpClient
    $anonymousResponse = $anonymous.GetAsync("$api/repositories/$($fixture.id)/investigations").Result
    $unauthStatus = [int] $anonymousResponse.StatusCode
    $anonymousResponse.Dispose()
    $anonymous.Dispose()
    Assert-That ($unauthStatus -eq 401) `
        "the investigation list requires the bearer token (was $unauthStatus)"
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
