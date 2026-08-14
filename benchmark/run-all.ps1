# Orchestrates real LabFlow reservation benches. Does not modify business code.
param(
    [int]$BackendPort = 18080,
    [switch]$SkipH2,
    [switch]$SkipProduction,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location -LiteralPath $root

$benchDir = Join-Path $root "benchmark"
$resultsDir = Join-Path $benchDir "results"
$logsDir = Join-Path $benchDir "logs"
$pidDir = Join-Path $benchDir ".run"
New-Item -ItemType Directory -Force -Path $resultsDir, $logsDir, $pidDir | Out-Null

$env:PYTHONIOENCODING = "utf-8"
$ts = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$orchestratorLog = Join-Path $logsDir "$ts-orchestrator.stdout.log"

function Log {
    param([string]$Message)
    $line = "[{0}] {1}" -f (Get-Date).ToUniversalTime().ToString("o"), $Message
    Write-Host $line
    Add-Content -LiteralPath $orchestratorLog -Value $line -Encoding utf8
}

function Invoke-Logged {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$StdoutPath,
        [string]$StderrPath,
        [int]$TimeoutSec = 0
    )
    $proc = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList -WorkingDirectory $root `
        -NoNewWindow -PassThru -Wait:($TimeoutSec -le 0) `
        -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath
    if ($TimeoutSec -gt 0) {
        $ok = $proc.WaitForExit($TimeoutSec * 1000)
        if (-not $ok) {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            throw "Process $FilePath timed out after ${TimeoutSec}s. stdout=$StdoutPath stderr=$StderrPath"
        }
    }
    return $proc.ExitCode
}

. (Join-Path $root "scripts\resolve-java.ps1")
$javaRuntime = Use-LabFlowJava
$javaExe = $javaRuntime.Executable

$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$cs = Get-CimInstance Win32_ComputerSystem
$os = Get-CimInstance Win32_OperatingSystem
$javaVerRaw = cmd.exe /c "`"$javaExe`" -version 2>&1"
$pyVer = & py -3 -c "import sys; print(sys.version)"

$machine = [ordered]@{
    source              = "benchmark/run-all.ps1"
    collected_at_utc    = (Get-Date).ToUniversalTime().ToString("o")
    cpu_name            = $cpu.Name.Trim()
    physical_cores      = $cpu.NumberOfCores
    logical_processors  = $cpu.NumberOfLogicalProcessors
    memory_gb           = [math]::Round($cs.TotalPhysicalMemory / 1GB, 2)
    os                  = ("{0} {1} {2}" -f $os.Caption, $os.Version, $os.OSArchitecture).Trim()
    java_home           = $javaRuntime.JavaHome
    java_major          = $javaRuntime.MajorVersion
    java_version        = (($javaVerRaw | Out-String) -split "`n")[0].Trim()
    java_version_raw    = ($javaVerRaw | Out-String).Trim()
    python_version      = ($pyVer | Out-String).Trim()
    python_launcher     = "py -3"
}
$machinePath = Join-Path $benchDir "machine.json"
[System.IO.File]::WriteAllText($machinePath, ($machine | ConvertTo-Json -Depth 6), [System.Text.UTF8Encoding]::new($false))
Log "Wrote $machinePath"

$runNotes = [ordered]@{
    started_at_utc = (Get-Date).ToUniversalTime().ToString("o")
    environments   = [ordered]@{}
    bugs           = @()
    aborted        = $false
    abort_reason   = $null
}

function Save-Notes {
    $runNotes.finished_at_utc = (Get-Date).ToUniversalTime().ToString("o")
    $notesPath = Join-Path $benchDir "run-notes.json"
    $runNotes | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $notesPath -Encoding utf8
}

function Get-Jar {
    $jar = Get-ChildItem (Join-Path $root "target\lab-equipment-platform-*.jar") -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $jar) { throw "Runnable JAR not found under target/" }
    return $jar
}

function Wait-Health {
    param([int]$Port, [int]$TimeoutSec = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2
            if ($health.status -eq "UP") { return $true }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    return $false
}

function Stop-Tracked {
    param([string]$PidFile)
    if (-not (Test-Path -LiteralPath $PidFile)) { return }
    $raw = (Get-Content -LiteralPath $PidFile -Raw).Trim()
    $procId = 0
    if ([int]::TryParse($raw, [ref]$procId)) {
        if (Get-Process -Id $procId -ErrorAction SilentlyContinue) {
            & taskkill.exe /PID $procId /T /F | Out-Null
            Log "Stopped pid=$procId from $PidFile"
        }
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

function Start-H2Backend {
    param([int]$Port)
    $pidFile = Join-Path $pidDir "backend-h2.pid"
    Stop-Tracked $pidFile
    $jar = Get-Jar
    $stdout = Join-Path $logsDir "$ts-backend-h2.stdout.log"
    $stderr = Join-Path $logsDir "$ts-backend-h2.stderr.log"
    # UseShellExecute=true (no stream redirect) so the JVM is not killed with the
    # agent Job Object. Spring writes the full log via logging.file.name.
    $argList = @(
        "-jar", $jar.FullName,
        "--server.port=$Port",
        "--logging.file.name=$stdout"
    )
    Log "Starting H2 backend: $javaExe $($argList -join ' ')"
    $proc = Start-Process -FilePath $javaExe -ArgumentList $argList -WorkingDirectory $root `
        -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $pidFile -Value ([string]$proc.Id) -NoNewline
    if (-not (Test-Path -LiteralPath $stderr)) {
        Set-Content -LiteralPath $stderr -Value "stderr not redirected; see logging.file.name=$stdout`n" -Encoding utf8
    }
    if (-not (Wait-Health -Port $Port -TimeoutSec 90)) {
        $err = ""
        if (Test-Path $stderr) { $err = Get-Content $stderr -Raw -ErrorAction SilentlyContinue }
        if (Test-Path $stdout) { $err += "`n" + (Get-Content $stdout -Raw -ErrorAction SilentlyContinue) }
        Stop-Tracked $pidFile
        throw "H2 backend failed to become healthy. excerpt=`n$($err.Substring(0, [Math]::Min(2000, $err.Length)))"
    }
    $runNotes.environments["h2-local"] = [ordered]@{
        status      = "up"
        detail      = "Default profile. H2 mem + LocalReservationLock. port=$Port pid=$($proc.Id) jar=$($jar.Name)"
        stdout_log  = ("benchmark/logs/{0}" -f (Split-Path $stdout -Leaf))
        stderr_log  = ("benchmark/logs/{0}" -f (Split-Path $stderr -Leaf))
        backend_log = ("benchmark/logs/{0}" -f (Split-Path $stdout -Leaf))
        pid         = $proc.Id
        port        = $Port
    }
    Save-Notes
    return $pidFile
}

function Invoke-Bench {
    param(
        [string]$Scenario,
        [string]$Profile,
        [string]$LockImpl,
        [int]$Concurrency,
        [int]$Round = 1,
        [int]$Total = 500,
        [int]$Port
    )
    $runId = "{0}-{1}-{2}-n{3}-r{4}" -f $ts, $Profile, $Scenario, $Concurrency, $Round
    if ($Scenario -eq "performance") {
        $runId = "{0}-{1}-{2}-c{3}-t{4}-r{5}" -f $ts, $Profile, $Scenario, $Concurrency, $Total, $Round
    }
    $jsonOut = Join-Path $resultsDir "$runId.json"
    $stdout = Join-Path $logsDir "$runId.stdout.log"
    $stderr = Join-Path $logsDir "$runId.stderr.log"
    $argList = @(
        "-3", (Join-Path $benchDir "bench_reservation.py"),
        "--base-url", "http://127.0.0.1:$Port",
        "--scenario", $Scenario,
        "--concurrency", "$Concurrency",
        "--round", "$Round",
        "--total", "$Total",
        "--profile", $Profile,
        "--lock-impl", $LockImpl,
        "--out-dir", $resultsDir,
        "--run-id", $runId,
        "--json-out", $jsonOut
    )
    Log "RUN $runId"
    $code = Invoke-Logged -FilePath "py" -ArgumentList $argList -StdoutPath $stdout -StderrPath $stderr
    # also drop a copy of stdout next to json for RESULTS.md lookup
    Copy-Item -LiteralPath $stdout -Destination (Join-Path $resultsDir "$runId.stdout.log") -Force
    if (Test-Path $stderr) {
        Copy-Item -LiteralPath $stderr -Destination (Join-Path $resultsDir "$runId.stderr.log") -Force
    }
    Log "EXIT $runId code=$code"
    return @{ ExitCode = $code; Json = $jsonOut; RunId = $runId }
}

function Invoke-ScenarioSuite {
    param(
        [string]$Profile,
        [string]$LockImpl,
        [int]$Port
    )
    foreach ($n in @(50, 100, 200)) {
        for ($r = 1; $r -le 3; $r++) {
            $res = Invoke-Bench -Scenario correctness -Profile $Profile -LockImpl $LockImpl `
                -Concurrency $n -Round $r -Port $Port
            if (-not (Test-Path -LiteralPath $res.Json)) {
                $runNotes.aborted = $true
                $runNotes.abort_reason = "correctness JSON missing for $Profile N=$n round=$r"
                Save-Notes
                throw $runNotes.abort_reason
            }
            $data = Get-Content -LiteralPath $res.Json -Raw -Encoding utf8 | ConvertFrom-Json
            if ([int]$data.success_201 -gt 1) {
                $runNotes.aborted = $true
                $runNotes.abort_reason = "FATAL more than one 201 on $Profile N=$n round=$r success_201=$($data.success_201)"
                $runNotes.bugs += $runNotes.abort_reason
                Save-Notes
                throw $runNotes.abort_reason
            }
            if ($res.ExitCode -eq 2 -and [int]$data.success_201 -gt 1) {
                throw $runNotes.abort_reason
            }
        }
    }
    foreach ($pair in @(@{C=50;T=500}, @{C=100;T=500})) {
        $null = Invoke-Bench -Scenario performance -Profile $Profile -LockImpl $LockImpl `
            -Concurrency $pair.C -Round 1 -Total $pair.T -Port $Port
    }
}

function Write-ResultsDoc {
    $out = Join-Path $benchDir "RESULTS.md"
    $code = & py -3 (Join-Path $benchDir "write_results.py") --bench-dir $benchDir --out $out
    if ($LASTEXITCODE -ne 0) { Log "write_results.py exit=$LASTEXITCODE" }
    Log "RESULTS.md written"
}

try {
    Log "Orchestrator start root=$root"
    if (-not $SkipBuild) {
        $buildOut = Join-Path $logsDir "$ts-maven-package.stdout.log"
        $buildErr = Join-Path $logsDir "$ts-maven-package.stderr.log"
        Log "mvnw -DskipTests package"
        $mvnCode = Invoke-Logged -FilePath (Join-Path $root "mvnw.cmd") -ArgumentList @("-DskipTests", "package") `
            -StdoutPath $buildOut -StderrPath $buildErr
        if ($mvnCode -ne 0) { throw "Maven package failed, see $buildOut / $buildErr" }
    }

    if (-not $SkipH2) {
        $h2Pid = $null
        try {
            $h2Pid = Start-H2Backend -Port $BackendPort
            Invoke-ScenarioSuite -Profile "h2-local" -LockImpl "LocalReservationLock" -Port $BackendPort
        } catch {
            Log "H2 suite error: $($_.Exception.Message)"
            if (-not $runNotes.environments.Contains("h2-local")) {
                $runNotes.environments["h2-local"] = [ordered]@{
                    status = "failed"
                    detail = $_.Exception.Message
                }
            } elseif ($runNotes.environments["h2-local"].status -eq "up") {
                $runNotes.environments["h2-local"].detail += " | suite error: $($_.Exception.Message)"
            }
            Save-Notes
            if ($runNotes.aborted) { throw }
        } finally {
            if ($h2Pid) { Stop-Tracked $h2Pid }
        }
    }

    if (-not $SkipProduction) {
        $mwOut = Join-Path $logsDir "$ts-start-middleware.stdout.log"
        $mwErr = Join-Path $logsDir "$ts-start-middleware.stderr.log"
        $prodLog = Join-Path $logsDir "$ts-backend-mysql-redis.stdout.log"
        $prodPidFile = Join-Path $pidDir "backend-mysql-redis.pid"
        try {
            Log "Starting middleware via wsl-docker-lib.ps1 (ErrorAction=Continue; docker progress on stderr is not fatal)"
            . (Join-Path $root "scripts\wsl-docker-lib.ps1")
            $envFile = Join-Path $root ".env"
            if (-not (Test-Path -LiteralPath $envFile)) { throw "Missing .env for production profile" }
            . (Join-Path $root "scripts\load-env.ps1") -EnvFile $envFile
            $prevEap = $ErrorActionPreference
            $ErrorActionPreference = "Continue"
            try {
                Start-Transcript -Path $mwOut -Force | Out-Null
                if (-not (Test-HostDocker)) {
                    Log "Host docker missing; starting WSL keepalive + dockerd"
                    [void](Start-WslKeepalive -ProjectRoot $root)
                } else {
                    Log "Using host Docker"
                }
                Log "docker compose up -d"
                $mwCode = Invoke-LabflowDocker -ProjectRoot $root -DockerArgs @("compose", "--env-file", ".env", "up", "-d")
                Log "docker compose up exit=$mwCode"
                if ($null -ne $mwCode -and $mwCode -ne 0) {
                    throw "docker compose up exit=$mwCode"
                }
                Log "Waiting for mysql/redis/rabbitmq health"
                $healthyMw = Wait-MiddlewareHealthy -ProjectRoot $root -TimeoutSeconds 180
                if (-not $healthyMw) {
                    throw "Middleware containers did not become healthy within 180s"
                }
                Log "Middleware healthy"
            } catch {
                $_ | Out-String | Set-Content -LiteralPath $mwErr -Encoding utf8
                throw
            } finally {
                try { Stop-Transcript | Out-Null } catch {}
                $ErrorActionPreference = $prevEap
            }
            if (-not (Test-Path $mwErr)) { Set-Content -LiteralPath $mwErr -Value "" -Encoding utf8 }

            $envFile = Join-Path $root ".env"
            if (-not (Test-Path -LiteralPath $envFile)) { throw "Missing .env for production profile" }
            . (Join-Path $root "scripts\load-env.ps1") -EnvFile $envFile
            $jar = Get-Jar
            $prodArgs = @(
                "-jar", $jar.FullName,
                "--spring.profiles.active=production",
                "--server.port=$BackendPort",
                "--logging.file.name=$prodLog",
                "--labops.jwt.refresh-cookie-secure=false"
            )
            Log "Starting production backend as orchestrator child: $javaExe $($prodArgs -join ' ')"
            Stop-Tracked $prodPidFile
            $prodProc = Start-Process -FilePath $javaExe -ArgumentList $prodArgs -WorkingDirectory $root `
                -WindowStyle Hidden -PassThru
            Set-Content -LiteralPath $prodPidFile -Value ([string]$prodProc.Id) -NoNewline
            if (-not (Wait-Health -Port $BackendPort -TimeoutSec 120)) {
                $logExcerpt = ""
                if (Test-Path $prodLog) {
                    $raw = Get-Content $prodLog -Raw -ErrorAction SilentlyContinue
                    if ($raw) { $logExcerpt = $raw.Substring([Math]::Max(0, $raw.Length - 2500)) }
                }
                throw "production backend failed health check. log excerpt:`n$logExcerpt"
            }
            $runNotes.environments["mysql-redis"] = [ordered]@{
                status      = "up"
                detail      = "production profile. MySQL + RedisReservationLock. port=$BackendPort pid=$($prodProc.Id)"
                stdout_log  = ("benchmark/logs/{0}" -f (Split-Path $mwOut -Leaf))
                stderr_log  = ("benchmark/logs/{0}" -f (Split-Path $mwErr -Leaf))
                backend_log = ("benchmark/logs/{0}" -f (Split-Path $prodLog -Leaf))
                pid         = $prodProc.Id
                port        = $BackendPort
            }
            Save-Notes
            Invoke-ScenarioSuite -Profile "mysql-redis" -LockImpl "RedisReservationLock" -Port $BackendPort
        } catch {
            $msg = $_.Exception.Message
            Log "Production path failed: $msg"
            if (-not $runNotes.environments.Contains("mysql-redis")) {
                $runNotes.environments["mysql-redis"] = [ordered]@{
                    status        = "failed"
                    detail        = "production/Redis 未能跑通：$msg"
                    stdout_log    = ("benchmark/logs/{0}" -f (Split-Path $mwOut -Leaf))
                    stderr_log    = ("benchmark/logs/{0}" -f (Split-Path $mwErr -Leaf))
                    backend_log   = ("benchmark/logs/{0}" -f (Split-Path $prodLog -Leaf))
                    error_excerpt = $msg
                }
            } else {
                $runNotes.environments["mysql-redis"].detail += " | suite error: $msg"
            }
            Save-Notes
            if ($runNotes.aborted) { throw }
        } finally {
            Stop-Tracked $prodPidFile
            try { & (Join-Path $root "scripts\stop-production.ps1") } catch { Log "stop-production: $($_.Exception.Message)" }
            try { & (Join-Path $root "scripts\stop-middleware.ps1") } catch { Log "stop-middleware: $($_.Exception.Message)" }
        }
    }
} finally {
    Stop-Tracked (Join-Path $pidDir "backend-h2.pid")
    if (Test-Path (Join-Path $root ".runtime\backend-production.pid")) {
        try { & (Join-Path $root "scripts\stop-production.ps1") } catch {}
    }
    Save-Notes
    try { Write-ResultsDoc } catch { Log "write results failed: $($_.Exception.Message)" }
    Log "Orchestrator finished"
}
