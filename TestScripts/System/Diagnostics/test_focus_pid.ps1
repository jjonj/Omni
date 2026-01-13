param([int]$pidToFocus = 4828)

$wshell = New-Object -ComObject WScript.Shell

function Try-Activate($targetId) {
    Write-Host "Attempting to focus PID: $targetId"
    $p = Get-Process -Id $targetId -ErrorAction SilentlyContinue
    if ($null -eq $p) { 
        Write-Host "Process $targetId not found."
        return $false 
    }
    
    Write-Host "Process Name: $($p.ProcessName)"
    Write-Host "Window Title: $($p.MainWindowTitle)"
    Write-Host "Window Handle: $($p.MainWindowHandle)"

    if ($wshell.AppActivate($p.Id)) { 
        Write-Host "SUCCESS: AppActivate($($p.Id)) returned true"
        return $true 
    }
    
    return $false
}

Write-Host "--- Starting Focus Test ---"
if (Try-Activate($pidToFocus)) {
    Write-Host "Target PID focused successfully."
} else {
    Write-Host "Failed to focus target PID. Climbing tree..."
    $currPid = $pidToFocus
    for ($i=0; $i -lt 5; $i++) {
        $procInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $currPid"
        $parent = $procInfo.ParentProcessId
        if (!$parent) { 
            Write-Host "No more parents found."
            break 
        }
        Write-Host "Parent PID found: $parent"
        if (Try-Activate($parent)) { 
            Write-Host "SUCCESS: Focused parent PID $parent"
            break 
        }
        $currPid = $parent
    }
}
Write-Host "--- Test Finished ---"
