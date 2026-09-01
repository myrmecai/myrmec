$raw = Get-Content 'test-results\providers-admin-model-prov-c7b0c-th-inline-connection-config-chromium\trace-unzipped\0-trace.network' -Raw
$net = $raw | ConvertFrom-Json
$net.entries | Where-Object { $_ -and ($_.request.url -like '*connection-configs*') } | ForEach-Object {
    [PSCustomObject]@{
        url = $_.request.url
        status = $_.response.status
        body = $_.request.postData.text
    }
} | Format-Table -Wrap
