$ErrorActionPreference = "Stop"

$jdk = "C:\Program Files\Java\jdk-25.0.2"
if (-not (Test-Path "$jdk\bin\java.exe")) {
    throw "未找到 JDK：$jdk"
}

$env:JAVA_HOME = $jdk
& "$PSScriptRoot\mvnw.cmd" spring-boot:run "-Dspring-boot.run.arguments=--server.port=18080"
exit $LASTEXITCODE