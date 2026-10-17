
# Replicating OmniHub's AiCliService and ProcessService window finding logic

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public class Win32 {
    [DllImport("user32.dll")]
    public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
    public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll", CharSet = CharSet.Auto, SetLastError = true)]
    public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll")]
    public static extern bool IsWindowVisible(IntPtr hWnd);

    [DllImport("user32.dll")]
    public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT {
        public int Left; public int Top; public int Right; public int Bottom;
    }
}
'@

function Get-GeminiSessionWindow {
    # 1. GetAllGeminiPidsAsync logic (simplified)
    $geminiNodes = Get-WmiObject Win32_Process -Filter "Name LIKE 'node%' AND (CommandLine LIKE '%gemini.js%' OR CommandLine LIKE '%omni_gemini%')"
    if (!$geminiNodes) { Write-Error "No Gemini node processes found."; return }

    # Pick the first session's launcher (simplification for test)
    $launcherPid = $geminiNodes[0].ProcessId
    Write-Host "Found Gemini Launcher PID: $launcherPid"

    # 2. GetProcessTreePidsAsync logic
    $treePids = New-Object System.Collections.Generic.HashSet[int]
    [void]$treePids.Add($launcherPid)

    # Get all processes to build parent map
    $allProcs = Get-WmiObject Win32_Process | Select-Object ProcessId, ParentProcessId, Name
    $parentMap = @{}
    foreach ($p in $allProcs) { $parentMap[$p.ProcessId] = $p.ParentProcessId }

    function Add-Children($targetId) {
        foreach ($p in $allProcs) {
            if ($p.ParentProcessId -eq $targetId) {
                if ($treePids.Add($p.ProcessId)) { Add-Children($p.ProcessId) }
            }
        }
    }
    Add-Children($launcherPid)

    # Walk up to find Root (similar to C# logic)
    $current = $launcherPid
    $rootPid = $launcherPid
    for ($i=0; $i -lt 10; $i++) {
        $parent = $parentMap[$current]
        if (!$parent -or $parent -eq 0) { break }
        $rootPid = $parent
        $parentName = ($allProcs | Where-Object { $_.ProcessId -eq $parent }).Name
        if ($parentName -match "explorer") { break }
        [void]$treePids.Add($parent)
        $current = $parent
    }
    
    Write-Host "Process Tree PIDs: $($treePids -join ', ')"

    # 3. GetVisibleWindowsForPids logic (Scoring)
    $candidates = New-Object System.Collections.Generic.List[PSObject]
    
    [Win32]::EnumWindows({
        param($hwnd, $lparam)
        
        $winPid = 0
        [Win32]::GetWindowThreadProcessId($hwnd, [ref]$winPid)
        
        $isTreeMatch = $treePids.Contains($winPid)
        
        $sb = New-Object System.Text.StringBuilder 256
        [Win32]::GetWindowText($hwnd, $sb, $sb.Capacity)
        $title = $sb.ToString()
        
        $isVisible = [Win32]::IsWindowVisible($hwnd)
        
        $rect = New-Object Win32+RECT
        [Win32]::GetWindowRect($hwnd, [ref]$rect)
        $w = $rect.Right - $rect.Left
        $h = $rect.Bottom - $rect.Top
        
        if ($isTreeMatch) {
            $score = 1000
            if ($isVisible) { $score += 500 }
            if ($w -gt 0 -and $h -gt 0) { $score += 200 } else { $score -= 2000 }
            
            # PID in title [PID] match
            if ($title -match "\[$launcherPid\]" -or $title -match "\($launcherPid\)") { $score += 5000 }
            
            $candidates.Add([PSCustomObject]@{
                Hwnd = $hwnd
                Title = $title
                Score = $score
                Area = $w * $h
            })
        }
        return $true
    }, [IntPtr]::Zero)

    if ($candidates.Count -eq 0) { return $null }
    
    $best = $candidates | Sort-Object Score, Area -Descending | Select-Object -First 1
    return $best
}

$win = Get-GeminiSessionWindow
if ($win) {
    Write-Host "Best Window Found: '$($win.Title)' (HWND: $($win.Hwnd)) Score: $($win.Score)"
    
    # Now spawn the new session in a new tab of this window
    # We use -w 0 assuming it targets the 'current' or most relevant WT window
    # In a real scenario with multiple WT windows, wt targeting is harder without internal IDs, 
    # but the user confirmed -w 0 works.
    wt -w 0 nt cmd /k "B:\GDrive\Tools\13AITools\gmi.exe --workspace D:\SSDProjects\Omni --model gemini-3-flash-preview --sync"
} else {
    Write-Error "Could not find Gemini window."
}
