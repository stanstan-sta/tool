param(
    [string]$ModsDir = $env:MINDCRAFT_MODS_DIR,
    [string]$ServerModsDir = $env:MINDCRAFT_SERVER_MODS_DIR,
    [string]$BaritoneDir,
    [string]$BridgeDir,
    [string]$ServerCompanionDir,
    [switch]$SkipBuild,
    [switch]$SkipServerMod,
    [switch]$CleanOld
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolDir = Resolve-Path (Join-Path $ScriptDir "..")
if (-not $BaritoneDir) {
    $BaritoneDir = Join-Path (Split-Path -Parent $ToolDir) "baritone"
}
if (-not $BridgeDir) {
    $BridgeDir = Join-Path $ToolDir "fabric-bridge-mod"
}
if (-not $ServerCompanionDir) {
    $ServerCompanionDir = Join-Path $ToolDir "server-companion-mod"
}

$BaritoneDir = (Resolve-Path $BaritoneDir).Path
$BridgeDir = (Resolve-Path $BridgeDir).Path
$ServerCompanionDir = (Resolve-Path $ServerCompanionDir).Path

function Invoke-GradleBuild {
    param(
        [string]$WorkingDirectory,
        [string[]]$GradleArgs
    )

    $gradlew = Join-Path $WorkingDirectory "gradlew.bat"
    if (-not (Test-Path $gradlew)) {
        throw "Missing gradlew.bat in $WorkingDirectory"
    }

    Push-Location $WorkingDirectory
    try {
        & $gradlew @GradleArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle failed in $WorkingDirectory with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Get-NewestJar {
    param(
        [string[]]$SearchDirs,
        [string[]]$IncludePatterns,
        [string[]]$ExcludePatterns = @("*sources*.jar", "*dev-shadow*.jar")
    )

    $jars = @()
    foreach ($dir in $SearchDirs) {
        if (-not (Test-Path $dir)) { continue }
        foreach ($pattern in $IncludePatterns) {
            $jars += Get-ChildItem -Path $dir -Filter $pattern -File -ErrorAction SilentlyContinue
        }
    }

    foreach ($exclude in $ExcludePatterns) {
        $jars = $jars | Where-Object { $_.Name -notlike $exclude }
    }

    $jar = $jars | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) {
        throw "No jar found in: $($SearchDirs -join ', ')"
    }
    return $jar
}

if (-not $ModsDir) {
    $fallbacks = @(
        # Lunar Client: the real on-disk layout is profiles\<name>\mods\fabric-<ver>
        # with NO extra "lunar" segment. The default Lunar profile is named "1.21".
        # (The mis-pathed entry below was silently skipped, making the build fall
        # through to .minecraft\mods and deploy where Lunar never loads from.)
        (Join-Path $env:USERPROFILE ".lunarclient\profiles\1.21\mods\fabric-1.21.11"),
        (Join-Path $env:USERPROFILE ".lunarclient\profiles\lunar\1.21\mods\fabric-1.21.11"),
        (Join-Path $env:APPDATA ".minecraft\mods")
    )
    foreach ($fallback in $fallbacks) {
        if (Test-Path $fallback) {
            $ModsDir = $fallback
            break
        }
    }
    if (-not $ModsDir) {
        throw "Set -ModsDir or MINDCRAFT_MODS_DIR to your Minecraft/Lunar mods folder."
    }
}
if (-not (Test-Path $ModsDir)) {
    New-Item -ItemType Directory -Path $ModsDir | Out-Null
}
$ModsDir = (Resolve-Path $ModsDir).Path

Write-Host "Tool repo:     $ToolDir"
Write-Host "Bridge mod:    $BridgeDir"
Write-Host "Baritone repo: $BaritoneDir"
Write-Host "Mods folder:   $ModsDir"

if (-not $SkipBuild) {
    Write-Host "`nBuilding Mindcraft Bridge..."
    Invoke-GradleBuild -WorkingDirectory $BridgeDir -GradleArgs @("build")

    Write-Host "`nBuilding custom Baritone Fabric jar..."
    Invoke-GradleBuild -WorkingDirectory $BaritoneDir -GradleArgs @(":fabric:remapJar")

    if (-not $SkipServerMod) {
        Write-Host "`nBuilding Mindcraft Server Companion mod..."
        # Non-fatal: the server mod is an optional deployable for your own Fabric SERVER.
        # A failure here must not abort the (critical) client + Baritone build above.
        try {
            Invoke-GradleBuild -WorkingDirectory $ServerCompanionDir -GradleArgs @("build")
        } catch {
            Write-Warning "Server companion mod build failed (skipping): $($_.Exception.Message)"
        }
    }
}

$bridgeJar = Get-NewestJar `
    -SearchDirs @(Join-Path $BridgeDir "build\libs") `
    -IncludePatterns @("mindcraft-bridge-*.jar")

$baritoneJar = Get-NewestJar `
    -SearchDirs @(
        (Join-Path $BaritoneDir "fabric\build\libs"),
        (Join-Path $BaritoneDir "dist")
    ) `
    -IncludePatterns @("*fabric*.jar", "*baritone*.jar")

if ($CleanOld) {
    Write-Host "`nRemoving older matching jars from mods folder..."
    Get-ChildItem -Path $ModsDir -File |
        Where-Object { $_.Name -like "mindcraft-bridge-*.jar" -or $_.Name -like "*baritone*.jar" } |
        ForEach-Object {
            $file = $_
            try {
                Remove-Item -LiteralPath $file.FullName -Force
            } catch {
                Write-Warning "Could not remove $($file.Name). Close Minecraft/Lunar if it is running, then rerun with -CleanOld."
            }
        }
}

Write-Host "`nCopying jars..."
Copy-Item -LiteralPath $bridgeJar.FullName -Destination (Join-Path $ModsDir $bridgeJar.Name) -Force
Copy-Item -LiteralPath $baritoneJar.FullName -Destination (Join-Path $ModsDir $baritoneJar.Name) -Force

Write-Host "Copied:"
Write-Host "  $($bridgeJar.Name)"
Write-Host "  $($baritoneJar.Name)"

# --- Server companion mod (optional; deploys to your Fabric SERVER's mods folder) ---
if (-not $SkipServerMod) {
    $serverLibs = Join-Path $ServerCompanionDir "build\libs"
    $serverJar = $null
    if (Test-Path $serverLibs) {
        $serverJar = Get-ChildItem -Path $serverLibs -Filter "mindcraft-server-companion-*.jar" -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike "*sources*.jar" } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    if ($serverJar) {
        if ($ServerModsDir) {
            if (-not (Test-Path $ServerModsDir)) {
                New-Item -ItemType Directory -Path $ServerModsDir | Out-Null
            }
            $ServerModsDir = (Resolve-Path $ServerModsDir).Path
            Copy-Item -LiteralPath $serverJar.FullName -Destination (Join-Path $ServerModsDir $serverJar.Name) -Force
            Write-Host "  $($serverJar.Name)  ->  $ServerModsDir"
        } else {
            Write-Host "`nServer companion jar built (set -ServerModsDir or MINDCRAFT_SERVER_MODS_DIR to auto-copy):"
            Write-Host "  $($serverJar.FullName)"
        }
    } else {
        Write-Warning "No server companion jar found in $serverLibs (build may have been skipped or failed)."
    }
}

Write-Host "`nDone."
