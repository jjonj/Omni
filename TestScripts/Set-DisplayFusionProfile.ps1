<#
.SYNOPSIS
Loads a DisplayFusion monitor profile and automatically clicks Keep Changes.
.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File .\TestScripts\Set-DisplayFusionProfile.ps1 -Profile SOFA
.NOTES
The Rust Hub's runwait command passes through cmd.exe; quoted -File paths
are escaped incorrectly there. The NORMAL/SOFA voice macros in the active
%APPDATA%\OmniHub\settings.json use PowerShell -EncodedCommand to preserve
the script path. Edit those macros using the Hub SaveMacro API.
Keep the default timeout below the Hub's 30-second command limit. The live
copy is %APPDATA%\OmniSync\Automation\Set-DisplayFusionProfile.ps1.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Profile = 'SOFA',
    [ValidateRange(5, 120)]
    [int]$TimeoutSeconds = 25
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
# Enumerating every UI Automation descendant of DisplayFusion's taskbars can
# block longer than the Hub's 30-second runwait limit. Inspect native buttons
# belonging to DisplayFusion instead; no focus or physical mouse input needed.
if (-not ('DisplayFusionProfileDialog' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class DisplayFusionProfileDialog {
    private delegate bool EnumProc(IntPtr hwnd, IntPtr param);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumProc callback, IntPtr param);
    [DllImport("user32.dll")] private static extern bool EnumChildWindows(IntPtr hwnd, EnumProc callback, IntPtr param);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int count);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool IsWindowEnabled(IntPtr hwnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr hwnd, StringBuilder text, int count);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern bool PostMessage(IntPtr hwnd, uint message, IntPtr wparam, IntPtr lparam);
    public static bool Accept(int processId) {
        bool accepted = false;
        EnumWindows(delegate(IntPtr window, IntPtr unused) {
            uint pid;
            GetWindowThreadProcessId(window, out pid);
            if (pid != (uint)processId || !IsWindowVisible(window)) return true;
            EnumChildWindows(window, delegate(IntPtr child, IntPtr ignored) {
                if (!IsWindowVisible(child) || !IsWindowEnabled(child)) return true;
                var text = new StringBuilder(256);
                GetWindowText(child, text, text.Capacity);
                if (text.ToString().Replace("&", "").Trim() != "Keep Changes") return true;
                var kind = new StringBuilder(256);
                GetClassName(child, kind, kind.Capacity);
                if (kind.ToString().IndexOf("button", StringComparison.OrdinalIgnoreCase) < 0) return true;
                accepted = PostMessage(child, 0x00F5, IntPtr.Zero, IntPtr.Zero); // BM_CLICK
                return !accepted;
            }, IntPtr.Zero);
            return !accepted;
        }, IntPtr.Zero);
        return accepted;
    }
}
'@
}

$displayFusion = Get-Process -Name DisplayFusion | Select-Object -First 1
$commandPath = Join-Path (Split-Path $displayFusion.Path) 'DisplayFusionCommand.exe'
$profileKeys = @(Get-ChildItem 'HKCU:\Software\Binary Fortress Software\DisplayFusion\MonitorConfig' |
    Where-Object { (Get-ItemProperty -LiteralPath $_.PSPath).Name -eq $Profile })
if ($profileKeys.Count -ne 1) { throw "Expected one saved monitor profile named '$Profile'; found $($profileKeys.Count)." }
$savedProfile = Get-ItemProperty -LiteralPath $profileKeys[0].PSPath
$previousLastUsed = $savedProfile.DateTimeLastUsedUTC

# Launch asynchronously so we can accept the dialog while the command waits.
$start = New-Object System.Diagnostics.ProcessStartInfo
$start.FileName = $commandPath
$start.Arguments = '-monitorloadprofile "{0}"' -f $savedProfile.Name
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$command = [System.Diagnostics.Process]::Start($start)
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$accepted = $false
while ([DateTime]::UtcNow -lt $deadline) {
    $command.Refresh()
    if ($command.HasExited -and $command.ExitCode -ne 0) {
        throw "DisplayFusionCommand exited with code $($command.ExitCode)."
    }
    $lastUsed = (Get-ItemProperty -LiteralPath $profileKeys[0].PSPath).DateTimeLastUsedUTC
    if (($command.HasExited -or $accepted) -and $lastUsed -ne $previousLastUsed) {
        Write-Host "Loaded '$($savedProfile.Name)'. Confirmation automatically accepted: $accepted"
        exit 0
    }
    if (-not $accepted -and [DisplayFusionProfileDialog]::Accept($displayFusion.Id)) {
        Write-Host "Accepting DisplayFusion's Keep Changes dialog..."
        $accepted = $true
    }
    Start-Sleep -Milliseconds 100
}
throw "Timed out loading '$Profile'. Automatically accepted confirmation: $accepted. Check DisplayFusion for an error dialog."
