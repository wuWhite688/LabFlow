param(
    [string]$JavaPath = ""
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "scripts\resolve-java.ps1")
$null = Use-LabFlowJava -JavaPath $JavaPath

# package runs unit/integration tests then produces the runnable Spring Boot JAR under target/.
& "$PSScriptRoot\mvnw.cmd" package
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$jar = Get-ChildItem "$PSScriptRoot\target\lab-equipment-platform-*.jar" |
    Where-Object { $_.Name -notlike "*.original" } |
    Select-Object -First 1
if (-not $jar) {
    throw "Build finished but runnable JAR was not found under target/."
}

Write-Host "Build OK: $($jar.FullName) ($([math]::Round($jar.Length / 1MB, 1)) MB)"
exit 0
