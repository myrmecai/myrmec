# Start Myrmec Chat Agent (TypeScript @myrmec/agent runtime)
# Usage: .\start-chat-agent.ps1
#
# Loads the chat agent registration key from chat-agent-registration.env
# and runs the headless TypeScript agent supervisor (src/bin/headless.ts).
# The chat agent connects to the engine and waits for conversational turns
# dispatched from the "Chat with Assistant" conversation.

param(
    [string]$EnvFile = "..\chat-agent-registration.env"
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvPath = Join-Path $ScriptDir $EnvFile
$AgentsDir = Join-Path $ScriptDir "..\agents"

# Check if env file exists
if (-not (Test-Path $EnvPath)) {
    Write-Host "Error: Chat agent registration key file not found: $EnvPath" -ForegroundColor Red
    Write-Host ""
    Write-Host "Run first: .\setup-e2e-data.ps1" -ForegroundColor Yellow
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

# Change to agents directory
Set-Location $AgentsDir

# Ensure dependencies are installed
if (-not (Test-Path (Join-Path $AgentsDir "node_modules"))) {
    Write-Host "Installing agent dependencies (npm install)..." -ForegroundColor Yellow
    npm install --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw "npm install failed" }
}

Write-Host ""
Write-Host "Starting Myrmec Chat Agent (TypeScript headless supervisor)..." -ForegroundColor Green
Write-Host "  Engine URL: $env:MYRMEC_ENGINE_URL" -ForegroundColor Yellow
Write-Host "  Model: ollama-llama3.2 (via Ollama at localhost:11434)" -ForegroundColor Yellow
Write-Host "  Press Ctrl+C to stop" -ForegroundColor Gray
Write-Host ""

# Run the TypeScript headless agent via tsx
npx tsx src/bin/headless.ts