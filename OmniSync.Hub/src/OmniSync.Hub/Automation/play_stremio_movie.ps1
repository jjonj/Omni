param([Parameter(Mandatory=$true)][string]$MovieId)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms

if (-not ("U32" -as [type])) {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class U32 {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint dwData, UIntPtr dwExtraInfo);
}
"@
}

if (-not ("StremioInputV2" -as [type])) {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class StremioInputV2 {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);
}
"@
}

$exe = "C:\Users\crovea\AppData\Local\Programs\Stremio\stremio-shell-ng.exe"
$movieUrl = "stremio:///detail/movie/$MovieId/$MovieId"

# Always pass the deep-link to Stremio. Stremio is single-instance, so this
# navigates the existing visible window instead of opening another one.
Start-Process -FilePath $exe -ArgumentList ('"' + $movieUrl + '"')

$proc = $null
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 250
    $proc = Get-Process -Name "stremio-shell-ng" -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 } |
        Select-Object -First 1
    if ($proc) { break }
}
if (-not $proc) { throw "Stremio window not found" }

$hwnd = $proc.MainWindowHandle

# Keep the native Stremio window maximized.
[U32]::ShowWindow($hwnd, 3) | Out-Null
[U32]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds 500

$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker

function Get-Descendants([System.Windows.Automation.AutomationElement]$node) {
    $child = $walker.GetFirstChild($node)
    while ($child) {
        $child
        Get-Descendants $child
        $child = $walker.GetNextSibling($child)
    }
}

# Poll until the source list contains a visible 1080p entry.
$match = $null
$deadline = [DateTime]::UtcNow.AddSeconds(30)

while (-not $match -and [DateTime]::UtcNow -lt $deadline) {
    try {
        $proc.Refresh()
        $hwnd = $proc.MainWindowHandle
        $root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)

        if ($root) {
            $match = Get-Descendants $root | Where-Object {
                try {
                    $_.Current.Name -and
                    $_.Current.Name.IndexOf("1080p", [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
                    $_.Current.BoundingRectangle.Width -gt 0 -and
                    $_.Current.BoundingRectangle.Height -gt 0
                } catch { $false }
            } | Select-Object -First 1
        }
    } catch {}

    if (-not $match) { Start-Sleep -Milliseconds 500 }
}

if (-not $match) {
    throw "No visible source containing '1080p' appeared within 30 seconds"
}

try {
    $pt = $match.GetClickablePoint()
    $x = [int]$pt.X
    $y = [int]$pt.Y
} catch {
    $r = $match.Current.BoundingRectangle
    $x = [int]($r.X + $r.Width / 2)
    $y = [int]($r.Y + $r.Height / 2)
}

# Click the first 1080p source, but remember where the user's mouse was.
$originalCursor = [System.Windows.Forms.Cursor]::Position

[U32]::ShowWindow($hwnd, 3) | Out-Null
[U32]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds 150
[U32]::SetCursorPos($x, $y) | Out-Null
Start-Sleep -Milliseconds 100
[U32]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
[U32]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)

Write-Output ("Clicked first 1080p source: " + $match.Current.Name)

# Wait until the source list has transitioned away (normally playback/loading),
# then send a real F11 keystroke to the foreground Stremio window.
$transitionDeadline = [DateTime]::UtcNow.AddSeconds(15)
do {
    Start-Sleep -Milliseconds 500
    $stillOnSources = $false
    try {
        $proc.Refresh()
        $hwnd = $proc.MainWindowHandle
        $root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
        if ($root) {
            $stillOnSources = $null -ne (Get-Descendants $root | Where-Object {
                try {
                    $_.Current.Name -and
                    $_.Current.Name.IndexOf("1080p", [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -and
                    $_.Current.BoundingRectangle.Width -gt 0 -and
                    $_.Current.BoundingRectangle.Height -gt 0
                } catch { $false }
            } | Select-Object -First 1)
        }
    } catch {}
} while ($stillOnSources -and [DateTime]::UtcNow -lt $transitionDeadline)

Start-Sleep -Milliseconds 750
[StremioInputV2]::ShowWindow($hwnd, 3) | Out-Null

$wshell = New-Object -ComObject WScript.Shell
for ($i = 0; $i -lt 10; $i++) {
    [StremioInputV2]::SetForegroundWindow($hwnd) | Out-Null
    $wshell.AppActivate($proc.Id) | Out-Null
    Start-Sleep -Milliseconds 150
    if ([StremioInputV2]::GetForegroundWindow() -eq $hwnd) { break }
}

if ([StremioInputV2]::GetForegroundWindow() -ne $hwnd) {
    throw "Could not bring Stremio to foreground for F11"
}

# VK_F11 = 0x7A. keybd_event generates an actual keyboard event,
# unlike PostMessage which Stremio's WebView can ignore.
[StremioInputV2]::keybd_event(0x7A, 0, 0, [UIntPtr]::Zero)
Start-Sleep -Milliseconds 100
[StremioInputV2]::keybd_event(0x7A, 0, 2, [UIntPtr]::Zero)

Write-Output "Sent real F11 keypress to foreground Stremio."

# Put the user's cursor back where it was before source selection.
[U32]::SetCursorPos($originalCursor.X, $originalCursor.Y) | Out-Null
Write-Output "Restored mouse cursor position."
