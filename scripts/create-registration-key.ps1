# Create Agent Registration Key
# Usage: .\create-registration-key.ps1 [-Label "my-agent"] [-ExpirationDays 30]

param(
    [string]$Label = "default-agent",
    [int]$ExpirationDays = 90,
    [string]$OutputFile = "..\agent-registration.env",
    [string]$AdminEmail = "admin@e2e-test.local",
    [string]$AdminPassword = "E2eTest@123!"
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutputPath = Join-Path $ScriptDir $OutputFile

$BaseUrl = "http://localhost:9090"
$ApiUrl = "$BaseUrl/api/v1"

Write-Host "Creating registration key..." -ForegroundColor Cyan
Write-Host "  Label: $Label" -ForegroundColor Yellow
Write-Host "  Expires in: $ExpirationDays days" -ForegroundColor Yellow
Write-Host ""

try {
    # Authenticate as admin first
    Write-Host "Authenticating as admin..." -ForegroundColor Yellow
    $loginBody = @{
        email = $AdminEmail
        password = $AdminPassword
    } | ConvertTo-Json

    $loginResponse = Invoke-RestMethod -Method POST `
        -Uri "$ApiUrl/auth/login" `
        -ContentType "application/json" `
        -Body $loginBody

    $adminToken = $loginResponse.accessToken
    Write-Host "  Authenticated as: $AdminEmail" -ForegroundColor Green
    Write-Host ""

    # Create registration key with auth header
    $response = Invoke-RestMethod -Method POST `
        -Uri "$ApiUrl/admin/registration-keys?label=$Label&expirationDays=$ExpirationDays" `
        -Headers @{ "Authorization" = "Bearer $adminToken" }

    $keyId = $response.id
    $keyValue = $response.keyValue
    $expiresAt = $response.expiresAt

    Write-Host "Registration key created successfully!" -ForegroundColor Green
    Write-Host "  ID: $keyId" -ForegroundColor White
    Write-Host "  Expires: $expiresAt" -ForegroundColor White
    Write-Host ""

    # Write to .env file
    $envContent = @"
# Myrmec Agent Registration Key
# Created: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
# Label: $Label
# Expires: $expiresAt
# Key ID: $keyId

MYRMEC_REGISTRATION_KEY=$keyValue
MYRMEC_ENGINE_URL=http://localhost:9090
MYRMEC_AGENT_NAME=$Label
MYRMEC_HEARTBEAT_INTERVAL=30
"@

    $envContent | Out-File -FilePath $OutputPath -Encoding UTF8

    Write-Host "Key written to: $OutputPath" -ForegroundColor Green
    Write-Host ""
    Write-Host "IMPORTANT: This key is shown only once. Keep it safe!" -ForegroundColor Red
    Write-Host ""
    Write-Host "To use with agent:" -ForegroundColor Yellow
    Write-Host "  .\start-agent.ps1" -ForegroundColor White

} catch {
    Write-Host "Error creating registration key:" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host ""
    Write-Host "Make sure the control engine is running: .\start-engine.ps1" -ForegroundColor Yellow
    exit 1
}
