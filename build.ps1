$ErrorActionPreference = "Stop"

$jdk = "C:\Program Files\Java\jdk-25.0.2"
if (-not (Test-Path "$jdk\bin\java.exe")) {
    throw "未找到 JDK：$jdk"
}

$env:JAVA_HOME = $jdk
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
