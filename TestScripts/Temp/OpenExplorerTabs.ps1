param(
    [string[]]$Paths = @("C:\", "C:\Windows")
)

# Function to open a path in a new tab
function Open-Tab {
    param([string]$Path)
    $wshell = New-Object -ComObject WScript.Shell
    
    # Send Ctrl+T to open a new tab
    $wshell.SendKeys("^t")
    Start-Sleep -Milliseconds 500
    
    # Send Alt+D to focus address bar
    $wshell.SendKeys("%d")
    Start-Sleep -Milliseconds 200
    
    # Type path and Enter
    # We escape some special characters for SendKeys if necessary, 
    # but for paths it's usually fine unless there are brackets.
    $wshell.SendKeys($Path)
    $wshell.SendKeys("{ENTER}")
    Start-Sleep -Milliseconds 500
}

if ($Paths.Count -eq 0) { return }

# Open the first path in a new window
Start-Process explorer.exe $Paths[0]
Start-Sleep -Seconds 2 # Wait for Explorer to initialize

# Open subsequent paths in tabs
for ($i = 1; $i -lt $Paths.Count; $i++) {
    Open-Tab -Path $Paths[$i]
}
