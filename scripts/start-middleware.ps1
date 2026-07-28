param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $ProjectRoot

$envFile = Join-Path $ProjectRoot ".env"
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item -LiteralPath (Join-Path $ProjectRoot ".env.example") -Destination $envFile
    Write-Host "Created .env from .env.example (edit secrets before production use)."
}

. (Join-Path $PSScriptRoot "load-env.ps1") -EnvFile $envFile
. (Join-Path $PSScriptRoot "wsl-docker-lib.ps1")

$useWsl = -not (Test-HostDocker)
if ($useWsl) {
    Write-Host "Host Docker not found; using WSL Ubuntu Docker Engine with persistent keepalive."
    [void](Start-WslKeepalive -ProjectRoot $ProjectRoot)
} else {
    Write-Host "Using host Docker."
}

Write-Host "Starting MySQL / Redis / RabbitMQ via docker compose..."
$code = Invoke-LabflowDocker -ProjectRoot $ProjectRoot -DockerArgs @("compose", "--env-file", ".env", "up", "-d")
if ($code -ne 0) {
    throw "docker compose up failed with exit code $code"
}

Write-Host "Waiting for container healthchecks..."
if (-not (Wait-MiddlewareHealthy -ProjectRoot $ProjectRoot -TimeoutSeconds 180)) {
    throw "Middleware containers did not become healthy in time."
}

if ($useWsl) {
    $pidFile = Get-KeepalivePidFile $ProjectRoot
    $kid = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    Write-Host "WSL keepalive Windows pid=$kid (prevents Ubuntu auto-shutdown)"
}

Write-Host ""
Write-Host "Middleware ready:"
Write-Host "  MySQL      127.0.0.1:$($env:MYSQL_PORT)  db=$($env:MYSQL_DATABASE) user=$($env:MYSQL_USER)"
Write-Host "  Redis      127.0.0.1:$($env:REDIS_PORT)"
Write-Host "  RabbitMQ   amqp://127.0.0.1:$($env:RABBITMQ_PORT)  management http://127.0.0.1:$($env:RABBITMQ_MANAGEMENT_PORT)"
