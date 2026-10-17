# Install independent MCP/Funnel supervision. Safe to repeat; keeps Lite's action/version.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$name = 'OmniMCP-Lite Watchdog'
$scriptFile = Join-Path $PSScriptRoot 'omnimcp_lite_watchdog.py'
$python = (Get-Command python.exe -ErrorAction Stop).Source
# Resolve Windows Store aliases and virtual environments to the real interpreter.
$python = (& $python -c 'import sys; print(sys.executable)').Trim()
$pythonw = Join-Path (Split-Path $python) 'pythonw.exe'
if (-not (Test-Path -LiteralPath $pythonw)) { throw "Missing $pythonw" }
$lite = Get-ScheduledTask -TaskName 'OmniMCP-Lite' -ErrorAction Stop
$backup = Join-Path $env:LOCALAPPDATA ('OmniSync\lite-watchdog\task-backup-' + (Get-Date -Format yyyyMMdd-HHmmss) + '.xml')
New-Item -ItemType Directory -Path (Split-Path $backup) -Force | Out-Null
Export-ScheduledTask -TaskName $lite.TaskName | Set-Content -LiteralPath $backup -Encoding Unicode
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
    -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
Set-ScheduledTask -TaskName $lite.TaskName -Settings $settings | Out-Null
$identity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$principal = New-ScheduledTaskPrincipal -UserId $identity -LogonType Interactive -RunLevel Highest
$action = New-ScheduledTaskAction -Execute $pythonw -Argument ('"' + $scriptFile + '"') -WorkingDirectory $PSScriptRoot
$logon = New-ScheduledTaskTrigger -AtLogOn -User $identity
$repeat = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) -RepetitionInterval (New-TimeSpan -Minutes 1)
$task = New-ScheduledTask -Action $action -Principal $principal -Trigger @($logon,$repeat) -Settings $settings `
    -Description 'Independent Lite MCP initialize/tool probes and public Funnel ingress probes; survives Hub failures.'
Register-ScheduledTask -TaskName $name -InputObject $task -Force | Out-Null
Start-ScheduledTask -TaskName $name
Write-Output "Installed $name; Lite task backup: $backup"
