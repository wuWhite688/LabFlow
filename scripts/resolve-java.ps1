function Get-LabFlowJavaMajorVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$VersionOutput,
        [Parameter(Mandatory = $true)]
        [string]$ToolName
    )

    $version = $null
    if ($ToolName -eq "java" -and
        $VersionOutput -match '(?im)(?:java|openjdk)\s+version\s+"?([0-9]+(?:\.[0-9]+)*)') {
        $version = $Matches[1]
    } elseif ($ToolName -eq "javac" -and
              $VersionOutput -match '(?im)\bjavac\s+([0-9]+(?:\.[0-9]+)*)') {
        $version = $Matches[1]
    }
    if (-not $version) {
        throw "Could not parse $ToolName version output: $VersionOutput"
    }

    $parts = $version.Split(".")
    if ($parts[0] -eq "1" -and $parts.Length -gt 1) {
        return [int]$parts[1]
    }
    return [int]$parts[0]
}

function Invoke-LabFlowVersionCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Executable
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Executable
    $startInfo.Arguments = "-version"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw "Failed to start $Executable -version"
        }
        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "$Executable -version exited with code $($process.ExitCode)"
        }
        return (($stdout, $stderr) -join [Environment]::NewLine).Trim()
    } finally {
        $process.Dispose()
    }
}

function Test-LabFlowJdkPair {
    param(
        [Parameter(Mandatory = $true)]
        [string]$JavaExecutable,
        [Parameter(Mandatory = $true)]
        [string]$JavacExecutable
    )

    $javaExe = (Resolve-Path -LiteralPath $JavaExecutable).Path
    $javacExe = (Resolve-Path -LiteralPath $JavacExecutable).Path
    $javaBin = Split-Path -Parent $javaExe
    $javacBin = Split-Path -Parent $javacExe
    if ($javaBin -ine $javacBin) {
        throw "java.exe and javac.exe must come from the same JDK bin directory: java=$javaExe; javac=$javacExe"
    }
    if ((Split-Path -Leaf $javaBin) -ine "bin") {
        throw "Expected java.exe and javac.exe under a JDK bin directory: $javaBin"
    }

    $javaOutput = Invoke-LabFlowVersionCommand -Executable $javaExe
    $javaMajor = Get-LabFlowJavaMajorVersion -VersionOutput $javaOutput -ToolName "java"

    $javacOutput = Invoke-LabFlowVersionCommand -Executable $javacExe
    $javacMajor = Get-LabFlowJavaMajorVersion -VersionOutput $javacOutput -ToolName "javac"

    if ($javaMajor -ne $javacMajor) {
        throw "java and javac major versions differ: java=$javaMajor; javac=$javacMajor"
    }
    if ($javaMajor -lt 21) {
        throw "JDK 21+ is required; selected JDK major version is $javaMajor ($javaExe)"
    }

    return [pscustomobject]@{
        Executable = $javaExe
        JavaHome = Split-Path -Parent $javaBin
        MajorVersion = $javaMajor
    }
}

function Resolve-LabFlowJava {
    [CmdletBinding()]
    param(
        [string]$JavaPath = ""
    )

    if (-not [string]::IsNullOrWhiteSpace($JavaPath)) {
        $candidate = $JavaPath
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            $binCandidate = if ((Split-Path -Leaf $candidate) -ieq "bin") {
                $candidate
            } else {
                Join-Path $candidate "bin"
            }
            $candidate = Join-Path $binCandidate "java.exe"
        }
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            throw "Java executable not found: $candidate"
        }

        $javaExe = (Resolve-Path -LiteralPath $candidate).Path
        $javaBin = Split-Path -Parent $javaExe
        $javacExe = Join-Path $javaBin "javac.exe"
        if (-not (Test-Path -LiteralPath $javacExe -PathType Leaf)) {
            throw "A full JDK is required; javac.exe was not found beside $javaExe"
        }
        return Test-LabFlowJdkPair -JavaExecutable $javaExe -JavacExecutable $javacExe
    }

    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
        $javacExe = Join-Path $env:JAVA_HOME "bin\javac.exe"
        if ((Test-Path -LiteralPath $javaExe -PathType Leaf) -and
            (Test-Path -LiteralPath $javacExe -PathType Leaf)) {
            return Test-LabFlowJdkPair -JavaExecutable $javaExe -JavacExecutable $javacExe
        }
    }

    $javaCommand = Get-Command java.exe -ErrorAction SilentlyContinue
    $javacCommand = Get-Command javac.exe -ErrorAction SilentlyContinue
    if (-not $javaCommand -or -not $javacCommand) {
        throw "JDK 21+ not found. Set JAVA_HOME, add java/javac to PATH, or pass -JavaPath."
    }

    return Test-LabFlowJdkPair -JavaExecutable $javaCommand.Source -JavacExecutable $javacCommand.Source
}

function Use-LabFlowJava {
    [CmdletBinding()]
    param(
        [string]$JavaPath = ""
    )

    $runtime = Resolve-LabFlowJava -JavaPath $JavaPath
    if ($runtime.JavaHome) {
        $env:JAVA_HOME = $runtime.JavaHome
        Write-Host "Using JDK $($runtime.MajorVersion) from JAVA_HOME=$($runtime.JavaHome)"
    } else {
        if (Test-Path Env:JAVA_HOME) {
            Remove-Item Env:JAVA_HOME
        }
        Write-Host "Using JDK $($runtime.MajorVersion) from PATH: $($runtime.Executable)"
    }
    return $runtime
}
