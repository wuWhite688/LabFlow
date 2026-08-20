param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [int]$ServerPort = 0,
    [string]$ApprovalTimeout = "",
    [switch]$SkipBuild,
    [switch]$SkipMiddleware,
    [string]$JavaPath = "",
    [switch]$AllowInsecureRefreshCookieForLocalHttp,
    [switch]$EnableDemoData
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $ProjectRoot

function Get-RunnableJar {
    param([string]$Root)
    return Get-ChildItem (Join-Path $Root "target\lab-equipment-platform-*.jar") -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

function Assert-JarExistsAndFresh {
    param(
        [string]$Root,
        [System.IO.FileInfo]$Jar
    )
    if (-not $Jar) {
        throw "Runnable JAR not found under target/. Run .\build.ps1 or scripts\start-production.ps1 without -SkipBuild."
    }

    $watchPaths = @(
        (Join-Path $Root "pom.xml"),
        (Join-Path $Root "src\main\java"),
        (Join-Path $Root "src\main\resources"),
        (Join-Path $Root "src\main\resources\db\migration")
    )
    $sources = @()
    foreach ($path in $watchPaths) {
        if (-not (Test-Path -LiteralPath $path)) { continue }
        $item = Get-Item -LiteralPath $path
        if ($item.PSIsContainer) {
            $sources += Get-ChildItem -LiteralPath $path -Recurse -File -ErrorAction SilentlyContinue
        } else {
            $sources += $item
        }
    }

    $newest = $sources | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($newest -and $newest.LastWriteTimeUtc -gt $Jar.LastWriteTimeUtc) {
        throw ("Refuse -SkipBuild: JAR is stale.`n" +
            "  JAR: $($Jar.FullName) @ $($Jar.LastWriteTimeUtc.ToString('u'))`n" +
            "  Newer source: $($newest.FullName) @ $($newest.LastWriteTimeUtc.ToString('u'))`n" +
            "Rebuild with .\build.ps1 or start-production.ps1 without -SkipBuild.")
    }
    Write-Host "Using JAR $($Jar.Name) (fresh vs sources/resources/migrations)"
}

$envFile = Join-Path $ProjectRoot ".env"
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Run: Copy-Item .env.example .env  then set a real JWT_SECRET (>=32 bytes)."
}
. (Join-Path $PSScriptRoot "load-env.ps1") -EnvFile $envFile

if (-not $env:JWT_SECRET -or [string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    throw "JWT_SECRET is required in .env for production profile (min 32 bytes, non-placeholder)."
}

if ($ServerPort -le 0) {
    if ($env:SERVER_PORT) { $ServerPort = [int]$env:SERVER_PORT } else { $ServerPort = 18080 }
}

. (Join-Path $PSScriptRoot "resolve-java.ps1")
$javaRuntime = Use-LabFlowJava -JavaPath $JavaPath
$javaExe = $javaRuntime.Executable

if (-not $SkipMiddleware) {
    & (Join-Path $PSScriptRoot "start-middleware.ps1") -ProjectRoot $ProjectRoot
}

$runtime = Join-Path $ProjectRoot ".runtime"
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$pidFile = Join-Path $runtime "backend-production.pid"
if (Test-Path $pidFile) {
    $old = [int](Get-Content $pidFile -Raw)
    if (Get-Process -Id $old -ErrorAction SilentlyContinue) {
        throw "Production backend already running (pid=$old). Stop it first."
    }
    Remove-Item $pidFile -Force
}

if (-not $SkipBuild) {
    & "$ProjectRoot\mvnw.cmd" -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Maven package failed" }
} else {
    $jarForCheck = Get-RunnableJar -Root $ProjectRoot
    Assert-JarExistsAndFresh -Root $ProjectRoot -Jar $jarForCheck
}

$jar = Get-RunnableJar -Root $ProjectRoot
if (-not $jar) { throw "Jar not found. Run .\build.ps1 or mvnw package first." }

$logFile = Join-Path $runtime "backend-production.log"
$args = @(
    "-jar", $jar.FullName,
    "--spring.profiles.active=production",
    "--server.port=$ServerPort",
    "--server.address=127.0.0.1",
    "--logging.file.name=$logFile"
)
if ($AllowInsecureRefreshCookieForLocalHttp) {
    Write-Warning "Refresh Cookie Secure=false for this local HTTP process only. Do not use this switch for HTTPS deployments."
    $args += "--labops.jwt.refresh-cookie-secure=false"
}
if ($EnableDemoData) {
    Write-Warning "Demo users/data enabled for this process only."
    $args += "--labops.demo-users.enabled=true"
    $args += "--labops.demo-data.enabled=true"
}
if ($ApprovalTimeout) {
    $args += "--labops.reservation-approval-timeout=$ApprovalTimeout"
    # Prefer Rabbit path for verification; keep compensation as slow fallback.
    $args += "--labops.reservation-expiry.scan-interval=300000"
}

Write-Host "Starting backend with production profile on port $ServerPort ..."
# UseShellExecute=true (default when not redirecting) helps detach from agent Job Objects.
$proc = Start-Process -FilePath $javaExe `
    -ArgumentList $args `
    -WorkingDirectory $ProjectRoot `
    -WindowStyle Hidden `
    -PassThru
if (-not $proc) { throw "Failed to start java process" }
Set-Content -Path $pidFile -Value ([string]$proc.Id) -NoNewline
Write-Host "Production java pid=$($proc.Id)"

$ready = $false
for ($i = 0; $i -lt 90; $i++) {
    if (-not (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) {
        throw "Backend process exited early. See $logFile"
    }
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$ServerPort/actuator/health" -TimeoutSec 2
        if ($health.status -eq "UP") {
            $ready = $true
            break
        }
    } catch {
        Start-Sleep -Milliseconds 1000
    }
}

if (-not $ready) {
    Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    throw "Backend failed to become healthy. See $logFile"
}

Write-Host "Production backend UP: http://127.0.0.1:$ServerPort"
Write-Host "Health: http://127.0.0.1:$ServerPort/actuator/health"
Write-Host "Logs: $logFile"
