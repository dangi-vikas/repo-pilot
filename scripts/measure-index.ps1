<#
.SYNOPSIS
    Times a repository index job and samples the backend process's resource use.

.DESCRIPTION
    Starts a REPOSITORY_INDEX job, polls it to completion, and samples the Java process
    that owns the listening port for its working set and CPU time once per SampleSeconds.

    The numbers this prints are measurements of the real process, not estimates:

      * wall time comes from the job row's own created/started/finished timestamps;
      * throughput is files divided by that wall time;
      * peak working set and peak private bytes are read from the operating system
        (Get-Process), not reported by the application about itself;
      * CPU averages come from deltas of TotalProcessorTime, so they include garbage
        collection and the persistence thread pool, not just the parsing loop.

    A measurement that cannot be reproduced is an anecdote, so every input the run
    depended on is printed with it.

.PARAMETER RepositoryId
    The repository to re-index. Must already be registered.

.PARAMETER BaseUrl
    Backend base URL. Defaults to the loopback bind.

.PARAMETER Token
    Bearer token. Defaults to REPOPILOT_AUTH_TOKEN.

.PARAMETER SampleSeconds
    Seconds between resource samples. Defaults to 3.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\measure-index.ps1 -RepositoryId e0ff0bd6-...
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RepositoryId,
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [string]$Token = $env:REPOPILOT_AUTH_TOKEN,
    [int]$SampleSeconds = 3
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($Token)) {
    throw "No token. Pass -Token or set REPOPILOT_AUTH_TOKEN."
}

$headers = @{ Authorization = "Bearer $Token" }
$api = "$BaseUrl/api/v1"

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
$job = Invoke-RestMethod -Method Post -Uri "$api/repositories/$RepositoryId/index" `
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
                At          = $now
                Progress    = $current.progress
                WorkingSet  = $alive.WorkingSet64
                PrivateBytes = $alive.PrivateMemorySize64
                CpuPercent  = [math]::Round($percent, 1)
            }
        }
        $previousCpu = $cpuSeconds
        $previousAt = $now
    }
}
$wall.Stop()

$final = Invoke-RestMethod -Uri "$api/jobs/$($job.id)" -Headers $headers
$coverage = Invoke-RestMethod -Uri "$api/repositories/$RepositoryId/coverage" -Headers $headers

# The job row's own timestamps are the authoritative span; the polling loop only adds
# up to SampleSeconds of latency after the fact.
$spanSeconds = if ($final.startedAt -and $final.finishedAt) {
    ([datetime]$final.finishedAt - [datetime]$final.startedAt).TotalSeconds
} elseif ($final.finishedAt) {
    ([datetime]$final.finishedAt - [datetime]$job.createdAt).TotalSeconds
} else {
    [math]::Round($wall.Elapsed.TotalSeconds, 2)
}

$peakWorkingSet = ($samples | Measure-Object -Property WorkingSet -Maximum).Maximum
$peakPrivate = ($samples | Measure-Object -Property PrivateBytes -Maximum).Maximum
$averageCpu = if ($samples.Count -gt 0) {
    [math]::Round(($samples | Measure-Object -Property CpuPercent -Average).Average, 1)
} else { 0 }
$filesIndexed = $coverage.totalFiles

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
if ($spanSeconds -gt 0 -and $filesIndexed -gt 0) {
    Write-Host "throughput   : $([math]::Round($filesIndexed / $spanSeconds, 2)) files/s"
    Write-Host "per file     : $([math]::Round($spanSeconds * 1000 / $filesIndexed, 2)) ms/file"
}
Write-Host ""
Write-Host "coverage     :"
$coverage | ConvertTo-Json -Depth 4
