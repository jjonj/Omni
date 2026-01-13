param([int]$pidToFocus = 4828)

$wshell = New-Object -ComObject WScript.Shell

function Get-Info($id) {
    $p = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($null -eq $p) { return "Process $id not found." }
    return "PID: $id, Name: $($p.ProcessName), Title: '$($p.MainWindowTitle)', Handle: $($p.MainWindowHandle)"
}

Write-Host "--- Focus Diagnosis for PID $pidToFocus ---"
$curr = $pidToFocus
for ($i=0; $i -lt 6; $i++) {
    Write-Host (Get-Info $curr)
    $procInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $curr"
    if (!$procInfo) { break }
    
    # Try activate
    if ($wshell.AppActivate($curr)) {
        Write-Host "SUCCESS: AppActivate($curr) returned true"
    } else {
        Write-Host "FAILED: AppActivate($curr) returned false"
    }

    $parent = $procInfo.ParentProcessId
    if (!$parent) { break }
    $curr = $parent
}
Write-Host "--- End Diagnosis ---"
