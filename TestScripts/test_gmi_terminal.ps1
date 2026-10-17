$workingDir = "D:\Wartribes"
Write-Host "Testing GMI command in Terminal (Non-Job)..."
Start-Process "cmd.exe" -ArgumentList "/k `"cd /d $workingDir && gmi.exe`""
