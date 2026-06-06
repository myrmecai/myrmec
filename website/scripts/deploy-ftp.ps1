$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$envPath = Join-Path $scriptDir ".env"

if (-not (Test-Path $envPath)) {
    throw "Missing .env file at $envPath"
}

$envMap = @{}
Get-Content $envPath | ForEach-Object {
    $line = $_.Trim()
    if ($line.Length -eq 0 -or $line.StartsWith("#")) {
        return
    }

    $pair = $line -split "=", 2
    if ($pair.Count -ne 2) {
        return
    }

    $key = $pair[0].Trim()
    $value = $pair[1].Trim().Trim('"').Trim("'")
    $envMap[$key] = $value
}

$requiredKeys = @("TARGET_URL", "FTP_USER", "FTP_PASSWORD")
$missing = @($requiredKeys | Where-Object { -not $envMap.ContainsKey($_) -or [string]::IsNullOrWhiteSpace($envMap[$_]) })
if ($missing.Count -gt 0) {
    throw "Missing required keys in .env: $($missing -join ', ')"
}

$targetUrl = $envMap["TARGET_URL"].TrimEnd("/")
$ftpUser = $envMap["FTP_USER"]
$ftpPassword = $envMap["FTP_PASSWORD"]
$remoteBase = "/"
if ($envMap.ContainsKey("REMOTE_BASE") -and -not [string]::IsNullOrWhiteSpace($envMap["REMOTE_BASE"])) {
    $remoteBase = "/" + $envMap["REMOTE_BASE"].Trim().Trim("/")
}

$localPath = Join-Path (Split-Path $scriptDir -Parent) "dist"
if ($envMap.ContainsKey("LOCAL_PATH") -and -not [string]::IsNullOrWhiteSpace($envMap["LOCAL_PATH"])) {
    $localPath = $envMap["LOCAL_PATH"]
}

if (-not (Test-Path $localPath)) {
    throw "Local path not found: $localPath"
}

$curlPath = (Get-Command "curl.exe" -ErrorAction SilentlyContinue).Source
if (-not $curlPath) {
    throw "curl.exe is required but was not found in PATH"
}

$targetUri = $null
try {
    $targetUri = [Uri]$targetUrl
} catch {
    throw "TARGET_URL is not a valid URL: $targetUrl"
}

$scheme = $targetUri.Scheme.ToLowerInvariant()
if ($scheme -notin @("ftp", "ftps", "sftp")) {
    throw "Unsupported TARGET_URL scheme '$scheme'. Use ftp://, ftps://, or sftp://"
}

if (($remoteBase -eq "/") -and $targetUri.AbsolutePath -and $targetUri.AbsolutePath -ne "/") {
    $remoteBase = $targetUri.AbsolutePath.TrimEnd("/")
}

$useTls = $true
if ($envMap.ContainsKey("FTP_USE_TLS")) {
    $tlsValue = $envMap["FTP_USE_TLS"].Trim().ToLowerInvariant()
    $useTls = -not ($tlsValue -eq "false" -or $tlsValue -eq "0" -or $tlsValue -eq "no")
}

if ($scheme -eq "sftp") {
    $useTls = $false
}

$auth = "${ftpUser}:${ftpPassword}"
$files = Get-ChildItem -Path $localPath -Recurse -File
if ($files.Count -eq 0) {
    throw "No files found to upload in: $localPath"
}

Write-Host "Uploading $($files.Count) files from $localPath to $targetUrl$remoteBase"

if ($scheme -eq "sftp") {
    $pscpPath = (Get-Command "pscp.exe" -ErrorAction SilentlyContinue).Source
    if (-not $pscpPath) {
        throw "pscp.exe is required for sftp:// uploads and was not found in PATH"
    }

    $sftpHostKey = ""
    if ($envMap.ContainsKey("SFTP_HOST_KEY") -and -not [string]::IsNullOrWhiteSpace($envMap["SFTP_HOST_KEY"])) {
        $sftpHostKey = $envMap["SFTP_HOST_KEY"].Trim()
    }

    if ([string]::IsNullOrWhiteSpace($sftpHostKey)) {
        throw "Missing SFTP_HOST_KEY in .env for batch mode. Example: SFTP_HOST_KEY=ssh-ed25519 255 SHA256:..."
    }

    $targetHost = $targetUri.Host
    $port = if ($targetUri.IsDefaultPort) { 22 } else { $targetUri.Port }
    $sourcePattern = Join-Path $localPath "*"
    $targetSpec = "${ftpUser}@${targetHost}:$remoteBase/"

    $pscpArgs = @("-batch", "-hostkey", $sftpHostKey, "-P", $port.ToString(), "-pw", $ftpPassword, "-r", $sourcePattern, $targetSpec)
    & $pscpPath @pscpArgs

    if ($LASTEXITCODE -ne 0) {
        throw "SFTP upload failed (pscp exit code $LASTEXITCODE)"
    }

    Write-Host "SFTP deploy complete."
    return
}

foreach ($file in $files) {
    $relative = $file.FullName.Substring($localPath.Length).TrimStart([char[]]@([char]92, [char]47)).Replace("\\", "/")
    $uploadUrl = "$targetUrl$remoteBase/$relative"

    $args = @()
    if ($scheme -eq "ftp" -or $scheme -eq "ftps") {
        if ($useTls) {
            $args += "--ssl-reqd"
        }
        $args += "--ftp-create-dirs"
    }

    $args += @("-u", $auth, "-T", $file.FullName, $uploadUrl)

    & $curlPath @args | Out-Null

    if ($LASTEXITCODE -ne 0) {
        throw "Upload failed for $relative (curl exit code $LASTEXITCODE)"
    }

    Write-Host "Uploaded: $relative"
}

Write-Host "FTP deploy complete."
