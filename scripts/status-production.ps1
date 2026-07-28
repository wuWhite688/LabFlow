param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [int]$ServerPort = 0
)

$ErrorActionPreference = "Continue"
Set-Location -LiteralPath $ProjectRoot

if (Test-Path -LiteralPath (Join-Path $ProjectRoot ".env")) {
    . (Join-Path $PSScriptRoot "load-env.ps1") -EnvFile (Join-Path $ProjectRoot ".env")
}
. (Join-Path $PSScriptRoot "wsl-docker-lib.ps1")

if ($ServerPort -le 0) {
    if ($env:SERVER_PORT) { $ServerPort = [int]$env:SERVER_PORT } else { $ServerPort = 18080 }
}

Write-Host "=== LabFlow production status ==="
Write-Host "Project: $ProjectRoot"
Write-Host ""

# Backend process
$backendPidFile = Join-Path $ProjectRoot ".runtime\backend-production.pid"
$backendOk = $false
$backendPid = $null
if (Test-Path -LiteralPath $backendPidFile) {
    $raw = (Get-Content -LiteralPath $backendPidFile -Raw).Trim()
    $tmp = 0
    if ([int]::TryParse($raw, [ref]$tmp)) {
        $backendPid = $tmp
        $p = Get-Process -Id $backendPid -ErrorAction SilentlyContinue
        if ($p) {
            $backendOk = $true
            Write-Host "[OK] backend process pid=$backendPid ($($p.ProcessName))"
        } else {
            Write-Host "[DOWN] backend pid file exists but process $backendPid is not running"
        }
    }
} else {
    Write-Host "[DOWN] backend pid file missing"
}

# WSL keepalive
$useWsl = -not (Test-HostDocker)
if ($useWsl) {
    $kaFile = Get-KeepalivePidFile $ProjectRoot
    if (Test-KeepaliveAlive $ProjectRoot) {
        $kid = (Get-Content -LiteralPath $kaFile -Raw).Trim()
        Write-Host "[OK] WSL keepalive Windows pid=$kid"
    } else {
        Write-Host "[DOWN] WSL keepalive not running (Ubuntu may auto-stop)"
    }
    $wslList = wsl -l -v 2>&1 | Out-String
    Write-Host "WSL distros:"
    Write-Host $wslList.Trim()
} else {
    Write-Host "[OK] host Docker available (no WSL keepalive required)"
}

# Containers
$names = @("labflow-mysql", "labflow-redis", "labflow-rabbitmq")
$allHealthy = $true
foreach ($n in $names) {
    $h = Get-ContainerHealth -ProjectRoot $ProjectRoot -Name $n
    if (-not $h) { $h = "missing" }
    if ($h -eq "healthy") {
        Write-Host "[OK] $n => $h"
    } else {
        Write-Host "[DOWN] $n => $h"
        $allHealthy = $false
    }
}

# Health endpoint
$healthOk = $false
try {
    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$ServerPort/actuator/health" -TimeoutSec 5
    $db = $health.components.db.status
    $redis = $health.components.redis.status
    $rabbit = $health.components.rabbit.status
    Write-Host "[OK] health endpoint status=$($health.status) db=$db redis=$redis rabbit=$rabbit"
    $healthOk = ($health.status -eq "UP")
    if ($db -and $redis -and $rabbit) {
        $healthOk = ($health.status -eq "UP" -and $db -eq "UP" -and $redis -eq "UP" -and $rabbit -eq "UP")
    }
    if (-not $healthOk) {
        Write-Host "[DOWN] health components not all UP"
    }
} catch {
    Write-Host "[DOWN] health endpoint: $($_.Exception.Message)"
}

Write-Host ""
if ($backendOk -and $allHealthy -and $healthOk) {
    if ($useWsl -and -not (Test-KeepaliveAlive $ProjectRoot)) {
        Write-Host "OVERALL: DEGRADED (stack up now but keepalive missing — may drop soon)"
        exit 2
    }
    Write-Host "OVERALL: HEALTHY"
    exit 0
}

Write-Host "OVERALL: UNHEALTHY"
exit 1
