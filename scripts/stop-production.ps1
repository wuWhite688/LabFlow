param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [switch]$AlsoMiddleware
)

$ErrorActionPreference = "Stop"
$pidFile = Join-Path $ProjectRoot ".runtime\backend-production.pid"
if (Test-Path -LiteralPath $pidFile) {
    $raw = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    $procId = 0
    if ([int]::TryParse($raw, [ref]$procId)) {
        if (Get-Process -Id $procId -ErrorAction SilentlyContinue) {
            Stop-Process -Id $procId -Force
            Write-Host "Stopped production backend pid=$procId"
        } else {
            Write-Host "Production backend pid=$procId not running."
        }
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "No production backend pid file found."
}

if ($AlsoMiddleware) {
    & (Join-Path $PSScriptRoot "stop-middleware.ps1") -ProjectRoot $ProjectRoot
}
