param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [int]$ServerPort = 0,
    [switch]$SkipStart
)

$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $ProjectRoot

$envFile = Join-Path $ProjectRoot ".env"
if (-not (Test-Path -LiteralPath $envFile)) {
    Copy-Item (Join-Path $ProjectRoot ".env.example") $envFile
}
. (Join-Path $PSScriptRoot "load-env.ps1") -EnvFile $envFile

if ($ServerPort -le 0) {
    if ($env:SERVER_PORT) { $ServerPort = [int]$env:SERVER_PORT } else { $ServerPort = 18080 }
}

$logDir = Join-Path $ProjectRoot ".verify-logs"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$report = Join-Path $logDir ("verify-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".log")
function Log([string]$msg) {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $msg
    Write-Host $line
    Add-Content -LiteralPath $report -Value $line
}

function JwtAuth([string]$user, [string]$pass) {
    $body = @{ username = $user; password = $pass } | ConvertTo-Json -Compress
    $resp = Invoke-RestMethod -Method POST -Uri "http://127.0.0.1:$ServerPort/api/auth/login" `
        -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 10
    return "Bearer $($resp.accessToken)"
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Url,
        [string]$Auth,
        [hashtable]$Body = $null
    )
    $headers = @{ Authorization = $Auth; Accept = "application/json" }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Compress -Depth 8
        return Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($json))
    }
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers
}

Log "=== LabFlow production stack verification ==="
Log "Report file: $report"

if (-not $SkipStart) {
    Log "Starting middleware + production backend..."
    & (Join-Path $PSScriptRoot "start-production.ps1") `
        -ProjectRoot $ProjectRoot `
        -ServerPort $ServerPort `
        -ApprovalTimeout "12s" `
        -AllowInsecureRefreshCookieForLocalHttp `
        -EnableDemoData
}

# 1) Health (authenticated to see component details)
Log "--- 1) Actuator health ---"
$health = Invoke-RestMethod -Uri "http://127.0.0.1:$ServerPort/actuator/health" -TimeoutSec 5
$healthJson = $health | ConvertTo-Json -Depth 10
Log $healthJson
if ($health.status -ne "UP") { throw "Health status is not UP" }
if ($health.components) {
    foreach ($name in @("db", "redis", "rabbit")) {
        $comp = $health.components.$name
        if ($comp -and $comp.status -ne "UP") {
            throw "$name health is not UP: $($comp.status)"
        }
        if ($comp) { Log "PASS component $name = $($comp.status)" }
    }
} else {
    Log "WARN: health components hidden (unauthorized). status=UP still required."
}
Log "PASS overall health UP"

# 2) Flyway on MySQL
Log "--- 2) Flyway migrations on MySQL ---"
$u = $env:MYSQL_USER
$p = $env:MYSQL_PASSWORD
$d = $env:MYSQL_DATABASE
$flywayOut = wsl -d Ubuntu -u root -- docker exec -e "MYSQL_PWD=$p" labflow-mysql mysql -N -u"$u" "$d" -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;" 2>$null | Out-String
Log $flywayOut.Trim()
if ($flywayOut -notmatch "(?m)^\s*1\s+init\s+1\s*$") { throw "Flyway V1 not found in MySQL" }
if ($flywayOut -notmatch "equipment profile") { throw "Flyway V6 not found in MySQL" }
if ($flywayOut -notmatch "(?m)^\s*6\s+") { throw "Flyway V6 version row missing" }
if ($flywayOut -notmatch "refresh tokens") { throw "Flyway V7 refresh tokens migration not found in MySQL" }
if ($flywayOut -notmatch "(?m)^\s*7\s+") { throw "Flyway V7 version row missing" }
Log "PASS Flyway V1..V7 present and successful in MySQL (including refresh_tokens)"

# 2b) JWT login + HttpOnly Cookie rotation + revoked old refresh => 401
Log "--- 2b) JWT login / HttpOnly refresh Cookie rotation / revoked refresh ---"
$loginBody = @{ username = "admin"; password = "admin123" } | ConvertTo-Json -Compress
$authUri = [Uri]"http://127.0.0.1:$ServerPort/api/auth"
$loginResponse = Invoke-WebRequest -Method POST -Uri "http://127.0.0.1:$ServerPort/api/auth/login" `
    -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($loginBody)) `
    -SessionVariable authSession -UseBasicParsing -TimeoutSec 10
$login = $loginResponse.Content | ConvertFrom-Json
$loginSetCookie = [string]$loginResponse.Headers["Set-Cookie"]
if (-not $login.accessToken) { throw "Login missing accessToken" }
if ($null -ne $login.PSObject.Properties["refreshToken"]) { throw "Login exposed refreshToken in JSON" }
if ($loginSetCookie -notmatch "(?i)\bHttpOnly\b" -or
    $loginSetCookie -notmatch "(?i)\bSameSite=Lax\b" -or
    $loginSetCookie -notmatch "(?i)\bPath=/api/auth(?:;|$)") {
    throw "Login refresh Cookie is missing HttpOnly, SameSite=Lax, or Path=/api/auth: $loginSetCookie"
}
if ($loginSetCookie -match "(?i)(?:^|;)\s*Secure(?:;|$)") {
    if ($SkipStart) {
        throw ("-SkipStart cannot override the already-running backend's Secure Cookie over local HTTP. " +
            "Restart it with .\scripts\start-production.ps1 -AllowInsecureRefreshCookieForLocalHttp, " +
            "or verify the deployment through HTTPS.")
    }
    throw "Local HTTP verifier started a backend that still returned a Secure refresh Cookie."
}
$oldRefresh = $authSession.Cookies.GetCookies($authUri)["labflow_refresh"].Value
if (-not $oldRefresh) { throw "Login missing HttpOnly refresh Cookie" }
Log "PASS JWT login returned access and stored refresh only in Cookie"

$refreshed = Invoke-RestMethod -Method POST -Uri "http://127.0.0.1:$ServerPort/api/auth/refresh" `
    -WebSession $authSession -TimeoutSec 10
if (-not $refreshed.accessToken) { throw "Refresh missing new access token" }
if ($null -ne $refreshed.PSObject.Properties["refreshToken"]) { throw "Refresh exposed refreshToken in JSON" }
$newRefresh = $authSession.Cookies.GetCookies($authUri)["labflow_refresh"].Value
if (-not $newRefresh) { throw "Refresh missing rotated refresh Cookie" }
if ($newRefresh -eq $oldRefresh) {
    throw "Refresh did not rotate refresh token"
}
Log "PASS refresh rotation issued a new HttpOnly Cookie"

$reuseStatus = -1
$oldTokenSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$oldTokenSession.Cookies.SetCookies($authUri, "labflow_refresh=$oldRefresh; Path=/api/auth")
try {
    $reuseResponse = Invoke-WebRequest -Method POST -Uri "http://127.0.0.1:$ServerPort/api/auth/refresh" `
        -WebSession $oldTokenSession -UseBasicParsing -TimeoutSec 10
    $reuseStatus = [int]$reuseResponse.StatusCode
} catch {
    if ($_.Exception.Response) {
        $reuseStatus = [int]$_.Exception.Response.StatusCode.value__
    }
}
if ($reuseStatus -ne 401) {
    throw "Expected old refresh token to return 401, got $reuseStatus"
}
Log "PASS old refresh token rejected with 401"

# 3) Redis lock evidence via concurrent reservation
Log "--- 3) Redis reservation lock concurrent create ---"
$adminAuth = JwtAuth "admin" "admin123"
$studentAuth = JwtAuth "student" "student123"
$code = "DOCKER-" + (Get-Random -Maximum 999999)
$equipment = Invoke-Json -Method POST -Url "http://127.0.0.1:$ServerPort/api/equipment" -Auth $adminAuth -Body @{
    code = $code
    name = "Docker-lock-verify-equipment"
    category = "verify"
    location = "Docker-Lab"
}
$equipmentId = $equipment.id
Log "Created equipment id=$equipmentId code=$code"

$start = (Get-Date).ToUniversalTime().AddDays(5).ToString("yyyy-MM-ddTHH:mm:ssZ")
$end = (Get-Date).ToUniversalTime().AddDays(5).AddHours(2).ToString("yyyy-MM-ddTHH:mm:ssZ")
$bodyJson = (@{
    equipmentId = $equipmentId
    purpose = "Redis-lock-concurrent-verify"
    startTime = $start
    endTime = $end
} | ConvertTo-Json -Compress)

$jobs = 1..2 | ForEach-Object {
    Start-Job -ScriptBlock {
        param($url, $auth, $json)
        try {
            $resp = Invoke-WebRequest -Method POST -Uri $url -Headers @{ Authorization = $auth; "Content-Type" = "application/json" } -Body $json -UseBasicParsing
            return [int]$resp.StatusCode
        } catch {
            if ($_.Exception.Response) {
                return [int]$_.Exception.Response.StatusCode.value__
            }
            return -1
        }
    } -ArgumentList "http://127.0.0.1:$ServerPort/api/reservations", $studentAuth, $bodyJson
}
$statuses = @($jobs | ForEach-Object { Receive-Job -Job $_ -Wait })
$jobs | Remove-Job -Force
Log ("Concurrent reservation HTTP statuses: " + ($statuses -join ", "))
$has201 = $statuses -contains 201
$has409 = $statuses -contains 409
if (-not ($has201 -and $has409)) {
    throw "Expected concurrent reservation statuses {201,409}, got {$($statuses -join ',')}"
}

$backendLog = Join-Path $ProjectRoot ".runtime\backend-production.log"
if (-not (Test-Path -LiteralPath $backendLog)) {
    $backendLog = Join-Path $ProjectRoot ".runtime\backend-production.stdout.log"
}
$redisLogHit = @(Select-String -Path $backendLog -Pattern "Redis reservation lock acquired" -ErrorAction SilentlyContinue | Select-Object -Last 5)
if ($redisLogHit.Count -lt 1) {
    throw "Backend log missing Redis lock acquire evidence"
}
Log "Redis lock log samples:"
$redisLogHit | ForEach-Object { Log $_.Line }
Log "PASS Redis lock used for concurrent reservation"

# 4) RabbitMQ delayed expiry path (per-reservation delay queue, no shared FIFO HOL)
Log "--- 4) RabbitMQ reservation expiry path ---"
$start2 = (Get-Date).ToUniversalTime().AddDays(6).ToString("yyyy-MM-ddTHH:mm:ssZ")
$end2 = (Get-Date).ToUniversalTime().AddDays(6).AddHours(1).ToString("yyyy-MM-ddTHH:mm:ssZ")
$reservation = Invoke-Json -Method POST -Url "http://127.0.0.1:$ServerPort/api/reservations" -Auth $studentAuth -Body @{
    equipmentId = $equipmentId
    purpose = "RabbitMQ-expiry-path-verify"
    startTime = $start2
    endTime = $end2
}
$reservationId = $reservation.id
Log "Created reservation id=$reservationId expiresAt=$($reservation.expiresAt) status=$($reservation.status)"

Start-Sleep -Seconds 2
$rabbitSchedule = @(Select-String -Path $backendLog -Pattern "RabbitMQ expiry scheduled reservationId=$reservationId" -ErrorAction SilentlyContinue | Select-Object -Last 1)
if ($rabbitSchedule.Count -lt 1) {
    throw "Backend log missing RabbitMQ expiry schedule evidence for reservation $reservationId"
}
Log $rabbitSchedule[0].Line
if ($rabbitSchedule[0].Line -notmatch "delayQueue=labops\.reservation\.expiry\.delay\.$reservationId\.") {
    throw "Expected per-reservation delayQueue in schedule log (not shared FIFO). Line=$($rabbitSchedule[0].Line)"
}
if ($rabbitSchedule[0].Line -notmatch "per-queue TTL") {
    throw "Expected per-queue TTL schedule path in log"
}
Log "PASS schedule uses per-reservation delay queue (not shared FIFO + per-message TTL)"

$queueInfo = wsl -d Ubuntu -u root -- bash -lc "docker exec labflow-rabbitmq rabbitmqctl list_queues name messages consumers" 2>&1 | Out-String
Log "RabbitMQ queues:"
Log $queueInfo.Trim()
if ($queueInfo -notmatch "labops.reservation.expiry.queue") {
    throw "Expected labops.reservation.expiry.queue not found in RabbitMQ"
}
# Shared legacy delay queue must not be the active delay path (may be absent after cleanup).
if ($queueInfo -match "(?m)^labops\.reservation\.expiry\.delay\.queue\s+[1-9]") {
    throw "Legacy shared delay queue still holds messages (HOL risk). Queue listing:`n$queueInfo"
}
Log "PASS RabbitMQ work queue present; legacy shared delay queue not holding traffic"

$expired = $false
for ($i = 0; $i -lt 45; $i++) {
    Start-Sleep -Seconds 1
    $consumeHit = @(Select-String -Path $backendLog -Pattern "RabbitMQ expiry message consumed reservationId=$reservationId" -ErrorAction SilentlyContinue | Select-Object -Last 1)
    if ($consumeHit.Count -ge 1) {
        Log $consumeHit[0].Line
        $processHit = @(Select-String -Path $backendLog -Pattern "RabbitMQ expiry processed reservationId=$reservationId" -ErrorAction SilentlyContinue | Select-Object -Last 1)
        if ($processHit.Count -ge 1) { Log $processHit[0].Line }
        $expired = $true
        break
    }
}
if (-not $expired) {
    throw "Timed out waiting for RabbitMQ expiry consumer log for reservation $reservationId"
}

$expiredUrl = "http://127.0.0.1:$ServerPort/api/reservations?status=EXPIRED&size=50"
$rows = Invoke-Json -Method GET -Url $expiredUrl -Auth $studentAuth
$found = @($rows.content | Where-Object { $_.id -eq $reservationId })
if ($found.Count -lt 1) {
    throw "Reservation $reservationId not found in EXPIRED list after Rabbit consumer"
}
Log "Reservation $reservationId status after Rabbit path: $($found[0].status)"
Log "PASS RabbitMQ expiry path end-to-end"

# 4b) HOL regression on real broker: long delay first, short must expire first
Log "--- 4b) HOL ordering (long delay first, short must fire first) ---"
$startL = (Get-Date).ToUniversalTime().AddDays(8).ToString("yyyy-MM-ddTHH:mm:ssZ")
$endL = (Get-Date).ToUniversalTime().AddDays(8).AddHours(1).ToString("yyyy-MM-ddTHH:mm:ssZ")
$longRes = Invoke-Json -Method POST -Url "http://127.0.0.1:$ServerPort/api/reservations" -Auth $studentAuth -Body @{
    equipmentId = $equipmentId
    purpose = "HOL-long-delay-first"
    startTime = $startL
    endTime = $endL
}
$startS = (Get-Date).ToUniversalTime().AddDays(9).ToString("yyyy-MM-ddTHH:mm:ssZ")
$endS = (Get-Date).ToUniversalTime().AddDays(9).AddHours(1).ToString("yyyy-MM-ddTHH:mm:ssZ")
$shortRes = Invoke-Json -Method POST -Url "http://127.0.0.1:$ServerPort/api/reservations" -Auth $studentAuth -Body @{
    equipmentId = $equipmentId
    purpose = "HOL-short-delay-second"
    startTime = $startS
    endTime = $endS
}
$longId = [int64]$longRes.id
$shortId = [int64]$shortRes.id
Log "Created HOL pair longId=$longId shortId=$shortId"

# Align DB expires_at with the delays we will schedule (app auto-schedule used shared timeout;
# expireIfPending requires expires_at <= now at fire time).
$longDelayMs = 25000
$shortDelayMs = 2000
$longExpEpoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + $longDelayMs
$shortExpEpoch = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + $shortDelayMs
$mysqlUser = $env:MYSQL_USER
$mysqlPass = $env:MYSQL_PASSWORD
$mysqlDb = $env:MYSQL_DATABASE
$sql = "UPDATE equipment_reservations SET expires_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 25 SECOND) WHERE id=$longId; UPDATE equipment_reservations SET expires_at = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 SECOND) WHERE id=$shortId;"
$mysqlOut = wsl -d Ubuntu -u root -- docker exec -e "MYSQL_PWD=$mysqlPass" labflow-mysql mysql -u"$mysqlUser" "$mysqlDb" -e "$sql" 2>&1 | Out-String
Log ("MySQL expires_at update: " + $mysqlOut.Trim())

function Declare-LabflowDelayQueue([string]$QueueName, [int64]$TtlMs) {
    $user = $env:RABBITMQ_USERNAME
    $pass = $env:RABBITMQ_PASSWORD
    $port = if ($env:RABBITMQ_MANAGEMENT_PORT) { $env:RABBITMQ_MANAGEMENT_PORT } else { "15672" }
    $pair = "{0}:{1}" -f $user, $pass
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
    $headers = @{ Authorization = "Basic $basic"; "Content-Type" = "application/json" }
    $argsObj = [ordered]@{
        "x-message-ttl" = $TtlMs
        "x-dead-letter-exchange" = "labops.reservation.expiry.exchange"
        "x-dead-letter-routing-key" = "reservation.expire"
        "x-expires" = ($TtlMs + 60000)
    }
    $body = @{ durable = $true; auto_delete = $false; arguments = $argsObj } | ConvertTo-Json -Compress -Depth 6
    $uri = "http://127.0.0.1:$port/api/queues/%2F/$([uri]::EscapeDataString($QueueName))"
    Invoke-RestMethod -Method PUT -Uri $uri -Headers $headers -Body $body -TimeoutSec 10 | Out-Null
}

function Publish-LabflowDelayMessage([string]$QueueName, [string]$Payload) {
    $user = $env:RABBITMQ_USERNAME
    $pass = $env:RABBITMQ_PASSWORD
    $port = if ($env:RABBITMQ_MANAGEMENT_PORT) { $env:RABBITMQ_MANAGEMENT_PORT } else { "15672" }
    $pair = "{0}:{1}" -f $user, $pass
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
    $headers = @{ Authorization = "Basic $basic"; "Content-Type" = "application/json" }
    $body = @{
        properties = @{}
        routing_key = $QueueName
        payload = $Payload
        payload_encoding = "string"
    } | ConvertTo-Json -Compress -Depth 5
    $uri = "http://127.0.0.1:$port/api/exchanges/%2F/amq.default/publish"
    $resp = Invoke-RestMethod -Method POST -Uri $uri -Headers $headers -Body $body -TimeoutSec 10
    if (-not $resp.routed) { throw "Publish to $QueueName was not routed" }
}

$longQueue = "labops.reservation.expiry.delay.$longId.$longExpEpoch"
$shortQueue = "labops.reservation.expiry.delay.$shortId.$shortExpEpoch"
# Long delay FIRST (this is the HOL scenario that broke the shared FIFO design)
Declare-LabflowDelayQueue -QueueName $longQueue -TtlMs $longDelayMs
Publish-LabflowDelayMessage -QueueName $longQueue -Payload ([string]$longId)
Declare-LabflowDelayQueue -QueueName $shortQueue -TtlMs $shortDelayMs
Publish-LabflowDelayMessage -QueueName $shortQueue -Payload ([string]$shortId)
Log "Published HOL messages longQueue=$longQueue shortQueue=$shortQueue"

$holOk = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Seconds 1
    $page = Invoke-Json -Method GET -Url "http://127.0.0.1:$ServerPort/api/reservations?size=100&sort=createdAt,desc" -Auth $studentAuth
    $longRow = @($page.content | Where-Object { $_.id -eq $longId }) | Select-Object -First 1
    $shortRow = @($page.content | Where-Object { $_.id -eq $shortId }) | Select-Object -First 1
    if ($null -eq $longRow -or $null -eq $shortRow) { continue }
    Log "HOL poll t=${i}s short=$($shortRow.status) long=$($longRow.status)"
    if ($shortRow.status -eq "EXPIRED" -and $longRow.status -eq "PENDING") {
        $holOk = $true
        break
    }
    if ($longRow.status -eq "EXPIRED" -and $shortRow.status -ne "EXPIRED") {
        throw "HOL failure: long reservation expired before short (shared FIFO symptom)"
    }
}
if (-not $holOk) {
    throw "HOL failure: short reservation did not expire while long remained PENDING within 20s"
}
Log "PASS HOL ordering on production broker: short expired first; long still PENDING"

Log "=== ALL CORE CHECKS PASSED ==="
Log "Artifacts: $report"
Write-Host ""
Write-Host "Verification report: $report"
