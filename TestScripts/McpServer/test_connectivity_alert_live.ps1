# Simulates watchdog health only. Does not disconnect Tailscale or stop Lite/Hub.
[CmdletBinding()]
param([ValidateSet('Legacy','Rust')][string]$Presenter = 'Legacy')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class AlertProbe {
    [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] private static extern IntPtr FindWindow(string cls, string title);
    public static IntPtr FindAlert(string title) { return FindWindow(null, title); }
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] public static extern int GetWindowLong(IntPtr hwnd, int index);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern IntPtr SendMessage(IntPtr hwnd, uint message, IntPtr wParam, IntPtr lParam);
    [StructLayout(LayoutKind.Sequential)] public struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
}
'@
[AlertProbe]::SetThreadDpiAwarenessContext([IntPtr]::new(-4)) | Out-Null
$title = 'OmniHub ' + [char]0x2014 + ' connection alert'
if ($Presenter -eq 'Rust') { $title = 'OmniHub - connection alert (Rust)' }
$taskName = 'OmniMCP-Lite Watchdog'
$path = Join-Path $env:LOCALAPPDATA 'OmniSync/lite-watchdog/status.json'
$initial = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
if (-not $initial.localHealthy -or -not $initial.publicHealthy -or $initial.tailscale.state -ne 'Running') {
    throw 'Run this UI simulation only when the actual connection is healthy'
}
$directory = Join-Path $PSScriptRoot ('../../artifacts/tailscale-alert-ui-' + $Presenter.ToLowerInvariant())
New-Item -ItemType Directory -Path $directory -Force | Out-Null
function Write-State($value) {
    $value.checkedAt = [DateTimeOffset]::UtcNow.ToString('O')
    $temp = "$path.test.tmp"
    [IO.File]::WriteAllText($temp, ($value | ConvertTo-Json -Depth 8))
    Move-Item -LiteralPath $temp -Destination $path -Force
}
function Wait-Visible([int]$seconds = 20) {
    $deadline = (Get-Date).AddSeconds($seconds)
    do {
        $handle = [AlertProbe]::FindAlert($title)
        if ($handle -ne [IntPtr]::Zero -and [AlertProbe]::IsWindowVisible($handle)) { return $handle }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    throw 'Expected connection alert did not appear'
}
function Alert-Visible([string]$windowTitle) {
    $handle = [AlertProbe]::FindAlert($windowTitle)
    return $handle -ne [IntPtr]::Zero -and [AlertProbe]::IsWindowVisible($handle)
}
function Capture([IntPtr]$handle, [string]$name) {
    $rect = New-Object AlertProbe+Rect
    [AlertProbe]::GetWindowRect($handle, [ref]$rect) | Out-Null
    $bitmap = New-Object Drawing.Bitmap(($rect.Right - $rect.Left), ($rect.Bottom - $rect.Top))
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
        $bitmap.Save((Join-Path $directory $name), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
try {
    $deadline = (Get-Date).AddSeconds(12)
    while ((Alert-Visible $title) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 200 }
    if (Alert-Visible $title) { throw 'An existing alert is still open; do not replace a real incident' }
    Disable-ScheduledTask -TaskName $taskName | Out-Null
    Stop-ScheduledTask -TaskName $taskName
    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Process -Id $initial.watchdogPid -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 200 }
    if (Get-Process -Id $initial.watchdogPid -ErrorAction SilentlyContinue) { throw 'Watchdog did not stop for simulation' }
    $foreground = [AlertProbe]::GetForegroundWindow()
    $simulated = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    $simulated.tailscale.state = 'NeedsLogin'
    $simulated.tailscale.online = $false
    $simulated.tailscale.authRequired = $true
    Write-State $simulated
    $handle = Wait-Visible
    if (([AlertProbe]::GetWindowLong($handle, -20) -band 8) -eq 0) { throw 'Alert is not topmost' }
    $screen = [Windows.Forms.Screen]::FromHandle($handle)
    if ([Windows.Forms.Screen]::AllScreens.Count -gt 1 -and $screen.Primary) { throw 'Alert appeared on primary monitor' }
    Start-Sleep -Milliseconds 400
    Capture $handle 'authorization.png'
    if ($Presenter -eq 'Rust' -and (Alert-Visible ('OmniHub ' + [char]0x2014 + ' connection alert'))) {
        throw 'Legacy and Rust showed duplicate alerts'
    }
    $sameFocus = [AlertProbe]::GetForegroundWindow() -eq $foreground
    if ($Presenter -eq 'Rust') {
        Add-Type -AssemblyName UIAutomationClient
        Add-Type -AssemblyName UIAutomationTypes
        $element = [Windows.Automation.AutomationElement]::FromHandle($handle)
        $condition = New-Object Windows.Automation.PropertyCondition([Windows.Automation.AutomationElement]::NameProperty, 'Snooze 1 minute')
        $button = $element.FindFirst([Windows.Automation.TreeScope]::Descendants, $condition)
        if (-not $button) { throw 'Rust Snooze button is not accessible' }
        $pattern = $button.GetCurrentPattern([Windows.Automation.InvokePattern]::Pattern)
        $pattern.Invoke()
        $deadline = (Get-Date).AddSeconds(3)
        while ([AlertProbe]::IsWindowVisible($handle) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 100 }
    } else {
        [AlertProbe]::SendMessage($handle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
    }
    if ([AlertProbe]::IsWindowVisible($handle)) { throw 'Closing did not snooze the alert' }
    Write-Output "PASS: $Presenter authorization popup, topmost, secondary monitor, snooze; foreground unchanged=$sameFocus"
    Write-State $initial
    Enable-ScheduledTask -TaskName $taskName | Out-Null
    Start-ScheduledTask -TaskName $taskName
    $handle = Wait-Visible
    Start-Sleep -Milliseconds 400
    Capture $handle 'recovery.png'
    $deadline = (Get-Date).AddSeconds(12)
    while ((Alert-Visible $title) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 200 }
    if (Alert-Visible $title) { throw 'Recovery popup did not auto-close' }
    Write-Output 'PASS: recovery overrides snooze, then closes automatically'
} finally {
    Write-State $initial
    Enable-ScheduledTask -TaskName $taskName | Out-Null
    Start-ScheduledTask -TaskName $taskName
}
