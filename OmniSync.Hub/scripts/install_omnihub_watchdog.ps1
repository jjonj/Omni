# Run elevated once. Installs an independent scheduled watchdog; no Hub rebuild required.
# At logon it runs continuously (5-second probes); every minute Task Scheduler
# also retries launch if the watchdog itself has died.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$hubName = 'OmniSync Hub'
$watchName = 'OmniHub Watchdog'
$watchFile = Join-Path $PSScriptRoot 'omnihub_watchdog.ps1'
if (-not (Test-Path $watchFile -PathType Leaf)) { throw "Missing supervisor: $watchFile" }
$hub = Get-ScheduledTask -TaskName $hubName -ErrorAction Stop
if (-not $hub) { throw "Missing '$hubName' task; install OmniHub before the watchdog" }

# Repair Hub's own scheduler fallback: no 72-hour kill, no battery stop, 3 rapid
# post-crash restarts. The separate watchdog provides unlimited subsequent attempts.
$hubSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -StartWhenAvailable
Set-ScheduledTask -TaskName $hubName -Settings $hubSettings | Out-Null

$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Highest
$arguments = '-NoLogo -NoProfile -NonInteractive -WindowStyle Hidden -ExecutionPolicy Bypass -File "' + $watchFile + '"'
$action = New-ScheduledTaskAction -Execute (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -Argument $arguments -WorkingDirectory $PSScriptRoot

$onLogon = New-ScheduledTaskTrigger -AtLogOn -User $identity
# Omitting RepetitionDuration makes this repeat indefinitely. Combined with
# IgnoreNew, the running watchdog is never replaced by an additional instance.
$onMinute = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes 1)
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
    -MultipleInstances IgnoreNew -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -StartWhenAvailable
$task = New-ScheduledTask -Action $action -Principal $principal `
    -Trigger @($onLogon,$onMinute) -Settings $settings `
    -Description 'Independent 5-second HTTP/process supervisor for OmniHub; Task Scheduler restarts the supervisor.'
# A repeat install resets the definition but need not stop the already running watchdog.
Register-ScheduledTask -TaskName $watchName -InputObject $task -Force | Out-Null
Start-ScheduledTask -TaskName $watchName -ErrorAction Stop
Write-Output "Installed and started $watchName for $identity."
Get-ScheduledTask -TaskName $watchName | Select-Object TaskName,State
Get-ScheduledTask -TaskName $hubName | Select-Object TaskName,State
