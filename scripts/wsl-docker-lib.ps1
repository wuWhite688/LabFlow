# Shared helpers for WSL Docker fallback (no Windows Docker Desktop).

function Get-LabflowWslPath {
    param([string]$WindowsPath)
    $full = [System.IO.Path]::GetFullPath($WindowsPath)
    if ($full -match '^[A-Za-z]:\\') {
        $drive = $full.Substring(0, 1).ToLowerInvariant()
        $rest = $full.Substring(2).Replace('\', '/')
        return "/mnt/$drive$rest"
    }
    throw "Cannot convert path to WSL: $WindowsPath"
}

function Test-HostDocker {
    return [bool](Get-Command docker -ErrorAction SilentlyContinue)
}

function Get-RuntimeDir {
    param([string]$ProjectRoot)
    $runtime = Join-Path $ProjectRoot ".runtime"
    New-Item -ItemType Directory -Force -Path $runtime | Out-Null
    return $runtime
}

function Get-KeepalivePidFile {
    param([string]$ProjectRoot)
    return Join-Path (Get-RuntimeDir $ProjectRoot) "wsl-keepalive.pid"
}

function Get-KeepaliveLogFile {
    param([string]$ProjectRoot)
    return Join-Path (Get-RuntimeDir $ProjectRoot) "wsl-keepalive.log"
}

function Test-KeepaliveAlive {
    param([string]$ProjectRoot)
    $pidFile = Get-KeepalivePidFile $ProjectRoot
    if (-not (Test-Path -LiteralPath $pidFile)) { return $false }
    $raw = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if (-not $raw) { return $false }
    $procId = 0
    if (-not [int]::TryParse($raw, [ref]$procId)) { return $false }
    $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if (-not $proc) { return $false }
    # Must still be a wsl-related host process
    $name = $proc.ProcessName.ToLowerInvariant()
    return ($name -eq "wsl" -or $name -eq "wslhost" -or $name -eq "wslservice" -or $name -eq "powershell" -or $name -eq "pwsh")
}

function Start-WslKeepalive {
    param(
        [string]$ProjectRoot,
        [string]$Distro = "Ubuntu"
    )
    $pidFile = Get-KeepalivePidFile $ProjectRoot
    $logFile = Get-KeepaliveLogFile $ProjectRoot

    if (Test-KeepaliveAlive $ProjectRoot) {
        $existing = (Get-Content -LiteralPath $pidFile -Raw).Trim()
        Write-Host "WSL keepalive already running (pid=$existing)"
        return [int]$existing
    }

    if (Test-Path -LiteralPath $pidFile) {
        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    }

    # Hidden Windows process that holds an open WSL session so the distro (and dockerd) do not auto-exit.
    # sleep infinity keeps the session open; restart dockerd if it dies.
    $bashCmd = @'
export HTTP_PROXY="${HTTP_PROXY:-http://127.0.0.1:7897}"
export HTTPS_PROXY="${HTTPS_PROXY:-http://127.0.0.1:7897}"
export NO_PROXY="${NO_PROXY:-localhost,127.0.0.1}"
ensure_dockerd() {
  if docker info >/dev/null 2>&1; then
    return 0
  fi
  echo "[keepalive] starting dockerd..."
  nohup env HTTP_PROXY="$HTTP_PROXY" HTTPS_PROXY="$HTTPS_PROXY" NO_PROXY="$NO_PROXY" dockerd >>/tmp/labflow-dockerd.log 2>&1 &
  for i in $(seq 1 30); do
    if docker info >/dev/null 2>&1; then
      echo "[keepalive] dockerd ready"
      return 0
    fi
    sleep 1
  done
  echo "[keepalive] dockerd failed to become ready"
  return 1
}
ensure_dockerd || true
echo "[keepalive] session started pid=$$ at $(date -Is)"
while true; do
  ensure_dockerd || true
  sleep 30
done
'@
    $bashCmd = $bashCmd -replace "`r`n", "`n"

    $argList = @(
        "-d", $Distro,
        "-u", "root",
        "--",
        "bash", "-lc", $bashCmd
    )

    # IMPORTANT: do NOT redirect stdout/stderr here.
    # Redirect forces UseShellExecute=false and binds the process to the parent Job Object,
    # so agent/tool shells will kill keepalive when they exit — Ubuntu then auto-stops and
    # all containers die. No-redirect Start-Process detaches from that job.
    $proc = Start-Process -FilePath "wsl.exe" `
        -ArgumentList $argList `
        -WindowStyle Hidden `
        -PassThru

    if (-not $proc) {
        throw "Failed to start WSL keepalive process"
    }
    Set-Content -LiteralPath $pidFile -Value ([string]$proc.Id) -NoNewline
    Write-Host "Started WSL keepalive (Windows pid=$($proc.Id))"

    # Give WSL + dockerd a moment
    Start-Sleep -Seconds 4
    if (-not (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) {
        throw "WSL keepalive process exited immediately (pid=$($proc.Id))"
    }
    return $proc.Id
}

function Stop-WslKeepalive {
    param([string]$ProjectRoot)
    $pidFile = Get-KeepalivePidFile $ProjectRoot
    if (-not (Test-Path -LiteralPath $pidFile)) {
        Write-Host "No WSL keepalive pid file."
        return
    }
    $raw = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    $procId = 0
    if ([int]::TryParse($raw, [ref]$procId)) {
        $proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
        if ($proc) {
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            # Child wslhost processes may linger; terminate distro session cleanly.
            try { wsl -d Ubuntu --terminate Ubuntu 2>$null } catch {}
            Write-Host "Stopped WSL keepalive pid=$procId"
        } else {
            Write-Host "WSL keepalive pid=$procId already gone."
        }
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

function Invoke-LabflowDocker {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string[]]$DockerArgs
    )
    if (-not $DockerArgs -or $DockerArgs.Count -eq 0) {
        throw "Invoke-LabflowDocker requires docker arguments"
    }
    if (Test-HostDocker) {
        Push-Location -LiteralPath $ProjectRoot
        try {
            & docker @DockerArgs
            return $LASTEXITCODE
        } finally {
            Pop-Location
        }
    }

    $wslProj = Get-LabflowWslPath $ProjectRoot
    # Avoid inheriting Windows proxy env (wildcards / <local>) into bash -lc.
    $runner = @"
#!/usr/bin/env bash
set -euo pipefail
export HTTP_PROXY='http://127.0.0.1:7897'
export HTTPS_PROXY='http://127.0.0.1:7897'
export NO_PROXY='localhost,127.0.0.1'
if ! docker info >/dev/null 2>&1; then
  nohup env HTTP_PROXY="`$HTTP_PROXY" HTTPS_PROXY="`$HTTPS_PROXY" NO_PROXY="`$NO_PROXY" dockerd >>/tmp/labflow-dockerd.log 2>&1 &
  for i in `$(seq 1 30); do
    if docker info >/dev/null 2>&1; then break; fi
    sleep 1
  done
fi
if ! docker info >/dev/null 2>&1; then
  echo "dockerd not ready" >&2
  exit 1
fi
cd '$wslProj'
exec docker "`$@"
"@
    $runner = $runner -replace "`r`n", "`n"
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($runner))
    wsl -d Ubuntu -u root -- bash -lc "echo $b64 | base64 -d > /tmp/labflow-docker-run.sh && chmod +x /tmp/labflow-docker-run.sh"
    $wslArgs = @("-d", "Ubuntu", "-u", "root", "--", "bash", "/tmp/labflow-docker-run.sh") + @($DockerArgs)
    & wsl.exe @wslArgs
    return $LASTEXITCODE
}

function Get-ContainerHealth {
    param(
        [string]$ProjectRoot,
        [string]$Name
    )
    if (Test-HostDocker) {
        $status = docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $Name 2>$null
        return "$status".Trim()
    }
    $status = wsl -d Ubuntu -u root -- docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $Name 2>$null
    return "$status".Trim()
}

function Wait-MiddlewareHealthy {
    param(
        [string]$ProjectRoot,
        [int]$TimeoutSeconds = 180
    )
    $names = @("labflow-mysql", "labflow-redis", "labflow-rabbitmq")
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $allOk = $true
        $snapshot = @()
        foreach ($n in $names) {
            $h = Get-ContainerHealth -ProjectRoot $ProjectRoot -Name $n
            if (-not $h) { $h = "missing" }
            $snapshot += "$n=$h"
            if ($h -ne "healthy") { $allOk = $false }
        }
        Write-Host ("  health: " + ($snapshot -join ", "))
        if ($allOk) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}
