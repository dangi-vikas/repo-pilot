<#
.SYNOPSIS
    End-to-end smoke test for a running RepoPilot backend.

.DESCRIPTION
    Exercises the whole vertical slice against a live server: readiness, workspace
    listing, repository registration, indexing, job polling, and every read endpoint
    the UI depends on (coverage, files, mappings, symbols, graph, overview).

    It asserts the identity contract as it goes: a create endpoint that returns a
    resource must return an id. That is not a formality — RepoPilot's HTTP layer
    serialises the entity it just created, so a null id would leave a client with
    nothing to poll (this happened once; see the identity notes in
    AbstractAuditedEntity).

.PARAMETER BaseUrl
    Backend root. Defaults to loopback on port 8080.

.PARAMETER Token
    Bearer token. Defaults to $env:REPOPILOT_AUTH_TOKEN.

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
    [int]    $ReadyTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "No bearer token. Set REPOPILOT_AUTH_TOKEN or pass -Token."
}

$api = "$BaseUrl/api/v1"
$headers = @{ Authorization = "Bearer $Token" }
$jsonHeaders = @{ Authorization = "Bearer $Token"; "Content-Type" = "application/json" }

$failures = New-Object System.Collections.Generic.List[string]

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

# ---------------------------------------------------------------- summary
Write-Host "`n== summary ==" -ForegroundColor Cyan
if ($failures.Count -eq 0) {
    Write-Host "All checks passed." -ForegroundColor Green
    exit 0
} else {
    Write-Host ("{0} check(s) failed:" -f $failures.Count) -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
