# build_run_omnihub.ps1

# --- Configuration ---
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) { $ScriptDir = Get-Location }
$HubDir = Join-Path $ScriptDir "OmniSync.Hub\src\OmniSync.Hub"
$HubExePath = Join-Path $HubDir "bin\Debug\net9.0-windows\OmniSync.Hub.exe"
$ExeDir = Join-Path $HubDir "bin\Debug\net9.0-windows"
$HubLogPath = Join-Path $ScriptDir "hub_build.log"

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
    
    $process = Start-Process -FilePath powershell.exe -ArgumentList "-NoProfile -Command $Command" -WorkingDirectory $Cwd -NoNewWindow -PassThru -RedirectStandardOutput $stdoutFile -RedirectStandardError $stderrFile
    
    # Wait for 30 seconds
    if (-not $process.WaitForExit(30000)) {
        Write-Host "Command timed out after 30 seconds. Terminating..." -ForegroundColor Red
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        $exitCode = -1
    } else {
        $exitCode = $process.ExitCode
    }
    
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
    
    return $exitCode
}

# --- Step 1: Kill existing Hub (Elevated) ---
Write-Host "Attempting elevated kill of OmniSync.Hub.exe..." -ForegroundColor Yellow
Start-Process taskkill -ArgumentList "/IM OmniSync.Hub.exe /F" -Verb RunAs -Wait -ErrorAction SilentlyContinue

# --- Step 2: Prepare for build ---
# Clear previous logs
if (Test-Path $HubLogPath) { Remove-Item $HubLogPath -Force }

# --- Step 3: Build Process (Non-Elevated) ---
Write-Host "`n--- Building OmniSync.Hub ---"
$exitCode = Run-Command "dotnet build" $HubDir $HubLogPath

if ($exitCode -ne 0) {
    Write-Host "Build failed. Check $HubLogPath" -ForegroundColor Red
    exit $exitCode
}

# If we reached here, build was successful. Delete the log.
if (Test-Path $HubLogPath) {
    Remove-Item $HubLogPath -Force
    Write-Host "Build successful." -ForegroundColor Green
}

# --- Step 4: Elevated Run ---
Write-Host "`n--- Starting OmniSync.Hub as Administrator ---"
if (-not (Test-Path $HubExePath)) {
    Write-Host "Error: Hub executable not found at $HubExePath" -ForegroundColor Red
    exit 1
}

# We use a single elevated PowerShell call to kill any remaining instances and start the new one.
$finalCmd = "taskkill /IM OmniSync.Hub.exe /F 2>`$null; Start-Process '$HubExePath' -WorkingDirectory '$ExeDir'"
Start-Process powershell.exe -ArgumentList "-NoProfile -ExecutionPolicy Bypass -Command `"$finalCmd`"" -Verb RunAs

Write-Host "Done. New Hub instance should be starting." -ForegroundColor Gray
