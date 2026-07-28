param(
    [string]$JavaPath = ""
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "scripts\resolve-java.ps1")
$null = Use-LabFlowJava -JavaPath $JavaPath

& "$PSScriptRoot\mvnw.cmd" spring-boot:run "-Dspring-boot.run.arguments=--server.port=18080"
exit $LASTEXITCODE
