$ErrorActionPreference = "Stop"
$runtime = Join-Path $PSScriptRoot ".runtime"

foreach ($name in @("frontend.pid", "backend.pid")) {
    $pidFile = Join-Path $runtime $name
    if (-not (Test-Path $pidFile)) { continue }

    $savedPid = [int](Get-Content $pidFile -Raw)
    $process = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
    if ($process) {
        & taskkill.exe /PID $savedPid /T /F | Out-Null
    }
    Remove-Item $pidFile -Force
}

Write-Host "LabFlow 已停止"