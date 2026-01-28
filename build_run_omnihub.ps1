# build_run_omnihub.ps1

# --- Elevation Logic ---
$currentPrincipal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "Script is not running as admin. Requesting elevation..." -ForegroundColor Yellow
    $params = $MyInvocation.BoundParameters.GetEnumerator() | ForEach-Object { "-$($_.Key) `"$($_.Value)`"" }
    $params += $MyInvocation.UnboundArguments
    Start-Process powershell.exe -ArgumentList "-NoExit -NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" $params" -Verb RunAs
    exit
}

# --- Configuration ---
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Get-Location }
$HubDir = Join-Path $ScriptDir "OmniSync.Hub\src\OmniSync.Hub"
$HubExePath = Join-Path $HubDir "bin\Debug\net9.0-windows\OmniSync.Hub.exe"
$HubLogPath = Join-Path $ScriptDir "hub_build.log"
$CrashLogPath = Join-Path $ScriptDir "hub_crash_log.log"

Write-Host "HUB_DIR is $HubDir"

# --- Helper Functions ---
function Run-Command {
    param (
        [string]$Command,
        [string]$Cwd,
        [string]$LogFile
    )
    Write-Host "Executing: $Command in $Cwd"
    
    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    
    $process = Start-Process -FilePath powershell.exe -ArgumentList "-NoProfile -Command $Command" -WorkingDirectory $Cwd -NoNewWindow -PassThru -Wait -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
    
    # Merge temp files into the log file
    Add-Content $LogFile "`n--- Command: $Command ---"
    if (Test-Path $stdoutFile) {
        $content = Get-Content $stdoutFile
        if ($content) { Add-Content $LogFile $content }
        Remove-Item $stdoutFile
    }
    if (Test-Path $stderrFile) {
        $errors = Get-Content $stderrFile
        if ($errors) {
            Add-Content $LogFile "--- Errors ---"
            Add-Content $LogFile $errors
        }
        Remove-Item $stderrFile
    }
    
    return $process.ExitCode
}

# --- Cleanup ---
Write-Host "Attempting to kill OmniSync.Hub.exe processes..."
taskkill /IM OmniSync.Hub.exe /F 2>$null

# Clear previous logs
if (Test-Path $HubLogPath) { Remove-Item $HubLogPath -Force }

# Delete bin/obj folders
foreach ($folder in "bin", "obj") {
    $path = Join-Path $HubDir $folder
    if (Test-Path $path) {
        Write-Host "Deleting $path..."
        Remove-Item $path -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Delete .vs folder
$vsFolder = Join-Path $ScriptDir ".vs"
if (Test-Path $vsFolder) {
    Write-Host "Deleting $vsFolder..."
    Remove-Item $vsFolder -Recurse -Force -ErrorAction SilentlyContinue
}

# --- Build Process ---
Write-Host "`n--- Cleaning OmniSync.Hub ---"
$exitCode = Run-Command "dotnet clean" $HubDir $HubLogPath
if ($exitCode -ne 0) { Write-Host "Clean failed. Check $HubLogPath"; exit $exitCode }

Write-Host "`n--- Clearing NuGet cache ---"
Run-Command "dotnet nuget locals all --clear" $HubDir $HubLogPath

Write-Host "`n--- Restoring OmniSync.Hub dependencies ---"
$exitCode = Run-Command "dotnet restore" $HubDir $HubLogPath
if ($exitCode -ne 0) { Write-Host "Restore failed. Check $HubLogPath"; exit $exitCode }

Write-Host "`n--- Building OmniSync.Hub ---"
$exitCode = Run-Command "dotnet build" $HubDir $HubLogPath
if ($exitCode -ne 0) { Write-Host "Build failed. Check $HubLogPath"; exit $exitCode }

# If we reached here, build was successful. Delete the log.
if (Test-Path $HubLogPath) {
    Remove-Item $HubLogPath -Force
    Write-Host "Build successful. hub_build.log deleted." -ForegroundColor Gray
}

# --- Launch ---
Write-Host "`n--- Starting OmniSync.Hub in background ---"
if (-not (Test-Path $HubExePath)) {
    Write-Host "Error: Hub executable not found at $HubExePath" -ForegroundColor Red
    exit 1
}

# Start the process detached
Start-Process -FilePath $HubExePath -WorkingDirectory $HubDir

Write-Host "Waiting for Hub to initialize..."
Start-Sleep -Seconds 3

# Check if process is running
$hubProc = Get-Process "OmniSync.Hub" -ErrorAction SilentlyContinue
if ($null -eq $hubProc) {
    Write-Host "ERROR: OmniSync.Hub failed to start." -ForegroundColor Red
    if (Test-Path $CrashLogPath) {
        Write-Host "Last crash log entry:"
        Get-Content $CrashLogPath | Select-Object -Last 10
    }
    exit 1
}

Write-Host "OmniSync.Hub is running with PID: $($hubProc.Id)" -ForegroundColor Green
