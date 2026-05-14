using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class ProcessService
    {
        [DllImport("user32.dll")]
        private static extern bool SetForegroundWindow(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);

        [DllImport("user32.dll")]
        private static extern bool IsIconic(IntPtr hWnd);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);

        [DllImport("user32.dll", SetLastError = true)]
        public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

        [DllImport("user32.dll")]
        private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint dwFlags);

        [DllImport("user32.dll")]
        private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO lpmi);

        [DllImport("user32.dll")]
        private static extern bool EnumDisplayMonitors(IntPtr hdc, IntPtr lprcClip, MonitorEnumProc lpfnEnum, IntPtr dwData);

        private delegate bool MonitorEnumProc(IntPtr hMonitor, IntPtr hdcMonitor, ref RECT lprcMonitor, IntPtr dwData);

        [StructLayout(LayoutKind.Sequential)]
        private struct MONITORINFO
        {
            public int cbSize;
            public RECT rcMonitor;
            public RECT rcWork;
            public uint dwFlags;
        }

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

        private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

        [DllImport("user32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

        [DllImport("user32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        private static extern bool SetWindowText(IntPtr hWnd, string lpString);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool AttachConsole(uint dwProcessId);

        [DllImport("kernel32.dll", SetLastError = true, ExactSpelling = true)]
        private static extern bool FreeConsole();

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool SetConsoleTitle(string lpConsoleTitle);

        [DllImport("user32.dll")]
        private static extern bool IsWindowVisible(IntPtr hWnd);

        [DllImport("user32.dll")]
        private static extern bool BringWindowToTop(IntPtr hWnd);

        [DllImport("kernel32.dll")]
        private static extern uint GetCurrentThreadId();

        [DllImport("user32.dll")]
        private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);

        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [StructLayout(LayoutKind.Sequential)]
        public struct RECT
        {
            public int Left;
            public int Top;
            public int Right;
            public int Bottom;
        }

        public const uint SWP_NOZORDER = 0x0004;
        public const uint SWP_SHOWWINDOW = 0x0040;
        private const uint MONITOR_DEFAULTTONEAREST = 2;

        private const int SW_RESTORE = 9;

        private readonly HubSettingsService _settingsService;
        private readonly Logic.Monitoring.HubMonitorService _monitorService;
        public event EventHandler<string> CommandOutputReceived;

        private readonly System.Collections.Concurrent.ConcurrentDictionary<(IntPtr hWnd, IntPtr hMonitor), (float ratioX, float ratioY)> _lastPositions = new();

        public ProcessService(HubSettingsService settingsService, Logic.Monitoring.HubMonitorService monitorService)
        {
            _settingsService = settingsService;
            _monitorService = monitorService;
        }

        public virtual async Task ExecuteCommand(string command)
        {
            _monitorService.AddLogMessage($"[ProcessService] ExecuteCommand: '{command}'");
            string finalCommand = command;
            string executable = "";
            string arguments = "";

            if (command.StartsWith("\""))
            {
                int nextQuote = command.IndexOf("\"", 1);
                if (nextQuote != -1)
                {
                    executable = command.Substring(1, nextQuote - 1);
                    arguments = command.Substring(nextQuote + 1).Trim();
                }
                else executable = command;
            }
            else
            {
                (executable, arguments) = FindLongestExistingPath(command);
                if (string.IsNullOrEmpty(executable))
                {
                    var parts = command.Split(' ', 2);
                    executable = parts[0];
                    if (parts.Length > 1) arguments = parts[1];
                }
            }

            var mappedPath = _settingsService.GetPath(executable);
            if (mappedPath != null)
            {
                finalCommand = string.IsNullOrEmpty(arguments) ? $"\"{mappedPath}\"" : $"\"{mappedPath}\" {arguments}";
            }
            else if (!command.StartsWith("\"") && executable.Contains(" "))
            {
                finalCommand = string.IsNullOrEmpty(arguments) ? $"\"{executable}\"" : $"\"{executable}\" {arguments}";
            }

            await Task.Run(() =>
            {
                var processStartInfo = new ProcessStartInfo
                {
                    FileName = "cmd.exe",
                    Arguments = $"/c {finalCommand}", 
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };

                using (var process = new Process { StartInfo = processStartInfo })
                {
                    process.OutputDataReceived += (sender, args) =>
                    {
                        if (!string.IsNullOrEmpty(args.Data)) CommandOutputReceived?.Invoke(this, args.Data + Environment.NewLine);
                    };
                    process.ErrorDataReceived += (sender, args) =>
                    {
                        if (!string.IsNullOrEmpty(args.Data)) CommandOutputReceived?.Invoke(this, "[ERROR] " + args.Data + Environment.NewLine);
                    };

                    try { process.Start(); }
                    catch (Exception ex) { _monitorService.AddLogMessage($"[ERROR] Process Start failed: {ex.Message}"); return; }

                    process.BeginOutputReadLine();
                    process.BeginErrorReadLine();
                    process.WaitForExit();
                }
            });
        }

        public virtual async Task ExecuteCommandWithLogging(string command, string logPrefix = "[Process]")
        {
            await Task.Run(() =>
            {
                var processStartInfo = new ProcessStartInfo
                {
                    FileName = "cmd.exe",
                    Arguments = $"/c {command}",
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };

                using (var process = new Process { StartInfo = processStartInfo })
                {
                    process.OutputDataReceived += (sender, args) => { if (!string.IsNullOrEmpty(args.Data)) _monitorService.AddLogMessage($"{logPrefix} {args.Data}"); };
                    process.ErrorDataReceived += (sender, args) => { if (!string.IsNullOrEmpty(args.Data)) _monitorService.AddLogMessage($"{logPrefix} [ERROR] {args.Data}"); };
                    try { process.Start(); process.BeginOutputReadLine(); process.BeginErrorReadLine(); process.WaitForExit(); }
                    catch { }
                }
            });
        }

        public virtual void ShellExecute(string path, string? arguments = null, string? workingDirectory = null)
        {
            try
            {
                var psi = new ProcessStartInfo { FileName = path, Arguments = arguments ?? "", UseShellExecute = true, WorkingDirectory = workingDirectory ?? "" };
                Process.Start(psi);
            }
            catch (Exception ex) { _monitorService.AddLogMessage($"[ERROR] ShellExecute failed for {path}: {ex.Message}"); }
        }

        public virtual void ExecuteCommandNonAdmin(string command, string? arguments = null, string? workingDirectory = null)
        {
            Task.Run(() => {
                try
                {
                    Type shellType = Type.GetTypeFromProgID("Shell.Application");
                    if (shellType != null)
                    {
                        dynamic shell = Activator.CreateInstance(shellType);
                        dynamic windows = shell.Windows();
                        for (int i = 0; i < windows.Count; i++)
                        {
                            try
                            {
                                dynamic window = windows.Item(i);
                                if (window != null && ((string)window.FullName).EndsWith("explorer.exe", StringComparison.OrdinalIgnoreCase))
                                {
                                    window.Document.Application.ShellExecute(command, arguments ?? "", workingDirectory ?? "", "open", 1);
                                    return;
                                }
                            }
                            catch { }
                        }
                        string tempBat = Path.Combine(Path.GetTempPath(), $"launch_non_admin_{Guid.NewGuid():N}.bat");
                        string cdCmd = !string.IsNullOrEmpty(workingDirectory) ? $"cd /d \"{workingDirectory}\"\r\n" : "";
                        File.WriteAllText(tempBat, $"@echo off\r\n{cdCmd}\"{command}\" {arguments}");
                        Process.Start("explorer.exe", $"\"{tempBat}\"");
                        Task.Delay(5000).ContinueWith(_ => { try { File.Delete(tempBat); } catch {} });
                    }
                }
                catch { }
            });
        }

        private (string executable, string arguments) FindLongestExistingPath(string command)
        {
            if (string.IsNullOrWhiteSpace(command) || !command.Contains(" ")) return ("", "");
            int lastSpace = command.Length;
            while ((lastSpace = command.LastIndexOf(' ', lastSpace - 1)) != -1)
            {
                var potentialPath = command.Substring(0, lastSpace);
                if (File.Exists(potentialPath)) return (potentialPath, command.Substring(lastSpace + 1).Trim());
            }
            if (File.Exists(command)) return (command, "");
            return ("", "");
        }

        public void WinActivate(string target)
        {
            _ = Task.Run(() => {
                string script = $@"
$target = '{target.Replace("'", "''")}'
$wshell = New-Object -ComObject WScript.Shell
if ($wshell.AppActivate($target)) {{ exit }}
$procs = Get-Process | Where-Object {{ $_.MainWindowTitle -like ""*$target*"" -or $_.ProcessName -eq $target }}
foreach ($p in $procs) {{ if ($wshell.AppActivate($p.Id)) {{ exit }} }}";
                RunPowerShellSynchronous(script);
                var procs = Process.GetProcesses().Where(p => (p.MainWindowTitle.Contains(target, StringComparison.OrdinalIgnoreCase) || p.ProcessName.Equals(target, StringComparison.OrdinalIgnoreCase)) && p.MainWindowHandle != IntPtr.Zero).ToList();
                foreach (var p in procs) { System.Windows.Application.Current?.Dispatcher?.BeginInvoke(new Action(() => ActivateWindow(p.MainWindowHandle))); }
            });
        }

        public void WinActivatePid(int pid, string? titleHint = null)
        {
            _ = Task.Run(async () => {
                try
                {
                    var treePids = await GetProcessTreePidsAsync(pid);
                    var hwnds = GetVisibleWindowsForPids(treePids, titleHint);
                    if (hwnds.Any())
                    {
                        var targetHwnd = hwnds.First();
                        System.Windows.Application.Current?.Dispatcher?.BeginInvoke(new Action(() => ActivateWindow(targetHwnd)));
                    }
                }
                catch { }
            });
        }

        private (DateTime LastUpdate, Dictionary<int, int> ParentMap, Dictionary<int, string> NameMap) _procCache;
        private readonly SemaphoreSlim _cacheLock = new SemaphoreSlim(1, 1);

        private async Task<(Dictionary<int, int> ParentMap, Dictionary<int, string> NameMap)> GetProcessMapsAsync()
        {
            await _cacheLock.WaitAsync();
            try
            {
                if (DateTime.Now - _procCache.LastUpdate < TimeSpan.FromSeconds(5) && _procCache.ParentMap != null) return (_procCache.ParentMap, _procCache.NameMap);
                var parentMap = new Dictionary<int, int>();
                var nameMap = new Dictionary<int, string>();
                using (var searcher = new System.Management.ManagementObjectSearcher("SELECT ProcessId, ParentProcessId, Name FROM Win32_Process"))
                using (var results = searcher.Get())
                {
                    foreach (var obj in results)
                    {
                        try { int pId = Convert.ToInt32(obj["ProcessId"]); int parentId = Convert.ToInt32(obj["ParentProcessId"]); string name = obj["Name"]?.ToString() ?? ""; parentMap[pId] = parentId; nameMap[pId] = name; }
                        catch { } finally { obj.Dispose(); }
                    }
                }
                _procCache = (DateTime.Now, parentMap, nameMap);
                return (parentMap, nameMap);
            }
            catch { return (new Dictionary<int, int>(), new Dictionary<int, string>()); }
            finally { _cacheLock.Release(); }
        }

        public void SetWindowTitle(int pid, string title, string? titleHint = null)
        {
            _ = Task.Run(async () =>
            {
                try
                {
                    var treePids = await GetProcessTreePidsAsync(pid);
                    foreach (var pId in treePids)
                    {
                        FreeConsole();
                        if (AttachConsole((uint)pId)) { SetConsoleTitle(title); FreeConsole(); }
                    }
                    var hwnds = GetVisibleWindowsForPids(treePids, titleHint);
                    if (hwnds.Any())
                    {
                        var targetHwnd = hwnds.First();
                        System.Windows.Application.Current?.Dispatcher?.BeginInvoke(new Action(() => SetWindowText(targetHwnd, title)));
                    }
                }
                catch { }
            });
        }

        private async Task<HashSet<int>> GetProcessTreePidsAsync(int rootPid)
        {
            var result = new HashSet<int> { rootPid };
            string jobName = "GeminiJob_" + rootPid;
            IntPtr hJob = OpenJobObject(JOB_OBJECT_QUERY, false, jobName);
            if (hJob != IntPtr.Zero)
            {
                try
                {
                    uint size = (uint)Marshal.SizeOf(typeof(JOBOBJECT_BASIC_PROCESS_ID_LIST));
                    IntPtr ptr = Marshal.AllocHGlobal((int)size);
                    uint returnLength = 0;
                    if (QueryInformationJobObject(hJob, JobObjectBasicProcessIdList, ptr, size, out returnLength))
                    {
                        var info = (JOBOBJECT_BASIC_PROCESS_ID_LIST)Marshal.PtrToStructure(ptr, typeof(JOBOBJECT_BASIC_PROCESS_ID_LIST));
                        for (int i = 0; i < info.NumberOfProcessIdsInList; i++) { int pId = (int)info.ProcessIdList[i]; if (pId != 0) result.Add(pId); }
                    }
                    Marshal.FreeHGlobal(ptr);
                }
                catch { } finally { CloseHandle(hJob); }
                if (result.Count > 1) return result;
            }

            var maps = await GetProcessMapsAsync();
            if (maps.ParentMap.Count == 0) return result;
            void AddChildren(int pId, int depth)
            {
                if (depth > 20) return;
                foreach (var kvp in maps.ParentMap) { if (kvp.Value == pId && result.Add(kvp.Key)) AddChildren(kvp.Key, depth + 1); }
            }
            AddChildren(rootPid, 0);
            int currentPid = rootPid;
            int parentDepth = 0;
            while (maps.ParentMap.TryGetValue(currentPid, out int parentId) && parentId != 0 && parentDepth++ < 10)
            {
                if (maps.NameMap.TryGetValue(parentId, out string parentName))
                {
                    if (parentName.ToLower().Contains("explorer")) break;
                    if (result.Add(parentId))
                    {
                        foreach (var kvp in maps.ParentMap)
                        {
                            if (kvp.Value == parentId && !result.Contains(kvp.Key))
                            {
                                if (maps.NameMap.TryGetValue(kvp.Key, out string siblingName) && (siblingName.ToLower().Contains("conhost") || siblingName.ToLower().Contains("openconsole"))) result.Add(kvp.Key);
                            }
                        }
                    }
                }
                currentPid = parentId;
            }
            return result;
        }

        [DllImport("user32.dll")]
        private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, int dwExtraInfo);
        private const byte VK_MENU = 0x12;
        private const uint KEYEVENTF_KEYUP = 0x0002;
        [DllImport("user32.dll")]
        private static extern bool LockSetForegroundWindow(uint uLockCode);
        [DllImport("user32.dll")]
        private static extern void SwitchToThisWindow(IntPtr hWnd, bool fAltTab);
        private const uint LSFW_UNLOCK = 2;

        private void ActivateWindow(IntPtr handle)
        {
            if (handle == IntPtr.Zero) return;
            if (IsIconic(handle)) ShowWindow(handle, SW_RESTORE);
            LockSetForegroundWindow(LSFW_UNLOCK);
            uint foregroundThreadId = GetWindowThreadProcessId(GetForegroundWindow(), out _);
            uint targetThreadId = GetWindowThreadProcessId(handle, out _);
            uint currentThreadId = GetCurrentThreadId();
            if (foregroundThreadId != targetThreadId)
            {
                AttachThreadInput(currentThreadId, foregroundThreadId, true);
                AttachThreadInput(currentThreadId, targetThreadId, true);
                SetForegroundWindow(handle); BringWindowToTop(handle); ShowWindow(handle, 5);
                AttachThreadInput(currentThreadId, targetThreadId, false); AttachThreadInput(currentThreadId, foregroundThreadId, false);
            }
            else { SetForegroundWindow(handle); BringWindowToTop(handle); ShowWindow(handle, 5); }
            SwitchToThisWindow(handle, true);
            keybd_event(VK_MENU, 0, 0, 0); keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0);
            SetForegroundWindow(handle);
        }

        public void WinClose(string target) { _ = Task.Run(() => { string script = $@"
$target = '{target.Replace("'", "''")}'
$procs = Get-Process | Where-Object {{ $_.MainWindowTitle -eq $target -or $_.MainWindowTitle -like ""*$target*"" -or $_.ProcessName -eq $target }}
foreach ($p in $procs) {{ $p.CloseMainWindow(); Sleep -Milliseconds 200; if (!$p.HasExited) {{ $p.Kill() }} }}"; RunPowerShellSynchronous(script); }); }

        public void WinMinimize(string target) { _ = Task.Run(() => { string script = $@"
$target = '{target.Replace("'", "''")}'
$procs = Get-Process | Where-Object {{ $_.MainWindowTitle -like ""*$target*"" -or $_.ProcessName -eq $target }}
$wshell = New-Object -ComObject WScript.Shell
foreach ($p in $procs) {{ if ($wshell.AppActivate($p.Id)) {{ $wshell.SendKeys('% n') }} }}"; RunPowerShellSynchronous(script); }); }

        public void WinMaximize(string target) { _ = Task.Run(() => { string script = $@"
$target = '{target.Replace("'", "''")}'
$procs = Get-Process | Where-Object {{ $_.MainWindowTitle -like ""*$target*"" -or $_.ProcessName -eq $target }}
$wshell = New-Object -ComObject WScript.Shell
foreach ($p in $procs) {{ if ($wshell.AppActivate($p.Id)) {{ $wshell.SendKeys('% x') }} }}"; RunPowerShellSynchronous(script); }); }

        public void MoveWindow(IntPtr hWnd, int x, int y, int width, int height)
        {
            if (hWnd == IntPtr.Zero) return;
            if (IsIconic(hWnd)) ShowWindow(hWnd, SW_RESTORE);
            SetWindowPos(hWnd, IntPtr.Zero, x, y, width, height, SWP_NOZORDER | SWP_SHOWWINDOW);
        }

        public void WinHide(string target) { _ = Task.Run(() => { string script = $@"
$code = @'
[DllImport(""user32.dll"")]
public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
'@
Add-Type -MemberDefinition $code -Name Win32 -Namespace Native
$target = '{target.Replace("'", "''")}'
$procs = Get-Process | Where-Object {{ $_.MainWindowTitle -like ""*$target*"" -or $_.ProcessName -eq $target }}
foreach ($p in $procs) {{ [Native.Win32]::ShowWindow($p.MainWindowHandle, 0) }}"; RunPowerShellSynchronous(script); }); }

        public bool WaitWinActive(string target, int timeoutMs)
        {
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < timeoutMs) { if (IsWindowActive(target)) return true; Thread.Sleep(200); }
            return false;
        }

        private bool IsWindowActive(string target)
        {
            try {
                var proc = new Process { StartInfo = new ProcessStartInfo { FileName = "powershell", Arguments = $"-Command \"if ((Get-Process | Where-Object {{ $_.MainWindowTitle -like '*{target}*' -or $_.ProcessName -eq '{target}' }}).MainWindowHandle -contains (Get-ForegroundWindow)) {{ exit 0 }} else {{ exit 1 }}\"", CreateNoWindow = true, UseShellExecute = false } };
                proc.Start(); proc.WaitForExit(); return proc.ExitCode == 0;
            } catch { return false; }
        }

        public void MouseMoveAbs(int x, int y) { RunPowerShell($"[Cursor]::Position = New-Object System.Drawing.Point({x}, {y})"); }

        public void MouseClickAt(string button, int x, int y)
        {
            string btnCode = button.ToLower() == "right" ? "0x0008 | 0x0010" : "0x0002 | 0x0004";
            string script = $@"
$code = @'
[DllImport(""user32.dll"")]
public static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);
'@
Add-Type -MemberDefinition $code -Name Win32 -Namespace Native
[Cursor]::Position = New-Object System.Drawing.Point({x}, {y})
[Native.Win32]::mouse_event({btnCode}, 0, 0, 0, 0)";
            RunPowerShell(script);
        }

        public void RunPowerShell(string script) { Task.Run(() => RunPowerShellSynchronous(script)); }

        private string RunPowerShellSynchronous(string script)
        {
            string tempFile = Path.Combine(Path.GetTempPath(), $"omni_script_{Guid.NewGuid():N}.ps1");
            try
            {
                File.WriteAllText(tempFile, script, Encoding.UTF8);
                var psi = new ProcessStartInfo { FileName = "powershell.exe", Arguments = $"-NoProfile -ExecutionPolicy Bypass -File \"{tempFile}\"", RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false, CreateNoWindow = true };
                using (var process = Process.Start(psi))
                {
                    if (process == null) return "";
                    string output = process.StandardOutput.ReadToEnd();
                    process.WaitForExit();
                    return output;
                }
            }
            catch { return ""; }
            finally { if (File.Exists(tempFile)) try { File.Delete(tempFile); } catch { } }
        }

        public IEnumerable<ProcessInfo> ListProcesses()
        {
            return Process.GetProcesses().Select(p => new ProcessInfo { Id = p.Id, Name = p.ProcessName, MemoryUsage = p.WorkingSet64, CpuUsage = 0 }).ToList();
        }

        public bool KillProcess(int processId) { try { var process = Process.GetProcessById(processId); process.Kill(); return true; } catch { return false; } }

        public bool IsProcessRunning(string processName) { try { var procs = Process.GetProcessesByName(processName); return procs != null && procs.Length > 0; } catch { return false; } }

        public void KillProcessTree(int rootPid)
        {
            try { using var process = new Process(); process.StartInfo.FileName = "taskkill.exe"; process.StartInfo.Arguments = $"/F /T /PID {rootPid}"; process.StartInfo.CreateNoWindow = true; process.StartInfo.UseShellExecute = false; process.Start(); process.WaitForExit(5000); }
            catch { }
        }

        public void MoveWindowOpposite(int pid, string? titleHint = null)
        {
            _ = Task.Run(async () => {
                try {
                    var targetPids = await GetProcessTreePidsAsync(pid);
                    var hwnds = GetVisibleWindowsForPids(targetPids, titleHint);
                    System.Windows.Application.Current?.Dispatcher?.BeginInvoke(new Action(() => MoveHwndsToOppositeMonitor(hwnds)));
                } catch { }
            });
        }

        public void MoveWindowOpposite(string titleOrName)
        {
            _ = Task.Run(() => {
                try {
                    var hwnds = GetWindowsByTitleOrProcessName(titleOrName);
                    System.Windows.Application.Current?.Dispatcher?.BeginInvoke(new Action(() => MoveHwndsToOppositeMonitor(hwnds)));
                } catch { }
            });
        }

        public void MoveWindowToMonitor(int pid, int monitorIndex)
        {
            _ = Task.Run(async () => {
                try {
                    var targetPids = await GetProcessTreePidsAsync(pid);
                    var hwnds = GetVisibleWindowsForPids(targetPids);
                    if (hwnds.Count == 0) return;
                    var monitors = new List<IntPtr>();
                    EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, (IntPtr hMonitor, IntPtr hdcMonitor, ref RECT lprcMonitor, IntPtr dwData) => { monitors.Add(hMonitor); return true; }, IntPtr.Zero);
                    if (monitorIndex < 0 || monitorIndex >= monitors.Count) return;
                    IntPtr hTargetMonitor = monitors[monitorIndex];
                    System.Windows.Application.Current?.Dispatcher?.BeginInvoke(new Action(() => {
                        foreach (var hWnd in hwnds.Distinct()) MoveHwndProportional(hWnd, hTargetMonitor, monitorIndex);
                    }));
                } catch { }
            });
        }

        private void MoveHwndProportional(IntPtr hWnd, IntPtr hTargetMonitor, int targetIndex)
        {
            IntPtr hCurrentMonitor = MonitorFromWindow(hWnd, MONITOR_DEFAULTTONEAREST);
            MONITORINFO currentInfo = new MONITORINFO { cbSize = Marshal.SizeOf(typeof(MONITORINFO)) };
            GetMonitorInfo(hCurrentMonitor, ref currentInfo);
            MONITORINFO targetInfo = new MONITORINFO { cbSize = Marshal.SizeOf(typeof(MONITORINFO)) };
            GetMonitorInfo(hTargetMonitor, ref targetInfo);
            GetWindowRect(hWnd, out RECT rect);
            int w = rect.Right - rect.Left; int h = rect.Bottom - rect.Top;
            int curW = currentInfo.rcWork.Right - currentInfo.rcWork.Left; int curH = currentInfo.rcWork.Bottom - currentInfo.rcWork.Top;
            int tarW = targetInfo.rcWork.Right - targetInfo.rcWork.Left; int tarH = targetInfo.rcWork.Bottom - targetInfo.rcWork.Top;
            if (curW <= 0 || curH <= 0 || tarW <= 0 || tarH <= 0) return;
            float relX = rect.Left - currentInfo.rcWork.Left; float relY = rect.Top - currentInfo.rcWork.Top;
            float ratioX = relX / curW; float ratioY = relY / curH;
            _lastPositions[(hWnd, hCurrentMonitor)] = (ratioX, ratioY);
            if (_lastPositions.TryGetValue((hWnd, hTargetMonitor), out var lastPos)) { ratioX = lastPos.ratioX; ratioY = lastPos.ratioY; }
            int newX = targetInfo.rcWork.Left + (int)(ratioX * tarW); int newY = targetInfo.rcWork.Top + (int)(ratioY * tarH);
            int newW = Math.Min(w, tarW); int newH = Math.Min(h, tarH);
            if (newX + newW > targetInfo.rcWork.Right) newX = targetInfo.rcWork.Right - newW;
            if (newY + newH > targetInfo.rcWork.Bottom) newY = targetInfo.rcWork.Bottom - newH;
            if (newX < targetInfo.rcWork.Left) newX = targetInfo.rcWork.Left;
            if (newY < targetInfo.rcWork.Top) newY = targetInfo.rcWork.Top;
            if (IsIconic(hWnd)) ShowWindow(hWnd, SW_RESTORE);
            SetWindowPos(hWnd, IntPtr.Zero, newX, newY, newW, newH, SWP_NOZORDER | SWP_SHOWWINDOW);
            SetForegroundWindow(hWnd);
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr OpenJobObject(uint dwDesiredAccess, bool bInheritHandle, string lpName);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool QueryInformationJobObject(IntPtr hJob, int JobObjectInfoClass, IntPtr lpJobObjectInfo, uint cbJobObjectInfoLength, out uint lpReturnLength);
        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool CloseHandle(IntPtr hObject);
        [StructLayout(LayoutKind.Sequential)]
        struct JOBOBJECT_BASIC_PROCESS_ID_LIST { public uint NumberOfAssignedProcesses; public uint NumberOfProcessIdsInList; [MarshalAs(UnmanagedType.ByValArray, SizeConst = 128)] public UIntPtr[] ProcessIdList; }
        const uint JOB_OBJECT_QUERY = 0x0004;
        const int JobObjectBasicProcessIdList = 3;

        private List<IntPtr> GetVisibleWindowsForPids(HashSet<int> pids, string? titleHint = null)
        {
            var candidates = new List<(IntPtr hWnd, int priority, long area, string title, string procName)>();
            EnumWindows((hWnd, lParam) =>
            {
                GetWindowThreadProcessId(hWnd, out uint processId);
                bool isTreeMatch = pids.Contains((int)processId);
                var sb = new StringBuilder(256);
                GetWindowText(hWnd, sb, sb.Capacity);
                string title = sb.ToString();
                bool isVisible = IsWindowVisible(hWnd);
                GetWindowRect(hWnd, out RECT rect);
                int w = rect.Right - rect.Left; int h = rect.Bottom - rect.Top;
                string procName = "Unknown";
                try { procName = Process.GetProcessById((int)processId).ProcessName; } catch { }
                bool isPidInTitle = false; int? foundPidInTitle = null;
                var pidMatch = System.Text.RegularExpressions.Regex.Match(title, @"\[(\d+)\]|\((\d+)\)");
                if (pidMatch.Success) { string pidStr = pidMatch.Groups[1].Value; if (string.IsNullOrEmpty(pidStr)) pidStr = pidMatch.Groups[2].Value; if (int.TryParse(pidStr, out int p)) { foundPidInTitle = p; if (pids.Contains(p)) isPidInTitle = true; } }
                if (!foundPidInTitle.HasValue) { var trailingMatch = System.Text.RegularExpressions.Regex.Match(title, @" (\d+)$"); if (trailingMatch.Success && int.TryParse(trailingMatch.Groups[1].Value, out int p)) { foundPidInTitle = p; if (pids.Contains(p)) isPidInTitle = true; } }
                bool isTitleMatch = !string.IsNullOrEmpty(titleHint) && title.Contains(titleHint, StringComparison.OrdinalIgnoreCase);
                bool isWrongPidInTitle = foundPidInTitle.HasValue && !isPidInTitle;
                if (isTreeMatch) {
                    int score = 1000; if (isVisible) score += 500; if (w > 0 && h > 0) score += 200; else score -= 2000;
                    if (isPidInTitle) score += 5000; if (isWrongPidInTitle) score -= 10000;
                    candidates.Add((hWnd, score, (long)w * h, title, procName));
                }
                else if (isVisible && w > 10 && h > 10) {
                    int score = 0; if (isPidInTitle) score += 5000;
                    if (isTitleMatch) { bool isTerminal = procName.Contains("Terminal", StringComparison.OrdinalIgnoreCase) || procName.Contains("cmd", StringComparison.OrdinalIgnoreCase) || procName.Contains("node", StringComparison.OrdinalIgnoreCase) || procName.Contains("PowerShell", StringComparison.OrdinalIgnoreCase); score += (isTerminal ? 300 : 50); score += CalculateTitleScore(title, titleHint!); if (isWrongPidInTitle) score -= 10000; }
                    if (score > 0) candidates.Add((hWnd, score, (long)w * h, title, procName));
                }
                return true;
            }, IntPtr.Zero);
            var validCandidates = candidates.Where(c => c.priority > 0).ToList();
            if (validCandidates.Count == 0) return new List<IntPtr>();
            var sorted = validCandidates.OrderByDescending(c => c.priority).ThenByDescending(c => c.area).ToList();
            return new List<IntPtr> { sorted.First().hWnd };
        }

        private int CalculateTitleScore(string title, string hint)
        {
            if (string.IsNullOrEmpty(hint)) return 0;
            string t = title.ToLower(); string h = hint.ToLower();
            int score = 0; if (t == h) score = 100; else if (t.Contains($"({h})")) score = 95; else if (t.StartsWith(h + " ")) score = 90; else if (System.Text.RegularExpressions.Regex.IsMatch(t, $@"\b{System.Text.RegularExpressions.Regex.Escape(h)}\b")) score = 85; else if (t.Contains(h)) score = 50;
            if (score > 0) score -= Math.Min(title.Length, 49);
            return score;
        }

        private List<IntPtr> GetWindowsByTitleOrProcessName(string target)
        {
            var results = new List<IntPtr>();
            var targetLower = target.ToLower();
            EnumWindows((hWnd, lParam) => {
                if (IsWindowVisible(hWnd)) {
                    var sb = new StringBuilder(256); GetWindowText(hWnd, sb, sb.Capacity); var title = sb.ToString().ToLower();
                    GetWindowThreadProcessId(hWnd, out uint processId); string procName = ""; try { procName = Process.GetProcessById((int)processId).ProcessName.ToLower(); } catch { }
                    if (title.Contains(targetLower) || procName.Contains(targetLower)) { GetWindowRect(hWnd, out RECT rect); if (rect.Right - rect.Left > 0 && rect.Bottom - rect.Top > 0) results.Add(hWnd); }
                }
                return true;
            }, IntPtr.Zero);
            return results;
        }

        private void MoveHwndsToOppositeMonitor(List<IntPtr> hwnds)
        {
            if (hwnds.Count == 0) return;
            var monitors = new List<IntPtr>();
            EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, (IntPtr hMonitor, IntPtr hdcMonitor, ref RECT lprcMonitor, IntPtr dwData) => { monitors.Add(hMonitor); return true; }, IntPtr.Zero);
            if (monitors.Count < 2) return;
            foreach (var hWnd in hwnds.Distinct()) {
                IntPtr hCurrentMonitor = MonitorFromWindow(hWnd, MONITOR_DEFAULTTONEAREST);
                int currentIndex = monitors.IndexOf(hCurrentMonitor);
                if (currentIndex == -1) continue;
                int targetIndex = (currentIndex + 1) % monitors.Count;
                MoveHwndProportional(hWnd, monitors[targetIndex], targetIndex);
            }
        }
    }

    public class ProcessInfo { public int Id { get; set; } public string Name { get; set; } public string MainWindowTitle { get; set; } public long MemoryUsage { get; set; } public double CpuUsage { get; set; } }
}
