# Independent OmniHub supervisor. Run via the "OmniHub Watchdog" Task Scheduler task.
# This process must NEVER be hosted by OmniHub: it is supposed to survive a Hub crash.
[CmdletBinding()]
param(
    [switch]$Once,
    [switch]$DryRun,
    [ValidateRange(1, 300)][int]$IntervalSeconds = 5,
    [ValidateRange(1, 20)][int]$FailureThreshold = 4,
    [ValidateRange(200, 10000)][int]$TimeoutMs = 5000,
    [ValidateRange(1, 300)][int]$RestartGraceSeconds = 120,
    [ValidateRange(60, 3600)][int]$RestartCooldownSeconds = 300,
    [string]$ProbeUrl = 'http://127.0.0.1:5000/',
    [string]$StateDirectory = (Join-Path $env:LOCALAPPDATA 'OmniSync\watchdog')
)
$ErrorActionPreference = 'Stop'
$taskName = 'OmniSync Hub'
$script:consecutiveFailures = 0
$script:restartCount = 0
$script:lastRestart = [datetime]::MinValue
$script:wasHealthy = $false
$script:lastError = ''
Add-Type -AssemblyName System.Net.Http
$script:client = New-Object System.Net.Http.HttpClient
$script:client.Timeout = [TimeSpan]::FromMilliseconds($TimeoutMs)
$script:client.DefaultRequestHeaders.Add('Cache-Control', 'no-cache')
[System.IO.Directory]::CreateDirectory($StateDirectory) | Out-Null
$logPath = Join-Path $StateDirectory 'watchdog.log'
$statePath = Join-Path $StateDirectory 'status.json'
$pausePath = Join-Path $StateDirectory 'pause.txt'
$script:expectedPath = $null

function Log([string]$message) {
    $line = ('{0:O} {1}' -f [DateTimeOffset]::Now, $message)
    if ((Test-Path $logPath) -and (Get-Item $logPath).Length -gt 5242880) {
        Move-Item -LiteralPath $logPath -Destination (Join-Path $StateDirectory 'watchdog.previous.log') -Force
    }
    Add-Content -LiteralPath $logPath -Value $line -Encoding UTF8
    if ($Once -or $DryRun) { Write-Output $line }
}

function Get-ExpectedPath {
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction Stop
    if (-not $task -or -not $task.Actions -or $task.Actions.Count -ne 1) {
        throw "Hub task must have precisely one executable action"
    }
    $exe = [Environment]::ExpandEnvironmentVariables($task.Actions[0].Execute.Trim('"'))
    if (-not [System.IO.Path]::IsPathRooted($exe)) { throw "Hub task action is not absolute: $exe" }
    return [System.IO.Path]::GetFullPath($exe)
}

function Get-HubProcesses {
    $expected = $script:expectedPath
    @(Get-CimInstance Win32_Process -Filter "Name='OmniSync.Hub.exe'" -ErrorAction Stop |
        Where-Object { $_.ExecutablePath -and $_.ExecutablePath.Equals($expected, [StringComparison]::OrdinalIgnoreCase) })
}

function Get-ListenerOwner {
    $listener = Get-NetTCPConnection -LocalPort 5000 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($listener) { return [int]$listener.OwningProcess }
    return 0
}

function Check-Healthy {
    # Check both app ownership and HTTP. A different web server must never be mistaken for Hub.
    $ownerPid = Get-ListenerOwner
    if ($ownerPid -eq 0) { $script:lastError = 'No listener on port 5000'; return $false }
    $owned = @(Get-HubProcesses)
    if (-not (@($owned | Where-Object { [int]$_.ProcessId -eq $ownerPid }).Count)) {
        $script:lastError = "Port 5000 owned by unexpected PID $ownerPid"
        return $false
    }
    try {
        $reply = $script:client.GetAsync($ProbeUrl).GetAwaiter().GetResult()
        try {
            if (-not $reply.IsSuccessStatusCode) {
                $script:lastError = "HTTP $([int]$reply.StatusCode) from Gateway"
                return $false
            }
            # Main transport listener is the authoritative Android connection endpoint.
            # Do not parse the expensive Gateway tool registry as a liveness test.
            $script:lastError = ''
            return $true
        } finally { $reply.Dispose() }
    } catch {
        $script:lastError = 'Gateway probe failed: ' + $_.Exception.Message
        return $false
    }
}

function Write-Status([bool]$healthy, [bool]$paused) {
    # Atomically publish a compact heartbeat so the Gateway/other agents can inspect it.
    $obj = [ordered]@{
        checkedAt = [DateTimeOffset]::Now.ToString('O')
        healthy = $healthy
        paused = $paused
        watchdogPid = $PID
        hubPids = @((Get-HubProcesses) | ForEach-Object { [int]$_.ProcessId })
        consecutiveFailures = $script:consecutiveFailures
        restartCount = $script:restartCount
        lastRestartAt = if ($script:lastRestart -eq [datetime]::MinValue) { $null } else { $script:lastRestart.ToString('O') }
        lastError = $script:lastError
    }
    $tmp = $statePath + ".$PID.tmp"
    [System.IO.File]::WriteAllText($tmp, ($obj | ConvertTo-Json -Depth 4), [System.Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $tmp -Destination $statePath -Force
}

function Restart-Hub {
    # Ownership guard: if a different app holds 5000, do not kill it or enter a restart storm.
    $ownerPid = Get-ListenerOwner
    $owned = @(Get-HubProcesses)
    if ($ownerPid -ne 0 -and -not @($owned | Where-Object { [int]$_.ProcessId -eq $ownerPid }).Count) {
        Log "BLOCKED: Port 5000 belongs to another process ($ownerPid). Refusing destructive recovery."
        return
    }
    if ($DryRun) { Log "DRY-RUN: Would stop owned Hub process(es) and start scheduled task."; return }

    Log "RECOVERY: Hub unhealthy ($($script:lastError)); restarting scheduled task."
    # Stop the task (if it owns the process), then terminate only exact executable matches.
    try { Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue } catch {}
    foreach ($process in $owned) {
        try {
            Stop-Process -Id $process.ProcessId -Force -ErrorAction Stop
            Log "Stopped unresponsive Hub PID $($process.ProcessId)."
        } catch {
            Log "WARNING: Unable to stop Hub PID $($process.ProcessId): $($_.Exception.Message)"
        }
    }
    Start-Sleep -Milliseconds 600
    Start-ScheduledTask -TaskName $taskName -ErrorAction Stop
    $script:lastRestart = Get-Date
    $script:restartCount++
    Log "Requested Hub startup through '$taskName' (recovery $($script:restartCount))."
}

function Step {
    $paused = Test-Path -LiteralPath $pausePath
    $healthy = Check-Healthy
    if ($healthy) {
        if (-not $script:wasHealthy) { Log 'HEALTHY: OmniHub is responding.' }
        $script:consecutiveFailures = 0
    } else {
        $script:consecutiveFailures++
        if ($script:consecutiveFailures -eq 1) { Log "UNHEALTHY: $($script:lastError)" }
        $processMissing = @(Get-HubProcesses).Count -eq 0
        $sinceRestart = ((Get-Date) - $script:lastRestart).TotalSeconds
        $pastGrace = $sinceRestart -ge $RestartGraceSeconds
        $pastCooldown = $sinceRestart -ge $RestartCooldownSeconds
        if ($paused) {
            if ($script:consecutiveFailures -eq 1) { Log "PAUSED: Maintenance marker at $pausePath" }
        } elseif (($processMissing -or $script:consecutiveFailures -ge $FailureThreshold) -and $pastGrace -and $pastCooldown) {
            try { Restart-Hub } catch { Log "ERROR: Hub recovery failed: $($_.Exception.Message)" }
            $script:consecutiveFailures = 0  # next attempt after new threshold/grace
        }
    }
    $script:wasHealthy = $healthy
    Write-Status -healthy $healthy -paused $paused
}

try {
    # A Task Scheduler instance is already single-flight; mutex also prevents manual duplicates.
    $created = $false
    $mutex = New-Object System.Threading.Mutex($false, 'Local\OmniHubIndependentWatchdog', [ref]$created)
    if (-not $mutex.WaitOne(0)) {
        if ($Once) { Write-Output 'Watchdog already running; second instance exited.' }
        exit 0
    }
    try {
        $script:expectedPath = Get-ExpectedPath
        if (-not (Test-Path -LiteralPath $script:expectedPath -PathType Leaf)) {
            throw "Scheduled Hub executable missing: $($script:expectedPath)"
        }
        Log "START: independent supervisor PID=$PID, Hub=$($script:expectedPath), interval=$IntervalSeconds s, threshold=$FailureThreshold."
        do {
            try { Step } catch { Log "ERROR: Check failed: $($_.Exception.Message)" }
            if (-not $Once) { Start-Sleep -Seconds $IntervalSeconds }
        } while (-not $Once)
    } finally {
        $mutex.ReleaseMutex()
        $mutex.Dispose()
    }
} finally {
    $script:client.Dispose()
}
