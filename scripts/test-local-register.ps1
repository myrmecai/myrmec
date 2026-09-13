# Test script for POST /api/v1/agent/auth/local/register
# Assumes engine is running on http://localhost:9090 with e2e profile.

param(
    [string]$ProfileId = "",
    [switch]$TestDefaultProfile
)

$ErrorActionPreference = "Stop"
$BaseUrl = "http://localhost:9090/api/v1"
$AdminEmail = "admin@e2e-test.local"
$AdminPassword = "E2eTest@123!"

function Write-Step([string]$Message) {
    Write-Host $Message -ForegroundColor Cyan
}

function Invoke-RestWithBody {
    param(
        [string]$Uri,
        [string]$Method,
        [hashtable]$Headers = @{},
        [string]$Body = ""
    )
    try {
        return Invoke-RestMethod -Uri $Uri -Method $Method -ContentType "application/json" -Headers $Headers -Body $Body
    } catch {
        $statusCode = "Unknown"
        $errBody = $_.Exception.Message
        if ($_.Exception.Response) {
            $statusCode = $_.Exception.Response.StatusCode
            try {
                $stream = $_.Exception.Response.GetResponseStream()
                $reader = New-Object System.IO.StreamReader($stream)
                $errBody = $reader.ReadToEnd()
            } catch {
                $errBody = $_.Exception.Message
            }
        }
        throw "HTTP $statusCode : $errBody"
    }
}

# 1. Login
Write-Step "1. Logging in as admin..."
$loginBody = @{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json -Compress
$loginResponse = Invoke-RestWithBody -Uri "$BaseUrl/auth/login" -Method Post -Body $loginBody
$accessToken = $loginResponse.accessToken
Write-Host "   Access token obtained."

$headers = @{ Authorization = "Bearer $accessToken" }

# 2. Find or create an agent profile
Write-Step "2. Finding or creating agent profile..."
if ($TestDefaultProfile -and -not $ProfileId) {
    $ProfileId = "6d7b8c9d-0e1f-4a2b-8c3d-9e4f5a6b7c8d"
}
if ($ProfileId) {
    try {
        $profile = Invoke-RestMethod -Uri "$BaseUrl/admin/agent-profiles/$ProfileId" -Method Get -Headers $headers
        $profileId = $profile.id
        Write-Host "   Using provided profile: $profileId"
    } catch {
        throw "Profile $ProfileId not found: $($_.Exception.Message)"
    }
} else {
    try {
        $profiles = Invoke-RestWithBody -Uri "$BaseUrl/admin/agent-profiles" -Method Get -Headers $headers
        $profile = $profiles.items | Select-Object -First 1
        if ($profile) {
            $profileId = $profile.id
            Write-Host "   Reusing existing profile: $profileId"
        } else {
            throw "no profiles"
        }
    } catch {
        $profileName = "local-test-profile-$(Get-Date -Format 'yyyyMMddHHmmss')"
        $profileBody = @{
            name = $profileName
            description = "Profile for local agent registration test"
            status = "ACTIVE"
            modelCode = "github-gpt-4o"
        } | ConvertTo-Json -Compress
        $profileResponse = Invoke-RestWithBody -Uri "$BaseUrl/admin/agent-profiles" -Method Post -Headers $headers -Body $profileBody
        $profileId = $profileResponse.id
        Write-Host "   Profile created: $profileId"
    }
}

# 3. Call /api/v1/agent/auth/local/register without project (system scope)
Write-Step "3. Registering local agent without project..."
$registerBody = @{
    profileId = $profileId
} | ConvertTo-Json -Compress

$localResponse = Invoke-RestWithBody -Uri "$BaseUrl/agent/auth/local/register" -Method Post -Headers $headers -Body $registerBody
Write-Host "   Local agent registered."
Write-Host "   agentId: $($localResponse.agentId)"
Write-Host "   instanceId: $($localResponse.instanceId)"
Write-Host "   accessToken length: $($localResponse.accessToken.Length)"
Write-Host "   refreshToken length: $($localResponse.refreshToken.Length)"

# 4. Verify agent token by hitting an AGENT-scoped REST endpoint (heartbeat)
Write-Step "4. Verifying agent token with /api/v1/agent/heartbeat..."
$agentHeaders = @{ Authorization = "Bearer $($localResponse.accessToken)" }
$heartbeatBody = @{
    sequence = 1
    status = "ACTIVE"
    timestamp = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
} | ConvertTo-Json -Compress

try {
    $heartbeatResponse = Invoke-RestWithBody -Uri "$BaseUrl/agent/heartbeat" -Method Post -Headers $agentHeaders -Body $heartbeatBody
    Write-Host "   Heartbeat accepted: ack=$($heartbeatResponse.ack)"
} catch {
    Write-Host "   Heartbeat call failed: $($_.Exception.Message)" -ForegroundColor Yellow
}

# 5. Register again with same user (should reuse host and create a new instance)
Write-Step "5. Re-registering local agent without project (should reuse host)..."
$localResponse2 = Invoke-RestWithBody -Uri "$BaseUrl/agent/auth/local/register" -Method Post -Headers $headers -Body $registerBody
Write-Host "   Second registration agentId: $($localResponse2.agentId)"
Write-Host "   Second registration instanceId: $($localResponse2.instanceId)"
if ($localResponse.agentId -eq $localResponse2.agentId) {
    Write-Host "   Host reused as expected." -ForegroundColor Green
} else {
    Write-Host "   WARNING: host NOT reused." -ForegroundColor Red
}


