param(
    [int]$BackendPort = 18080,
    [int]$FrontendPort = 13000,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$runtime = Join-Path $root ".runtime"
$frontend = Join-Path $root "frontend"
$jdk = "C:\Program Files\Java\jdk-25.0.2"

if (-not (Test-Path "$jdk\bin\java.exe")) {
    throw "未找到 JDK：$jdk"
}
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    throw "未找到 npm.cmd，请先安装 Node.js 22+"
}

New-Item -ItemType Directory -Force -Path $runtime | Out-Null
foreach ($name in @("backend.pid", "frontend.pid")) {
    $pidFile = Join-Path $runtime $name
    if (Test-Path $pidFile) {
        $savedPid = [int](Get-Content $pidFile -Raw)
        if (Get-Process -Id $savedPid -ErrorAction SilentlyContinue) {
            throw "$name 对应的进程仍在运行，请先执行 .\stop-fullstack.ps1"
        }
        Remove-Item $pidFile -Force
    }
}

$env:JAVA_HOME = $jdk
if (-not $SkipBuild) {
    & "$root\mvnw.cmd" clean package
    if ($LASTEXITCODE -ne 0) { throw "后端构建失败" }
    Push-Location $frontend
    try {
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw "前端构建失败" }
    } finally {
        Pop-Location
    }
}

$jar = Get-ChildItem "$root\target\lab-equipment-platform-*.jar" | Where-Object { $_.Name -notlike "*.original" } | Select-Object -First 1
if (-not $jar) { throw "未找到后端 jar，请先运行 .\build.ps1" }

$backendOut = Join-Path $runtime "backend.stdout.log"
$backendErr = Join-Path $runtime "backend.stderr.log"
$backendProcess = Start-Process -FilePath "$jdk\bin\java.exe" -ArgumentList @("-jar", $jar.FullName, "--server.port=$BackendPort") -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput $backendOut -RedirectStandardError $backendErr -PassThru
[IO.File]::WriteAllText((Join-Path $runtime "backend.pid"), [string]$backendProcess.Id)

$backendReady = $false
for ($i = 0; $i -lt 50; $i++) {
    try {
        $health = Invoke-RestMethod -Uri "http://127.0.0.1:$BackendPort/actuator/health" -TimeoutSec 2
        if ($health.status -eq "UP") { $backendReady = $true; break }
    } catch {}
    Start-Sleep -Milliseconds 500
}
if (-not $backendReady) {
    & "$root\stop-fullstack.ps1"
    throw "后端未能就绪，请查看 $backendErr"
}

$env:BACKEND_BASE_URL = "http://127.0.0.1:$BackendPort"
$frontendOut = Join-Path $runtime "frontend.stdout.log"
$frontendErr = Join-Path $runtime "frontend.stderr.log"
$frontendProcess = Start-Process -FilePath "npm.cmd" -ArgumentList @("run", "dev", "--", "--port", [string]$FrontendPort, "--hostname", "0.0.0.0") -WorkingDirectory $frontend -WindowStyle Hidden -RedirectStandardOutput $frontendOut -RedirectStandardError $frontendErr -PassThru
[IO.File]::WriteAllText((Join-Path $runtime "frontend.pid"), [string]$frontendProcess.Id)

$frontendReady = $false
for ($i = 0; $i -lt 50; $i++) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$FrontendPort/" -TimeoutSec 2
        if ($response.StatusCode -eq 200) { $frontendReady = $true; break }
    } catch {}
    Start-Sleep -Milliseconds 500
}
if (-not $frontendReady) {
    & "$root\stop-fullstack.ps1"
    throw "前端未能就绪，请查看 $frontendErr"
}

Write-Host "LabFlow 已启动"
Write-Host "前端：http://localhost:$FrontendPort"
Write-Host "后端：http://127.0.0.1:$BackendPort"
