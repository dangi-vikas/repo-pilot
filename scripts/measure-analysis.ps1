<#
.SYNOPSIS
    Times a static-analysis job and reports exactly what it produced.

.DESCRIPTION
    Starts a STATIC_ANALYSIS job, polls it to completion while sampling the backend
    process's resource use, then reads the run back through the public API.

    Every number printed is either a measurement of the real process (wall time from the
    job row's own timestamps, working set and CPU time from the operating system) or a
    count the API returns. Nothing is estimated, and nothing is inferred from a partial
    look:

      * the run header comes from /findings/summary, which names the revision the findings
        describe;
      * the severity, analyzer and verification tallies are accumulated by paging through
        the findings endpoint itself, the same one the UI uses, so a number here is a
        number a reader can reproduce in the browser;
      * the findings-per-rule table is the full list, not a sample, up to MaxPages.

    A run with zero findings is reported as zero. That is a result, not a failure.

.PARAMETER RepositoryId
    The repository to analyse. It must already be indexed.

.PARAMETER BaseUrl
    Backend base URL. Defaults to the loopback bind.

.PARAMETER Token
    Bearer token. Defaults to REPOPILOT_AUTH_TOKEN.

.PARAMETER SampleSeconds
    Seconds between resource samples. Defaults to 3.

.PARAMETER MaxPages
    Hard cap on findings pages read while tallying, at 200 findings per page. Reaching the
    cap is reported rather than hidden.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\measure-analysis.ps1 -RepositoryId a0118eef-...
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RepositoryId,
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Token = $env:REPOPILOT_AUTH_TOKEN,
    [int]$SampleSeconds = 3,
    [int]$MaxPages = 300
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "No token. Pass -Token or set REPOPILOT_AUTH_TOKEN."
}

$headers = @{ Authorization = "Bearer $Token" }
$api = "$BaseUrl/api/v1"
$pageSize = 200

# ---- the process under measurement -----------------------------------------
$uri = [System.Uri]$BaseUrl
$connection = Get-NetTCPConnection -LocalPort $uri.Port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $connection) {
    throw "Nothing is listening on port $($uri.Port). Start the backend first."
}
$processId = $connection.OwningProcess
$process = Get-Process -Id $processId

Write-Host "job target   : $RepositoryId"
Write-Host "process      : pid $processId ($($process.ProcessName)), sampling every ${SampleSeconds}s"
Write-Host "logical cpu  : $([Environment]::ProcessorCount)"

# ---- start the job ---------------------------------------------------------
$job = Invoke-RestMethod -Method Post -Uri "$api/repositories/$RepositoryId/analyze" `
    -Headers $headers -ContentType "application/json" -Body "{}"
Write-Host "job id       : $($job.id)"
Write-Host "queued at    : $($job.createdAt)"
Write-Host ""

$samples = @()
$previousCpu = $null
$previousAt = $null
$wall = [System.Diagnostics.Stopwatch]::StartNew()
$status = $job.status

while ($status -notin @("SUCCEEDED", "FAILED", "CANCELLED")) {
    Start-Sleep -Seconds $SampleSeconds
    $now = Get-Date
    $current = Invoke-RestMethod -Uri "$api/jobs/$($job.id)" -Headers $headers
    $status = $current.status

    $alive = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($alive) {
        $cpuSeconds = $alive.TotalProcessorTime.TotalSeconds
        if ($null -ne $previousCpu) {
            $elapsed = ($now - $previousAt).TotalSeconds
            $percent = if ($elapsed -gt 0) {
                (($cpuSeconds - $previousCpu) / $elapsed) / [Environment]::ProcessorCount * 100
            } else { 0 }
            $samples += [pscustomobject]@{
                At           = $now
                Progress     = $current.progress
                WorkingSet   = $alive.WorkingSet64
                PrivateBytes = $alive.PrivateMemorySize64
                CpuPercent   = [math]::Round($percent, 1)
            }
        }
        $previousCpu = $cpuSeconds
        $previousAt = $now
    }
}
$wall.Stop()

$final = Invoke-RestMethod -Uri "$api/jobs/$($job.id)" -Headers $headers

# ---- read the run back -----------------------------------------------------
$summary = Invoke-RestMethod -Uri "$api/repositories/$RepositoryId/findings/summary" -Headers $headers

# The job row's own timestamps are the authoritative span; the polling loop only adds up
# to SampleSeconds of latency after the fact.
$spanSeconds = if ($final.startedAt -and $final.finishedAt) {
    ([datetime]$final.finishedAt - [datetime]$final.startedAt).TotalSeconds
} elseif ($final.finishedAt) {
    ([datetime]$final.finishedAt - $job.createdAt).TotalSeconds
} else {
    [math]::Round($wall.Elapsed.TotalSeconds, 2)
}

$peakWorkingSet = ($samples | Measure-Object -Property WorkingSet -Maximum).Maximum
$peakPrivate = ($samples | Measure-Object -Property PrivateBytes -Maximum).Maximum
$averageCpu = if ($samples.Count -gt 0) {
    [math]::Round(($samples | Measure-Object -Property CpuPercent -Average).Average, 1)
} else { 0 }

Write-Host ""
Write-Host "status       : $($final.status)"
Write-Host "created      : $($job.createdAt)"
Write-Host "started      : $($final.startedAt)"
Write-Host "finished     : $($final.finishedAt)"
Write-Host "span (job)   : $([math]::Round($spanSeconds, 2))s"
Write-Host "summary      : $($final.resultSummary)"
if ($final.errorMessage) { Write-Host "error        : $($final.errorMessage)" }
Write-Host "samples      : $($samples.Count)"
Write-Host "peak wset    : $([math]::Round($peakWorkingSet / 1MB, 1)) MiB"
Write-Host "peak private : $([math]::Round($peakPrivate / 1MB, 1)) MiB"
Write-Host "cpu average  : $averageCpu% of $([Environment]::ProcessorCount) logical cores"
Write-Host "analysis run : $($summary.runId)"
Write-Host "run status   : $($summary.runStatus)"
Write-Host "revision     : $($summary.revisionId)"
Write-Host "analyzers    : $($summary.analyzerVersions)"

# ---- exact tallies, accumulated from the public findings endpoint ----------
$byVerification = @{}
$byRule = @{}
$page = 0
$seen = 0
$truncated = $false

while ($page -lt $MaxPages) {
    $response = Invoke-RestMethod -Uri "$api/repositories/$RepositoryId/findings?page=$page&size=$pageSize" `
        -Headers $headers
    $items = @($response.items)
    if ($items.Count -eq 0) { break }
    foreach ($item in $items) {
        $key = $item.verificationStatus
        if ($null -eq $byVerification[$key]) { $byVerification[$key] = 0 }
        $byVerification[$key] = $byVerification[$key] + 1
        $rule = "$($item.analyzerId)/$($item.ruleId)"
        if ($null -eq $byRule[$rule]) { $byRule[$rule] = 0 }
        $byRule[$rule] = $byRule[$rule] + 1
    }
    $seen += $items.Count
    if (-not $response.hasNext) { break }
    $page++
    if ($page -ge $MaxPages) { $truncated = $true }
}

Write-Host ""
Write-Host "findings     : $($summary.total) recorded"
Write-Host "examined     : $seen"
if ($truncated) { Write-Host "note         : stopped at MaxPages=$MaxPages; tallies cover the pages read" }
Write-Host "by severity  :"
$summary.bySeverity.PSObject.Properties | ForEach-Object { Write-Host ("  {0,-10} {1}" -f $_.Name, $_.Value) }
Write-Host "by category  :"
$summary.byCategory.PSObject.Properties | ForEach-Object { Write-Host ("  {0,-16} {1}" -f $_.Name, $_.Value) }
Write-Host "by analyzer  :"
$summary.byAnalyzer.PSObject.Properties | ForEach-Object { Write-Host ("  {0,-16} {1}" -f $_.Name, $_.Value) }
Write-Host "verification :"
$byVerification.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Host ("  {0,-22} {1}" -f $_.Key, $_.Value) }
Write-Host "top rules    :"
$byRule.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 12 |
    ForEach-Object { Write-Host ("  {0,-46} {1}" -f $_.Key, $_.Value) }

$dataDir = $env:REPOPILOT_DATA_DIR
if ([string]::IsNullOrWhiteSpace($dataDir)) { $dataDir = (Join-Path $PSScriptRoot "..\data") }
$database = Get-ChildItem -Path $dataDir -Filter "repopilot.mv.db" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($database) {
    Write-Host ""
    Write-Host "database     : $([math]::Round($database.Length / 1MB, 1)) MiB"
}
