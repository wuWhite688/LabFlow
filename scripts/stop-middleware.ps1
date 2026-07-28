param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [switch]$Volumes
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $ProjectRoot

. (Join-Path $PSScriptRoot "wsl-docker-lib.ps1")

if (Test-Path -LiteralPath (Join-Path $ProjectRoot ".env")) {
    . (Join-Path $PSScriptRoot "load-env.ps1") -EnvFile (Join-Path $ProjectRoot ".env")
}

Write-Host "Stopping compose stack..."
try {
    $downArgs = @("compose", "--env-file", ".env", "down")
    if ($Volumes) { $downArgs += "--volumes" }
    $code = Invoke-LabflowDocker -ProjectRoot $ProjectRoot -DockerArgs $downArgs
    if ($code -ne 0) {
        Write-Host "WARN: docker compose down exited $code (continuing to stop keepalive)"
    } else {
        Write-Host "docker compose down completed."
    }
} catch {
    Write-Host "WARN: docker compose down failed: $_"
}

# Always stop WSL keepalive when present (WSL fallback path)
Stop-WslKeepalive -ProjectRoot $ProjectRoot
Write-Host "Middleware stopped."
