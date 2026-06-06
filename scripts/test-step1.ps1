# Integration test script for Step 1: Agent Communication (PowerShell)

$ErrorActionPreference = "Stop"

$BaseUrl = "http://localhost:9090"
$ApiUrl = "$BaseUrl/api/v1"

Write-Host "=== Myrmec Step 1 Integration Test ===" -ForegroundColor Cyan
Write-Host ""

# 1. Create registration key
Write-Host "1. Creating registration key..." -ForegroundColor Yellow
$keyResponse = Invoke-RestMethod -Method POST -Uri "$ApiUrl/admin/registration-keys?label=test-key"
$regKey = $keyResponse.keyValue
Write-Host "   Registration key: $regKey" -ForegroundColor Green
Write-Host ""

# 2. Register agent (new auth flow: key in body)
Write-Host "2. Registering agent..." -ForegroundColor Yellow
$registerBody = @{
    registrationKey = $regKey
    name = "test-agent-1"
    metadata = @{
        version = "0.1.0"
        capabilities = @("test")
    }
} | ConvertTo-Json

$registerResponse = Invoke-RestMethod -Method POST -Uri "$ApiUrl/auth/register" `
    -Headers @{ "Content-Type" = "application/json" } `
    -Body $registerBody

$agentId = $registerResponse.agentId
$accessToken = $registerResponse.accessToken
$refreshToken = $registerResponse.refreshToken
Write-Host "   Agent ID: $agentId" -ForegroundColor Green
Write-Host "   Access Token: $($accessToken.Substring(0, 50))..." -ForegroundColor Green
Write-Host "   Refresh Token: $($refreshToken.Substring(0, 50))..." -ForegroundColor Green
Write-Host ""

# 3. Get agent info
Write-Host "3. Getting agent info..." -ForegroundColor Yellow
$meResponse = Invoke-RestMethod -Method GET -Uri "$ApiUrl/agents/me" `
    -Headers @{ "Authorization" = "Bearer $accessToken" }
Write-Host "   Name: $($meResponse.name)" -ForegroundColor Green
Write-Host "   Status: $($meResponse.status)" -ForegroundColor Green
Write-Host ""

# 4. Send heartbeat
Write-Host "4. Sending heartbeat..." -ForegroundColor Yellow
$heartbeatBody = @{} | ConvertTo-Json

$hbResponse = Invoke-RestMethod -Method POST -Uri "$ApiUrl/agents/heartbeat" `
    -Headers @{ "Authorization" = "Bearer $accessToken"; "Content-Type" = "application/json" } `
    -Body $heartbeatBody
Write-Host "   Ack: $($hbResponse.ack)" -ForegroundColor Green
Write-Host "   Server Time: $($hbResponse.serverTime)" -ForegroundColor Green
Write-Host ""

# 5. Refresh access token
Write-Host "5. Refreshing access token..." -ForegroundColor Yellow
$refreshBody = @{ refreshToken = $refreshToken } | ConvertTo-Json

$refreshResponse = Invoke-RestMethod -Method POST -Uri "$ApiUrl/auth/refresh" `
    -Headers @{ "Content-Type" = "application/json" } `
    -Body $refreshBody
Write-Host "   New Access Token: $($refreshResponse.accessToken.Substring(0, 50))..." -ForegroundColor Green
Write-Host "   Expires At: $($refreshResponse.accessTokenExpiresAt)" -ForegroundColor Green
Write-Host ""

Write-Host "=== All tests passed! ===" -ForegroundColor Cyan
