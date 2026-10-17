<#
.SYNOPSIS
    Generates the RepoPilot benchmark corpus.

.DESCRIPTION
    Writes a deterministic, synthetically generated Java repository. The point is
    scale, not realism: RepoPilot's indexing pipeline is bounded by file count,
    symbol count and edge count, and a corpus that produces those in a known
    quantity is what makes a measurement repeatable.

    The generated corpus deliberately contains more than parseable Java:

      * `problems/` holds a syntactically broken file, so the run has to report
        PARSED_WITH_PROBLEMS rather than pretending the scan was clean.
      * `docs/` and `resources/` hold non-Java files, which must be counted as
        excluded rather than silently dropped.
      * `config/` holds filenames that look like credentials. The secret filter
        has to exclude them before any read, and coverage has to say so.

    A corpus that only contains well-formed Java would let a broken scanner still
    look correct, because the excluded and failed counts would both be zero.

.PARAMETER Packages
    Number of packages to generate.

.PARAMETER TypesPerPackage
    Number of types generated in each package.

.PARAMETER MethodsPerType
    Number of methods generated in each class-like type.

.PARAMETER Seed
    Random seed. The same seed always produces byte-identical output.

.PARAMETER OutDir
    Destination directory. Removed and recreated.

.EXAMPLE
    ./scripts/generate-corpus.ps1 -Packages 100 -TypesPerPackage 6 -MethodsPerType 6

    Generates 600 Java types plus the non-Java fixtures. This is the shape used
    for the recorded measurements in corpus/RESULTS.md.
#>
[CmdletBinding()]
param(
    [int] $Packages = 100,
    [int] $TypesPerPackage = 6,
    [int] $MethodsPerType = 6,
    [int] $Seed = 20261017,
    # Resolved inside the body: $PSScriptRoot is not populated when a parameter
    # default is evaluated on Windows PowerShell 5.1.
    [string] $OutDir = ''
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrEmpty($OutDir)) {
    $OutDir = Join-Path $PSScriptRoot '..\corpus\generated\jdk21-synthetic'
}
$root = [System.IO.Path]::GetFullPath($OutDir)

if (Test-Path $root) {
    Remove-Item -Recurse -Force $root
}

$javaRoot = Join-Path $root 'src\main\java'
New-Item -ItemType Directory -Force -Path $javaRoot | Out-Null

$random = [System.Random]::new($Seed)

$adjectives = @('Rapid', 'Legacy', 'Primary', 'Async', 'Batched', 'Cached', 'Audited', 'Draft')
$nouns = @('Order', 'Invoice', 'Ledger', 'Shipment', 'Payment', 'Refund', 'Quote', 'Catalog')
$verbs = @('create', 'apply', 'validate', 'enrich', 'publish', 'archive', 'reconcile', 'summarise')

function New-Record([string] $packageName, [string] $typeName) {
    @"
package $packageName;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * $typeName. Immutable value carrier for the generated corpus.
 */
public record $typeName(
        String id,
        BigDecimal amount,
        Instant createdAt) {

    public boolean isSettled() {
        return amount != null && amount.signum() >= 0;
    }
}
"@
}

function New-Enum([string] $packageName, [string] $typeName) {
    # Uses the seeded generator on purpose: Get-Random would draw from a different
    # RNG and the corpus would no longer be reproducible from the seed.
    $all = @('DRAFT', 'OPEN', 'PAID', 'CANCELLED', 'ARCHIVED')
    $count = $random.Next(3, 6)
    $constants = $all[0..($count - 1)]
    @"
package $packageName;

/**
 * $typeName. Lifecycle states for the generated corpus.
 */
public enum $typeName {
    $($constants -join ",`n    ")
}
"@
}

function New-Service([string] $packageName,
                     [string] $typeName,
                     [string] $recordName,
                     [string] $enumName,
                     [string] $repositoryName,
                     [string[]] $methods) {
    $builder = [System.Text.StringBuilder]::new()
    [void] $builder.AppendLine("package $packageName;")
    [void] $builder.AppendLine()
    [void] $builder.AppendLine('import java.math.BigDecimal;')
    [void] $builder.AppendLine('import java.time.Instant;')
    [void] $builder.AppendLine('import java.util.ArrayList;')
    [void] $builder.AppendLine('import java.util.HashMap;')
    [void] $builder.AppendLine('import java.util.List;')
    [void] $builder.AppendLine('import java.util.Map;')
    [void] $builder.AppendLine('import org.springframework.stereotype.Service;')
    # A cross-package import. RepoPilot records the simple name and does not track
    # imports, so the edge for SharedRegistry has to stay INFERRED rather than be
    # claimed as a proof.
    [void] $builder.AppendLine('import com.example.shared.SharedRegistry;')
    [void] $builder.AppendLine()
    [void] $builder.AppendLine('/**')
    [void] $builder.AppendLine(" * $typeName. Generated service.")
    [void] $builder.AppendLine(' */')
    [void] $builder.AppendLine('@Service')
    [void] $builder.AppendLine("public class $typeName {")
    [void] $builder.AppendLine()
    [void] $builder.AppendLine('    private final SharedRegistry registry;')
    # Same package as this file, so this reference is provable.
    [void] $builder.AppendLine("    private final $repositoryName repository;")
    [void] $builder.AppendLine("    private final Map<String, $recordName> cache = new HashMap<>();")
    [void] $builder.AppendLine()
    [void] $builder.AppendLine("    public $typeName(SharedRegistry registry, $repositoryName repository) {")
    [void] $builder.AppendLine('        this.registry = registry;')
    [void] $builder.AppendLine('        this.repository = repository;')
    [void] $builder.AppendLine('    }')
    [void] $builder.AppendLine()

    for ($i = 0; $i -lt $methods.Count; $i++) {
        $method = $methods[$i]
        [void] $builder.AppendLine("    public $recordName $method(String id, $enumName status) {")
        [void] $builder.AppendLine("        $recordName existing = cache.get(id);")
        [void] $builder.AppendLine('        if (existing == null) {')
        [void] $builder.AppendLine("            existing = new $recordName(id, BigDecimal.TEN, Instant.now());")
        [void] $builder.AppendLine('        }')
        [void] $builder.AppendLine('        List<String> trail = new ArrayList<>();')
        [void] $builder.AppendLine('        trail.add(status.name());')
        [void] $builder.AppendLine('        for (String step : trail) {')
        [void] $builder.AppendLine('            if (step.isEmpty()) {')
        [void] $builder.AppendLine('                continue;')
        [void] $builder.AppendLine('            }')
        [void] $builder.AppendLine('        }')
        [void] $builder.AppendLine('        registry.register(existing.id());')
        [void] $builder.AppendLine('        return repository.persist(existing);')
        [void] $builder.AppendLine('    }')
        [void] $builder.AppendLine()
    }

    [void] $builder.AppendLine('}')
    $builder.ToString()
}

function New-Repository([string] $packageName, [string] $recordName) {
    @"
package $packageName;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for $recordName. Deliberately not a Spring Data interface, so
 * the corpus does not require a database on the classpath.
 */
public interface ${recordName}Repository {

    Optional<$recordName> findById(String id);

    List<$recordName> findAll();

    $recordName persist($recordName entity);
}
"@
}

function New-Controller([string] $packageName,
                        [string] $typeName,
                        [string] $recordName,
                        [string] $serviceName) {
    @"
package $packageName;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * $typeName. HTTP surface for $recordName.
 */
@RestController
@RequestMapping("/api/${packageName}/${recordName}")
public class $typeName {

    private final $serviceName service;

    public $typeName($serviceName service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<$recordName> get(@PathVariable String id) {
        return service.find(id);
    }

    @PostMapping
    public ResponseEntity<$recordName> create(@RequestBody $recordName body) {
        return service.save(body);
    }
}
"@
}

$typeCount = 0
$methodCount = 0

# One shared type, outside the generated packages. Every service imports it, which
# is the cross-package case RepoPilot can only record as INFERRED.
$sharedDirectory = Join-Path $javaRoot 'com\example\shared'
New-Item -ItemType Directory -Force -Path $sharedDirectory | Out-Null
Set-Content -Path (Join-Path $sharedDirectory 'SharedRegistry.java') -Encoding UTF8 -Value @'
package com.example.shared;

/**
 * SharedRegistry. Registration port referenced from every generated package.
 */
public interface SharedRegistry {

    void register(String name);
}
'@
$typeCount++

for ($p = 0; $p -lt $Packages; $p++) {
    $segment = "p{0:d3}" -f $p
    $packageName = "com.example.$segment"
    $directory = Join-Path $javaRoot ($packageName -replace '\.', '\')
    New-Item -ItemType Directory -Force -Path $directory | Out-Null

    for ($t = 0; $t -lt $TypesPerPackage; $t++) {
        $noun = $nouns[$random.Next($nouns.Count)]
        $adjective = $adjectives[$random.Next($adjectives.Count)]
        $suffix = "{0:d3}{1:d2}" -f $p, $t

        $recordName = "$adjective$noun$suffix"
        $enumName = "$($adjective.ToUpper())_STATE_$suffix"
        $serviceName = "$adjective$noun${suffix}Service"
        $repositoryName = "$adjective$noun${suffix}Repository"
        $controllerName = "$adjective$noun${suffix}Controller"

        Set-Content -Path (Join-Path $directory "$recordName.java") -Value (New-Record $packageName $recordName) -Encoding UTF8
        $typeCount++

        Set-Content -Path (Join-Path $directory "$enumName.java") -Value (New-Enum $packageName $enumName) -Encoding UTF8
        $typeCount++

        $methods = @()
        for ($m = 0; $m -lt $MethodsPerType; $m++) {
            $methods += $verbs[$random.Next($verbs.Count)]
        }

        Set-Content -Path (Join-Path $directory "$repositoryName.java") `
            -Value (New-Repository $packageName $recordName) -Encoding UTF8
        $typeCount++

        Set-Content -Path (Join-Path $directory "$serviceName.java") `
            -Value (New-Service $packageName $serviceName $recordName $enumName $repositoryName $methods) `
            -Encoding UTF8
        $typeCount++
        $methodCount += $methods.Count

        Set-Content -Path (Join-Path $directory "$controllerName.java") `
            -Value (New-Controller $packageName $controllerName $recordName $serviceName) -Encoding UTF8
        $typeCount++
    }
}

# --- non-Java fixtures -------------------------------------------------------
$docs = Join-Path $root 'docs'
New-Item -ItemType Directory -Force -Path $docs | Out-Null
Set-Content -Path (Join-Path $docs 'architecture.md') `
    -Value "# Corpus architecture`n`nGenerated documentation. Not Java, must be counted as excluded." -Encoding UTF8
Set-Content -Path (Join-Path $docs 'notes.txt') -Value 'scratch notes' -Encoding UTF8

$resources = Join-Path $root 'src\main\resources'
New-Item -ItemType Directory -Force -Path $resources | Out-Null
Set-Content -Path (Join-Path $resources 'application.properties') -Value "corpus.enabled=true`ncorpus.size=$Packages" -Encoding UTF8

# --- secret fixtures ---------------------------------------------------------
# These names are what RepoPilot's secret filter keys on. They must never be
# read, and the run must report them as excluded.
$config = Join-Path $root 'config'
New-Item -ItemType Directory -Force -Path $config | Out-Null
Set-Content -Path (Join-Path $config 'credentials.json') -Value '{ "apiKey": "not-a-real-key" }' -Encoding UTF8
Set-Content -Path (Join-Path $config 'id_rsa') -Value 'not-a-real-private-key' -Encoding UTF8
Set-Content -Path (Join-Path $config 'server.pem') -Value 'not-a-real-certificate' -Encoding UTF8

# --- parse failure fixtures --------------------------------------------------
$problems = Join-Path $javaRoot 'com\example\problems'
New-Item -ItemType Directory -Force -Path $problems | Out-Null
Set-Content -Path (Join-Path $problems '_SyntaxBreak.java') `
    -Value @'
package com.example.problems;

public class _SyntaxBreak {
    public void broken( {
}
'@ -Encoding UTF8

$javaFiles = (Get-ChildItem -Recurse -File -Filter *.java $root).Count
$totalFiles = (Get-ChildItem -Recurse -File $root).Count

Write-Host "corpus written to $root"
Write-Host ("  java files      : {0}" -f $javaFiles)
Write-Host ("  all files       : {0}" -f $totalFiles)
Write-Host ("  generated types : {0}" -f $typeCount)
Write-Host ("  methods         : {0}" -f $methodCount)
Write-Host ("  secret fixtures : 3 (must appear as excluded, never read)")
Write-Host ("  broken java     : 1 (must appear as PARSED_WITH_PROBLEMS)")
