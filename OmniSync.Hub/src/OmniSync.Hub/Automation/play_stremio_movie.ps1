param(
    [Parameter(Mandatory=$true)][ValidatePattern('^tt\d{5,12}$')][string]$MovieId,
    [Parameter(Mandatory=$true)][string]$ExpectedTitle,
    [string]$Quality = "1080p,720p,2160p",
    [string]$StremioExe = "",
    [ValidateRange(15,120)][int]$TimeoutSeconds = 105
)

$ErrorActionPreference = "Stop"
$overallDeadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)

function Fail([string]$Message, [int]$Code) {
    [Console]::Error.WriteLine($Message)
    exit $Code
}

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms

if (-not ("StremioNativeV3" -as [type])) {
    Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class StremioNativeV3 {
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int X, int Y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extra);
    [DllImport("user32.dll")] public static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
}
"@
}

if ([string]::IsNullOrWhiteSpace($StremioExe)) {
    $StremioExe = Join-Path $env:LOCALAPPDATA "Programs\Stremio\stremio-shell-ng.exe"
}
if (-not (Test-Path -LiteralPath $StremioExe)) {
    Fail "Stremio executable not found at $StremioExe" 3
}

$qualities = $Quality.Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
if ($qualities.Count -eq 0) { throw "At least one quality must be supplied" }

$movieUrl = "stremio:///detail/movie/$MovieId/$MovieId"
Start-Process -FilePath $StremioExe -ArgumentList ('"' + $movieUrl + '"')

$proc = $null
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 250
    $proc = Get-Process -Name "stremio-shell-ng" -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 } |
        Select-Object -First 1
    if ($proc) { break }
}
if (-not $proc) { Fail "Stremio window not found" 3 }

$hwnd = $proc.MainWindowHandle
[StremioNativeV3]::ShowWindow($hwnd, 3) | Out-Null
[StremioNativeV3]::SetForegroundWindow($hwnd) | Out-Null

$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker

function Get-Descendants([System.Windows.Automation.AutomationElement]$node) {
    $child = $walker.GetFirstChild($node)
    while ($child) {
        $child
        Get-Descendants $child
        $child = $walker.GetNextSibling($child)
    }
}

function Get-VisibleElements($root) {
    @(Get-Descendants $root | Where-Object {
        try {
            $_.Current.BoundingRectangle.Width -gt 0 -and
            $_.Current.BoundingRectangle.Height -gt 0
        } catch { $false }
    })
}

function Test-ExpectedTitle($elements, [string]$title) {
    $wanted = ($title -replace '[^\p{L}\p{N}]', '').ToLowerInvariant()
    if (-not $wanted) { return $false }
    foreach ($element in $elements) {
        try {
            $name = ($element.Current.Name -replace '[^\p{L}\p{N}]', '').ToLowerInvariant()
            if ($name -eq $wanted -or ($name.Length -gt $wanted.Length -and $name.Contains($wanted))) {
                return $true
            }
        } catch {}
    }
    return $false
}

function Find-QualityElement($elements, $qualityList) {
    foreach ($preferred in $qualityList) {
        foreach ($element in $elements) {
            try {
                $name = $element.Current.Name
                $control = $element.Current.ControlType.ProgrammaticName
                $allowed = $control -in @(
                    'ControlType.ListItem',
                    'ControlType.Hyperlink',
                    'ControlType.Button'
                )
                if ($allowed -and $name -and
                    $name.IndexOf($preferred, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
                    return [PSCustomObject]@{ Element = $element; Quality = $preferred }
                }
            } catch {}
        }
    }
    return $null
}

function Test-Fullscreen([IntPtr]$handle) {
    $rect = New-Object StremioNativeV3+RECT
    if (-not [StremioNativeV3]::GetWindowRect($handle, [ref]$rect)) { return $false }
    $bounds = [System.Windows.Forms.Screen]::FromHandle($handle).Bounds
    $tolerance = 3
    return (
        [Math]::Abs($rect.Left - $bounds.Left) -le $tolerance -and
        [Math]::Abs($rect.Top - $bounds.Top) -le $tolerance -and
        [Math]::Abs($rect.Right - $bounds.Right) -le $tolerance -and
        [Math]::Abs($rect.Bottom - $bounds.Bottom) -le $tolerance
    )
}

# Do not touch a source until the newly navigated page contains the expected movie title.
$selection = $null
$sourceWaitSeconds = [Math]::Min(35, [Math]::Max(5, $TimeoutSeconds - 20))
$deadline = [DateTime]::UtcNow.AddSeconds($sourceWaitSeconds)
if ($deadline -gt $overallDeadline) { $deadline = $overallDeadline }
while (-not $selection -and [DateTime]::UtcNow -lt $deadline) {
    try {
        $proc.Refresh()
        $hwnd = $proc.MainWindowHandle
        $root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
        if ($root) {
            $elements = Get-VisibleElements $root
            if (Test-ExpectedTitle $elements $ExpectedTitle) {
                $selection = Find-QualityElement $elements $qualities
            }
        }
    } catch {}

    if (-not $selection) { Start-Sleep -Milliseconds 500 }
}

if (-not $selection) {
    Fail "Expected title '$ExpectedTitle' and a preferred stream quality did not appear before timeout" 2
}

$match = $selection.Element
$chosenQuality = $selection.Quality
$clickedName = $match.Current.Name
$invoked = $false

# Prefer UI Automation invocation: it does not hijack the user's cursor.
try {
    $pattern = $null
    if ($match.TryGetCurrentPattern(
        [System.Windows.Automation.InvokePattern]::Pattern,
        [ref]$pattern)) {
        ([System.Windows.Automation.InvokePattern]$pattern).Invoke()
        $invoked = $true
    }
} catch {}

$originalCursor = [System.Windows.Forms.Cursor]::Position
if (-not $invoked) {
    try {
        $pt = $match.GetClickablePoint()
        $x = [int]$pt.X
        $y = [int]$pt.Y
    } catch {
        $r = $match.Current.BoundingRectangle
        $x = [int]($r.X + $r.Width / 2)
        $y = [int]($r.Y + $r.Height / 2)
    }

    [StremioNativeV3]::SetForegroundWindow($hwnd) | Out-Null
    Start-Sleep -Milliseconds 150
    [StremioNativeV3]::SetCursorPos($x, $y) | Out-Null
    [StremioNativeV3]::mouse_event(0x0002, 0, 0, 0, [UIntPtr]::Zero)
    [StremioNativeV3]::mouse_event(0x0004, 0, 0, 0, [UIntPtr]::Zero)
    [StremioNativeV3]::SetCursorPos($originalCursor.X, $originalCursor.Y) | Out-Null
}

# Wait for the selected stream row to disappear, indicating playback/loading transition.
$transitionDeadline = [DateTime]::UtcNow.AddSeconds(15)
if ($transitionDeadline -gt $overallDeadline) { $transitionDeadline = $overallDeadline }
do {
    Start-Sleep -Milliseconds 500
    $stillOnSources = $false
    try {
        $proc.Refresh()
        $hwnd = $proc.MainWindowHandle
        $root = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
        if ($root) {
            $elements = Get-VisibleElements $root
            $stillOnSources = $null -ne (Find-QualityElement $elements $qualities)
        }
    } catch {}
} while ($stillOnSources -and [DateTime]::UtcNow -lt $transitionDeadline)

Start-Sleep -Milliseconds 750
$sentF11 = $false
if (-not (Test-Fullscreen $hwnd)) {
    $wshell = New-Object -ComObject WScript.Shell
    for ($i = 0; $i -lt 10; $i++) {
        [StremioNativeV3]::SetForegroundWindow($hwnd) | Out-Null
        $wshell.AppActivate($proc.Id) | Out-Null
        Start-Sleep -Milliseconds 150
        if ([StremioNativeV3]::GetForegroundWindow() -eq $hwnd) { break }
    }

    if ([StremioNativeV3]::GetForegroundWindow() -ne $hwnd) {
        Fail "Could not bring Stremio to foreground for fullscreen" 3
    }

    [StremioNativeV3]::keybd_event(0x7A, 0, 0, [UIntPtr]::Zero)
    Start-Sleep -Milliseconds 100
    [StremioNativeV3]::keybd_event(0x7A, 0, 2, [UIntPtr]::Zero)
    $sentF11 = $true
    Start-Sleep -Milliseconds 500
}

$fullscreen = Test-Fullscreen $hwnd
$result = [ordered]@{
    ok = $true
    movieId = $MovieId
    expectedTitle = $ExpectedTitle
    source = $clickedName
    quality = $chosenQuality
    invoked = $invoked
    fullscreen = $fullscreen
    sentF11 = $sentF11
}
Write-Output ("RESULT " + ($result | ConvertTo-Json -Compress))
