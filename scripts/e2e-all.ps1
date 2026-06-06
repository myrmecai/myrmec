# scripts/e2e-all.ps1
#
# One-shot orchestrator for the Phase 4 Tier-2 end-to-end suite.
#
# Playwright owns process lifecycle for the engine (Spring Boot, e2e profile,
# port 9090) and the Vite dev server (port 3000) via its `webServer` config —
# see control/ui/playwright.config.ts. This script only ensures the right
# Node/Maven environment, installs the Chromium browser on first run, and
# invokes Playwright. Tests are serialised (single H2 instance).
#
# Usage:
#   .\scripts\e2e-all.ps1                   # full suite, headless
#   .\scripts\e2e-all.ps1 -Ui               # Playwright UI mode (interactive)
#   .\scripts\e2e-all.ps1 -SkipInstall      # skip `npm install` + `playwright install`

param(
    [switch]$Ui,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$UiDir = Resolve-Path (Join-Path $ScriptDir "..\control\ui")

function Write-Step([string]$Message) {
    Write-Host $Message -ForegroundColor Cyan
}

Push-Location $UiDir
try {
    if (-not $SkipInstall) {
        Write-Step "Installing UI dependencies (npm install)..."
        npm install --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw "npm install failed" }

        Write-Step "Installing Playwright Chromium browser..."
        npx --yes playwright install chromium
        if ($LASTEXITCODE -ne 0) { throw "playwright install failed" }
    }

    if ($Ui) {
        Write-Step "Launching Playwright in UI mode..."
        npx playwright test --ui
    } else {
        Write-Step "Running Playwright suite..."
        npx playwright test
    }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
finally {
    Pop-Location
}
