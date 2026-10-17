# Lists recent sleep cycles from the Windows power logs, read-only. Run it after a few days of normal use.
# Each row: when the PC went to sleep, when it woke, how long it slept, and the wake source Windows reported.
# Wake Source "Unknown" is normal for Wake-on-LAN and some NIC wakes. Long, short, or unexplained rows are what to look at.
param(
    [int]$Days = 3
)

$since = (Get-Date).AddDays(-$Days)
$sleeps = Get-WinEvent -FilterHashtable @{ LogName = 'System'; ProviderName = 'Microsoft-Windows-Kernel-Power'; Id = 42; StartTime = $since } -ErrorAction SilentlyContinue
$wakes = Get-WinEvent -FilterHashtable @{ LogName = 'System'; ProviderName = 'Microsoft-Windows-Power-Troubleshooter'; Id = 1; StartTime = $since } -ErrorAction SilentlyContinue

$rows = foreach ($wake in $wakes) {
    $message = $wake.Message
    $source = ($message -split "`n" | Where-Object { $_ -match 'Wake Source:' } | Select-Object -First 1) -replace '.*Wake Source:\s*', '' -replace '^Device -\s*', ''
    $wakeTime = $wake.TimeCreated
    # Pair each wake with the closest sleep event before it.
    $sleep = $sleeps | Where-Object { $_.TimeCreated -lt $wakeTime } | Sort-Object TimeCreated -Descending | Select-Object -First 1
    [pscustomobject]@{
        Slept    = if ($sleep) { $sleep.TimeCreated.ToString('MM-dd HH:mm:ss') } else { '?' }
        Woke     = $wakeTime.ToString('MM-dd HH:mm:ss')
        Seconds  = if ($sleep) { [int]($wakeTime - $sleep.TimeCreated).TotalSeconds } else { $null }
        Source   = $source.Trim()
    }
}

if (-not $rows) {
    Write-Host "No sleep/wake cycles in the last $Days day(s)."
    return
}

$rows | Sort-Object Woke | Format-Table -AutoSize | Out-String -Width 200

