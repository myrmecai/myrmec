# Start Myrmec Agent
# Usage: .\start-agent.ps1 [-EnvFile "..\agent-registration.env"] [-AgentScript "simple_agent.py"]

param(
    [string]$EnvFile = "..\agent-registration.env",
    [string]$AgentScript = "simple_agent.py"
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvPath = Join-Path $ScriptDir $EnvFile
$AgentsDir = Join-Path $ScriptDir "..\agents"
$ExamplesDir = Join-Path $AgentsDir "examples"

# Check if env file exists
if (-not (Test-Path $EnvPath)) {
    Write-Host "Error: Registration key file not found: $EnvPath" -ForegroundColor Red
    Write-Host ""
    Write-Host "Run first: .\create-registration-key.ps1" -ForegroundColor Yellow
    exit 1
}

# Load environment variables from file
Write-Host "Loading environment from: $EnvPath" -ForegroundColor Cyan
Get-Content $EnvPath | ForEach-Object {
    if ($_ -match '^\s*([^#][^=]+)=(.*)$') {
        $name = $matches[1].Trim()
        $value = $matches[2].Trim()
        [Environment]::SetEnvironmentVariable($name, $value, "Process")
        Write-Host "  $name = $($value.Substring(0, [Math]::Min(20, $value.Length)))..." -ForegroundColor Gray
    }
}
Write-Host ""

# Verify required variables
if (-not $env:MYRMEC_REGISTRATION_KEY) {
    Write-Host "Error: MYRMEC_REGISTRATION_KEY not set" -ForegroundColor Red
    exit 1
}

# Change to agents directory and setup virtual environment
Set-Location $AgentsDir

# Check for virtual environment
$VenvPath = Join-Path $AgentsDir ".venv"
$VenvPython = Join-Path $VenvPath "Scripts\python.exe"

if (-not (Test-Path $VenvPython)) {
    Write-Host "Creating virtual environment..." -ForegroundColor Yellow
    python -m venv .venv
}

# Activate virtual environment
$ActivateScript = Join-Path $VenvPath "Scripts\Activate.ps1"
. $ActivateScript

# Install agent package in development mode
Write-Host "Installing myrmec-agent package..." -ForegroundColor Yellow
pip install -e . -q

Write-Host ""
Write-Host "Starting Myrmec Agent..." -ForegroundColor Green
Write-Host "  Name: $env:MYRMEC_AGENT_NAME" -ForegroundColor Yellow
Write-Host "  Engine URL: $env:MYRMEC_ENGINE_URL" -ForegroundColor Yellow
Write-Host "  Heartbeat: $env:MYRMEC_HEARTBEAT_INTERVAL seconds" -ForegroundColor Yellow
Write-Host ""
Write-Host "Press Ctrl+C to stop" -ForegroundColor Gray
Write-Host ""

# Run the agent
$AgentPath = Join-Path $ExamplesDir $AgentScript
python $AgentPath
