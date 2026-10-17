# Non-destructive live integration smoke: create and remove one disposable setup.
$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:3333/gateway/api/sweep'
$headers = @{ Origin = 'http://127.0.0.1:3333' }
function Call($path, $method, $body) {
    if ($null -ne $body) {
        return Invoke-RestMethod -Uri ($base + $path) -Method $method -Headers $headers -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 20 -Compress) -TimeoutSec 12
    }
    return Invoke-RestMethod -Uri ($base + $path) -Method $method -Headers $headers -TimeoutSec 12
}
$initial = Call '' 'GET' $null
$originalIds = @($initial.setups | ForEach-Object { $_.id })
$name = '__Gateway_Sweep_Smoke_' + [guid]::NewGuid().ToString('N').Substring(0,10)
$id = $null
try {
    $created = Call '/setups' 'POST' @{ revision = $initial.revision; name = $name }
    if (-not $created.ok) { throw 'Create failed' }
    $afterCreate = Call '' 'GET' $null
    $setup = @($afterCreate.setups | Where-Object { $_.name -eq $name })
    if ($setup.Count -ne 1) { throw 'New setup missing from shared live config' }
    $id = $setup[0].id
    $updated = Call ('/setups/' + $id) 'PUT' @{
        revision = $afterCreate.revision
        name = ($name + '_renamed')
        shortcut = ''
        actions = @(@{
            id = '00000000-0000-0000-0000-000000000000'
            type = 0; path = 'D:\Wartribes'; arguments = ''
            workingDirectory = ''; command = ''; reuseWindow = $true; positionWindow = $false
        })
    }
    if (-not $updated.ok) { throw 'Update failed' }
    $afterUpdate = Call '' 'GET' $null
    $changed = @($afterUpdate.setups | Where-Object { $_.id -eq $id })
    if ($changed.Count -ne 1 -or $changed[0].actions.Count -ne 1 -or $changed[0].name -ne ($name + '_renamed')) { throw 'Live update not reflected in shared config' }
    $disk = Join-Path $env:APPDATA 'OmniSync\settings.json'
    $saved = Get-Content -LiteralPath $disk -Raw | ConvertFrom-Json
    if (-not @($saved.Setups | Where-Object { $_.Id -eq $id }).Count) { throw 'Updated setup not persisted to shared settings.json' }
    $conflict = $false
    try {
        Call ('/setups/' + $id) 'PUT' @{ revision = $initial.revision; name = 'must-not-overwrite'; shortcut = ''; actions = $null } | Out-Null
    } catch { $conflict = ($_.Exception.Response.StatusCode.value__ -eq 409) }
    if (-not $conflict) { throw 'Optimistic concurrency check missing' }
    Write-Output ('PASS shared config: create, edit, persistence and stale-write rejection; rev=' + $afterUpdate.revision)
} finally {
    if ($id) {
        $latest = Call '' 'GET' $null
        $deleted = Call ('/setups/' + $id + '/delete') 'POST' @{ revision = $latest.revision }
        if (-not $deleted.ok) { throw 'Cleanup delete failed' }
    }
    $final = Call '' 'GET' $null
    if (@($final.setups | Where-Object { $_.name -like '__Gateway_Sweep_Smoke_*' }).Count -gt 0) { throw 'Smoke setup cleanup incomplete' }
    if (@($final.setups).Count -ne $originalIds.Count) { throw 'Original setup count changed' }
    Write-Output ('PASS cleanup: original setup count preserved (' + $originalIds.Count + ')')
}
