# Start Myrmec Control Engine
# Usage: .\start-engine.ps1
#
# Optional parameters:
#   -JavaHome "C:\jdk\jdk-21.0.6"
#   -SkipJavaHomeOverride
#   -SkipUserPathUpdate
#
# Environment variables:
#   MYRMEC_ADMIN_EMAIL    - Initial admin email (required on first run)
#   MYRMEC_ADMIN_PASSWORD - Initial admin password (required on first run)
#   MYRMEC_ADMIN_NAME     - Initial admin name (optional, default: System Administrator)

param(
    [string]$JavaHome = "C:\jdk\jdk-21.0.6",
    [switch]$SkipJavaHomeOverride,
    [switch]$SkipUserPathUpdate
)

$ErrorActionPreference = "Stop"

function Write-Step([string]$Message) {
    Write-Host $Message -ForegroundColor Cyan
}

function Add-ToUserPath([string]$Entry) {
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $parts = @()
    if ($userPath) {
        $parts = $userPath.Split(';') | Where-Object { $_ -and $_.Trim() -ne '' }
    }

    $normalizedEntry = $Entry.Trim().TrimEnd('\\')
    $alreadyExists = $parts | Where-Object {
        $_.Trim().TrimEnd('\\').ToLowerInvariant() -eq $normalizedEntry.ToLowerInvariant()
    }

    if ($alreadyExists) {
        Write-Step "JDK bin already present in User PATH: $Entry"
        return
    }

    $newPath = if ($userPath -and $userPath.Trim() -ne '') {
        "$userPath;$Entry"
    } else {
        $Entry
    }

    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
    Write-Step "Added JDK bin to User PATH: $Entry"
}

function Resolve-JavaExecutable {
    $candidates = @()

    if ($env:JAVA_HOME) {
        $candidates += (Join-Path $env:JAVA_HOME "bin\java.exe")
    }

    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source) {
        $candidates += $cmd.Source
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    return $null
}

# Optionally set Java 21 path used by this project
if (-not $SkipJavaHomeOverride) {
    if (Test-Path $JavaHome) {
        $jdkBin = Join-Path $JavaHome "bin"
        $env:JAVA_HOME = $JavaHome
        $env:PATH = "$jdkBin;$env:PATH"
        Write-Step "JAVA_HOME set to: $env:JAVA_HOME"

        if (-not $SkipUserPathUpdate) {
            Add-ToUserPath $jdkBin
        }
    } else {
        Write-Host "Configured JavaHome does not exist: $JavaHome" -ForegroundColor Yellow
        Write-Host "Continuing with current JAVA_HOME/PATH..." -ForegroundColor Yellow
    }
}

# Validate java availability
$javaExe = Resolve-JavaExecutable
if (-not $javaExe) {
    Write-Host "Java is not available in PATH. Install/configure Java 21 and retry." -ForegroundColor Red
    Write-Host "JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Yellow
    Write-Host "PATH: $env:PATH" -ForegroundColor DarkYellow
    exit 1
}

# java -version emits to stderr; use Start-Process with redirected streams to avoid NativeCommandError behavior
$stdoutFile = Join-Path $env:TEMP "myrmec-java-stdout.txt"
$stderrFile = Join-Path $env:TEMP "myrmec-java-stderr.txt"

try {
    $javaProc = Start-Process -FilePath $javaExe `
        -ArgumentList "-version" `
        -NoNewWindow `
        -Wait `
        -PassThru `
        -RedirectStandardOutput $stdoutFile `
        -RedirectStandardError $stderrFile

    if ($javaProc.ExitCode -ne 0) {
        Write-Host "Java executable found but could not run (exit code $($javaProc.ExitCode)): $javaExe" -ForegroundColor Red
        exit 1
    }

    $javaVersion = (Get-Content $stderrFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $javaVersion) {
        $javaVersion = (Get-Content $stdoutFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    }
    if (-not $javaVersion) {
        $javaVersion = "Java runtime detected"
    }
}
finally {
    Remove-Item $stdoutFile -ErrorAction SilentlyContinue
    Remove-Item $stderrFile -ErrorAction SilentlyContinue
}

Write-Step "Java executable: $javaExe"
Write-Step "Using: $($javaVersion.ToString().Trim())"

# Validate maven availability
$mvnVersion = & { $ErrorActionPreference = 'SilentlyContinue'; mvn -v 2>&1 | Select-Object -First 1 }
if (-not $mvnVersion) {
    Write-Host "Maven (mvn) is not available in PATH. Install Maven and retry." -ForegroundColor Red
    exit 1
}
Write-Step "Using: $mvnVersion"

# Set default admin credentials if not set (for development only)
if (-not $env:MYRMEC_ADMIN_EMAIL) {
    $env:MYRMEC_ADMIN_EMAIL = "admin@myrmec.local"
    Write-Host "Using default admin email: admin@myrmec.local" -ForegroundColor Yellow
}
if (-not $env:MYRMEC_ADMIN_PASSWORD) {
    $env:MYRMEC_ADMIN_PASSWORD = "Admin@123!"
    Write-Host "Using default admin password (CHANGE IN PRODUCTION!)" -ForegroundColor Yellow
}

# Change to engine directory
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$EngineDir = Join-Path $ScriptDir "..\control\engine"

if (-not (Test-Path $EngineDir)) {
    Write-Host "Engine directory not found: $EngineDir" -ForegroundColor Red
    exit 1
}

Set-Location $EngineDir

Write-Host "Starting Myrmec Control Engine..." -ForegroundColor Green
Write-Host "API: http://localhost:9090/api/v1" -ForegroundColor Yellow
Write-Host "Swagger UI: http://localhost:9090/swagger-ui.html" -ForegroundColor Yellow
Write-Host ""

# Start Spring Boot
mvn spring-boot:run
