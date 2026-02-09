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

        public ProcessService(HubSettingsService settingsService, Logic.Monitoring.HubMonitorService monitorService)
        {
            _settingsService = settingsService;
            _monitorService = monitorService;
        }

        public virtual async Task ExecuteCommand(string command)
        {
            _monitorService.AddLogMessage($"[ProcessService] ExecuteCommand: '{command}'");
            // Resolve mapping if available
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
                // Try to find the longest existing path at the start
                (executable, arguments) = FindLongestExistingPath(command);
                
                if (string.IsNullOrEmpty(executable))
                {
                    // Fallback to simple split
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
                // If it was an unquoted path with spaces that we found exists, quote it now
                finalCommand = string.IsNullOrEmpty(arguments) ? $"\"{executable}\"" : $"\"{executable}\" {arguments}";
            }

            await Task.Run(() =>
            {
                _monitorService.AddLogMessage($"[ProcessService] ExecuteCommand (detached): finalCommand='{finalCommand}'");
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
                        if (!string.IsNullOrEmpty(args.Data))
                        {
                            CommandOutputReceived?.Invoke(this, args.Data + Environment.NewLine);
                        }
                    };
                    process.ErrorDataReceived += (sender, args) =>
                    {
                        if (!string.IsNullOrEmpty(args.Data))
                        {
                            CommandOutputReceived?.Invoke(this, "[ERROR] " + args.Data + Environment.NewLine);
                        }
                    };

                    Action startAction = () => {
                        try { process.Start(); }
                        catch (Exception ex) { _monitorService.AddLogMessage($"[ERROR] Process Start failed: {ex.Message}"); }
                    };

                    if (System.Windows.Application.Current != null)
                    {
                        System.Windows.Application.Current.Dispatcher.Invoke(startAction);
                    }
                    else
                    {
                        startAction();
                    }

                    process.BeginOutputReadLine();
                    process.BeginErrorReadLine();
                    process.WaitForExit();
                }
            });
        }

        public virtual async Task ExecuteCommandWithLogging(string command, string logPrefix = "[Process]")
        {
            _monitorService.AddLogMessage($"{logPrefix} Execute: '{command}'");
            
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
                    process.OutputDataReceived += (sender, args) =>
                    {
                        if (!string.IsNullOrEmpty(args.Data))
                        {
                            _monitorService.AddLogMessage($"{logPrefix} {args.Data}");
                        }
                    };
                    process.ErrorDataReceived += (sender, args) =>
                    {
                        if (!string.IsNullOrEmpty(args.Data))
                        {
                            _monitorService.AddLogMessage($"{logPrefix} [ERROR] {args.Data}");
                        }
                    };

                    try
                    {
                        process.Start();
                        process.BeginOutputReadLine();
                        process.BeginErrorReadLine();
                        process.WaitForExit();
                    }
                    catch (Exception ex)
                    {
                        _monitorService.AddLogMessage($"{logPrefix} [EXCEPTION] {ex.Message}");
                    }
                }
            });
        }

        public virtual void ShellExecute(string path, string? arguments = null, string? workingDirectory = null)
        {
            _monitorService.AddLogMessage($"[ProcessService] ShellExecute: '{path}' Args: '{arguments}' Dir: '{workingDirectory}'");
            
            Action action = () => {
                try
                {
                    var psi = new ProcessStartInfo
                    {
                        FileName = path,
                        Arguments = arguments ?? "",
                        UseShellExecute = true,
                        WorkingDirectory = workingDirectory ?? ""
                    };
                    Process.Start(psi);
                    _monitorService.AddLogMessage($"[ProcessService] ShellExecute started process for '{path}'");
                }
                catch (Exception ex)
                {
                    _monitorService.AddLogMessage($"[ERROR] ShellExecute failed for {path}: {ex.Message}");
                }
            };

            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.Invoke(action);
            }
            else
            {
                action();
            }
        }

        public virtual void ExecuteCommandNonAdmin(string command, string? arguments = null, string? workingDirectory = null)
        {
            _monitorService.AddLogMessage($"[ProcessService] ExecuteCommandNonAdmin: '{command}' Args: '{arguments}' Dir: '{workingDirectory}'");
            
            Action action = () => {
                try
                {
                    // Use Shell.Application COM object to start a non-elevated process from an elevated one
                    Type shellType = Type.GetTypeFromProgID("Shell.Application");
                    if (shellType != null)
                    {
                        dynamic shell = Activator.CreateInstance(shellType);
                        dynamic windows = shell.Windows();
                        bool started = false;

                        // Try to find an existing explorer window to use as a launch proxy
                        for (int i = 0; i < windows.Count; i++)
                        {
                            try
                            {
                                dynamic window = windows.Item(i);
                                if (window != null && ((string)window.FullName).EndsWith("explorer.exe", StringComparison.OrdinalIgnoreCase))
                                {
                                    window.Document.Application.ShellExecute(command, arguments ?? "", workingDirectory ?? "", "open", 1);
                                    _monitorService.AddLogMessage($"[ProcessService] ExecuteCommandNonAdmin started via existing Explorer window: {command}");
                                    started = true;
                                    break;
                                }
                            }
                            catch { /* Skip windows that don't support the expected properties */ }
                        }

                        if (!started)
                        {
                            _monitorService.AddLogMessage("[ProcessService] No Explorer window found. Using explorer.exe as a launcher fallback.");
                            
                            // Create a temporary batch file to launch the command. 
                            // explorer.exe <path_to_file> always launches as a non-elevated process if explorer itself is non-elevated.
                            string tempBat = Path.Combine(Path.GetTempPath(), $"launch_non_admin_{Guid.NewGuid():N}.bat");
                            string cdCmd = !string.IsNullOrEmpty(workingDirectory) ? $"cd /d \"{workingDirectory}\"\r\n" : "";
                            File.WriteAllText(tempBat, $"@echo off\r\n{cdCmd}\"{command}\" {arguments}");
                            
                            Process.Start("explorer.exe", $"\"{tempBat}\"");
                            
                            // Delete the temp file after a delay to ensure explorer had time to read it
                            Task.Delay(5000).ContinueWith(_ => { try { File.Delete(tempBat); } catch {} });
                        }
                    }
                    else
                    {
                        _monitorService.AddLogMessage("[ERROR] Could not get Type for Shell.Application");
                    }
                }
                catch (Exception ex)
                {
                    _monitorService.AddLogMessage($"[ERROR] ExecuteCommandNonAdmin failed: {ex.Message}");
                }
            };

            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.Invoke(action);
            }
            else
            {
                action();
            }
        }

        private (string executable, string arguments) FindLongestExistingPath(string command)
        {
            if (string.IsNullOrWhiteSpace(command)) return ("", "");

            // If it's a simple command without spaces, it's not a path with spaces
            if (!command.Contains(" ")) return ("", "");

            // Iterate backwards through spaces to find the longest string that is a file
            int lastSpace = command.Length;
            while ((lastSpace = command.LastIndexOf(' ', lastSpace - 1)) != -1)
            {
                var potentialPath = command.Substring(0, lastSpace);
                if (File.Exists(potentialPath))
                {
                    return (potentialPath, command.Substring(lastSpace + 1).Trim());
                }
            }

            // Check the whole thing if it has spaces but no arguments
            if (File.Exists(command)) return (command, "");

            return ("", "");
        }

        public void WinActivate(string target)
        {
            Action action = () => {
                try
                {
                    // Intelligent activation: try exact title, partial title, then process name
                    string script = $@"
$target = '{target.Replace("'", "''")}'
$wshell = New-Object -ComObject WScript.Shell
if ($wshell.AppActivate($target)) {{ exit }}

$procs = Get-Process | Where-Object {{ $_.MainWindowTitle -like ""*$target*"" -or $_.ProcessName -eq $target }}
foreach ($p in $procs) {{
    if ($wshell.AppActivate($p.Id)) {{ exit }}
}}
";
                    RunPowerShellSynchronous(script);

                    // C# Fallback and Un-minimize check
                    var procs = Process.GetProcesses()
                        .Where(p => (p.MainWindowTitle.Contains(target, StringComparison.OrdinalIgnoreCase) || 
                                    p.ProcessName.Equals(target, StringComparison.OrdinalIgnoreCase)) && 
                                    p.MainWindowHandle != IntPtr.Zero)
                        .ToList();

                    foreach (var p in procs)
                    {
                        ActivateWindow(p.MainWindowHandle);
                    }
                }
                catch { }
            };

            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.Invoke(action);
            }
            else
            {
                action();
            }
        }

        public void WinActivatePid(int pid, string? titleHint = null)
        {
            Action action = () => {
                try
                {
                    _monitorService.AddLogMessage($"[ProcessService] WinActivatePid: {pid} (Hint: {titleHint ?? "None"})");
                    var treePids = GetProcessTreePids(pid);
                    _monitorService.AddLogMessage($"[ProcessService] Found {treePids.Count} PIDs in tree for root {pid}: {string.Join(", ", treePids)}");
                    var hwnds = GetVisibleWindowsForPids(treePids, titleHint);

                    if (hwnds.Any())
                    {
                        var targetHwnd = hwnds.First();
                        _monitorService.AddLogMessage($"[ProcessService] Activating HWND {targetHwnd} for PID {pid}");
                        ActivateWindow(targetHwnd);
                    }
                    else
                    {
                        _monitorService.AddLogMessage($"[ProcessService] No window found to activate for PID {pid}");
                    }
                }
                catch (Exception ex)
                {
                    _monitorService.AddLogMessage($"[ProcessService] Error in WinActivatePid: {ex.Message}");
                }
            };

            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.Invoke(action);
            }
            else
            {
                action();
            }
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
            
            _monitorService.AddLogMessage($"[ProcessService] Activating window {handle} aggressively...");

            if (IsIconic(handle)) ShowWindow(handle, SW_RESTORE);
            
            // Allow focus switching
            LockSetForegroundWindow(LSFW_UNLOCK);

            uint foregroundThreadId = GetWindowThreadProcessId(GetForegroundWindow(), out _);
            uint targetThreadId = GetWindowThreadProcessId(handle, out _);
            uint currentThreadId = GetCurrentThreadId();

            if (foregroundThreadId != targetThreadId)
            {
                AttachThreadInput(currentThreadId, foregroundThreadId, true);
                AttachThreadInput(currentThreadId, targetThreadId, true);
                
                SetForegroundWindow(handle);
                BringWindowToTop(handle);
                ShowWindow(handle, 5); // SW_SHOW
                
                AttachThreadInput(currentThreadId, targetThreadId, false);
                AttachThreadInput(currentThreadId, foregroundThreadId, false);
            }
            else
            {
                SetForegroundWindow(handle);
                BringWindowToTop(handle);
                ShowWindow(handle, 5);
            }
            
            // Nuclear fallback: SwitchToThisWindow
            SwitchToThisWindow(handle, true);

            // Fallback: tap the Alt key. 
            keybd_event(VK_MENU, 0, 0, 0);
            keybd_event(VK_MENU, 0, KEYEVENTF_KEYUP, 0);
            
            SetForegroundWindow(handle);
        }

        public void WinClose(string target)
        {
            Action action = () => {
                // Intelligent close: try exact title, partial title, then process name
                string script = $@"
$target = '{target.Replace("'", "''")}'
$procs = Get-Process | Where-Object {{ $_.MainWindowTitle -eq $target -or $_.MainWindowTitle -like ""*$target*"" -or $_.ProcessName -eq $target }}
foreach ($p in $procs) {{
    $p.CloseMainWindow()
    Sleep -Milliseconds 200
    if (!$p.HasExited) {{ $p.Kill() }}
}}
";
                RunPowerShell(script);
            };

            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.Invoke(action);
            }
            else
            {
                action();
            }
        }

        public void WinMinimize(string target)
        {
            Action action = () => {
                RunPowerShell($@"
$target = '{target.Replace("'", "''")}'
$procs = Get-Process | Where-Object {{ $_.MainWindowTitle -like ""*$target*"" -or $_.ProcessName -eq $target }}
$wshell = New-Object -ComObject WScript.Shell
foreach ($p in $procs) {{
    if ($wshell.AppActivate($p.Id)) {{
        $wshell.SendKeys('% n')
    }}
}}
");
            };

            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.Invoke(action);
            }
            else
            {
                action();
            }
        }

        public void WinMaximize(string target)
        {
            Action action = () => {
                RunPowerShell($@"
$target = '{target.Replace("'", "''")}'
$procs = Get-Process | Where-Object {{ $_.MainWindowTitle -like ""*$target*"" -or $_.ProcessName -eq $target }}
$wshell = New-Object -ComObject WScript.Shell
foreach ($p in $procs) {{
    if ($wshell.AppActivate($p.Id)) {{
        $wshell.SendKeys('% x')
    }}
}}
");
            };

            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.Invoke(action);
            }
            else
            {
                action();
            }
        }

        public void MoveWindow(IntPtr hWnd, int x, int y, int width, int height)
        {
            if (hWnd == IntPtr.Zero) return;
            if (IsIconic(hWnd)) ShowWindow(hWnd, SW_RESTORE);
            SetWindowPos(hWnd, IntPtr.Zero, x, y, width, height, SWP_NOZORDER | SWP_SHOWWINDOW);
        }

        public void WinHide(string target)
        {
            Action action = () => {
                RunPowerShell($@"
$code = @'
[DllImport(""user32.dll"")]
public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
'@
Add-Type -MemberDefinition $code -Name Win32 -Namespace Native
$target = '{target.Replace("'", "''")}'
$procs = Get-Process | Where-Object {{ $_.MainWindowTitle -like ""*$target*"" -or $_.ProcessName -eq $target }}
foreach ($p in $procs) {{
    [Native.Win32]::ShowWindow($p.MainWindowHandle, 0)
}}
");
            };

            if (System.Windows.Application.Current != null)
            {
                System.Windows.Application.Current.Dispatcher.Invoke(action);
            }
            else
            {
                action();
            }
        }

        public bool WaitWinActive(string target, int timeoutMs)
        {
            var sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < timeoutMs)
            {
                // We'll use a slightly different check here: is the foreground window matching our target?
                // For simplicity, let's use PowerShell to check and return
                string checkScript = $@"
$target = '{target.Replace("'", "''")}'
$active = (Get-Process | Where-Object {{ $_.MainWindowHandle -eq (Get-ForegroundWindow) }}).MainWindowTitle
if ($active -like ""*$target*"") {{ exit 0 }} else {{ exit 1 }}
";
                // This is a bit heavy. Let's try a simpler approach if possible.
                // But PowerShell is reliable for partial titles.
                if (IsWindowActive(target)) return true;
                Thread.Sleep(200);
            }
            return false;
        }

        private bool IsWindowActive(string target)
        {
            try {
                // Use a simple PS check
                var proc = new Process {
                    StartInfo = new ProcessStartInfo {
                        FileName = "powershell",
                        Arguments = $"-Command \"if ((Get-Process | Where-Object {{ $_.MainWindowTitle -like '*{target}*' -or $_.ProcessName -eq '{target}' }}).MainWindowHandle -contains (Get-ForegroundWindow)) {{ exit 0 }} else {{ exit 1 }}\"",
                        CreateNoWindow = true,
                        UseShellExecute = false
                    }
                };
                proc.Start();
                proc.WaitForExit();
                return proc.ExitCode == 0;
            } catch { return false; }
        }

        public void MouseMoveAbs(int x, int y)
        {
            RunPowerShell($"[Cursor]::Position = New-Object System.Drawing.Point({x}, {y})");
        }

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
[Native.Win32]::mouse_event({btnCode}, 0, 0, 0, 0)
";
            RunPowerShell(script);
        }

        public void RunPowerShell(string script)
        {
            // For asynchronous execution, we still use the simple approach but with a file if complex
            Task.Run(() => RunPowerShellSynchronous(script));
        }

        private string RunPowerShellSynchronous(string script)
        {
            string tempFile = Path.Combine(Path.GetTempPath(), $"omni_script_{Guid.NewGuid():N}.ps1");
            try
            {
                File.WriteAllText(tempFile, script, Encoding.UTF8);
                var psi = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = $"-NoProfile -ExecutionPolicy Bypass -File \"{tempFile}\"",
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                    UseShellExecute = false,
                    CreateNoWindow = true
                };

                using (var process = Process.Start(psi))
                {
                    if (process == null) return "";
                    string output = process.StandardOutput.ReadToEnd();
                    string error = process.StandardError.ReadToEnd();
                    process.WaitForExit();
                    
                    if (!string.IsNullOrEmpty(error))
                    {
                        Console.WriteLine($"[PS ERROR] {error}");
                        _monitorService.AddLogMessage($"[PS ERROR] {error}");
                    }
                    return output;
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[PS EXCEPTION] {ex.Message}");
                return "";
            }
            finally
            {
                if (File.Exists(tempFile)) try { File.Delete(tempFile); } catch { }
            }
        }

        public IEnumerable<ProcessInfo> ListProcesses()
        {
            return Process.GetProcesses().Select(p => new ProcessInfo
            {
                Id = p.Id,
                Name = p.ProcessName,
                MemoryUsage = p.WorkingSet64,
                CpuUsage = 0
            }).ToList();
        }

        public bool KillProcess(int processId)
        {
            try
            {
                var process = Process.GetProcessById(processId);
                process.Kill();
                return true;
            }
            catch (ArgumentException)
            {
                // Process with specified ID is not running.
                return false;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error killing process {processId}: {ex.Message}");
                return false;
            }
        }

        public bool IsProcessRunning(string processName)
        {
            try
            {
                var procs = Process.GetProcessesByName(processName);
                return procs != null && procs.Length > 0;
            }
            catch
            {
                return false;
            }
        }

        public void MoveWindowOpposite(int pid)
        {
            _monitorService.AddLogMessage($"[ProcessService] MoveWindowOpposite for PID: {pid}");
            var targetPids = GetProcessTreePids(pid);
            _monitorService.AddLogMessage($"[ProcessService] PIDs in tree: {string.Join(", ", targetPids)}");
            var hwnds = GetVisibleWindowsForPids(targetPids);
            _monitorService.AddLogMessage($"[ProcessService] Found {hwnds.Count} visible windows for tree.");
            MoveHwndsToOppositeMonitor(hwnds);
        }

        public void MoveWindowOpposite(string titleOrName)
        {
            _monitorService.AddLogMessage($"[ProcessService] MoveWindowOpposite for Title/Name: {titleOrName}");
            var hwnds = GetWindowsByTitleOrProcessName(titleOrName);
            _monitorService.AddLogMessage($"[ProcessService] Found {hwnds.Count} visible windows for '{titleOrName}'.");
            MoveHwndsToOppositeMonitor(hwnds);
        }

        public void MoveWindowToMonitor(int pid, int monitorIndex)
        {
            _monitorService.AddLogMessage($"[ProcessService] MoveWindowToMonitor for PID: {pid} to Mon {monitorIndex}");
            var targetPids = GetProcessTreePids(pid);
            var hwnds = GetVisibleWindowsForPids(targetPids);
            
            if (hwnds.Count == 0)
            {
                _monitorService.AddLogMessage("[ProcessService] MoveWindowToMonitor: No valid windows found.");
                return;
            }

            var monitors = new List<IntPtr>();
            EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, (IntPtr hMonitor, IntPtr hdcMonitor, ref RECT lprcMonitor, IntPtr dwData) =>
            {
                monitors.Add(hMonitor);
                return true;
            }, IntPtr.Zero);

            if (monitorIndex < 0 || monitorIndex >= monitors.Count)
            {
                _monitorService.AddLogMessage($"[ProcessService] Invalid monitor index {monitorIndex}. Total monitors: {monitors.Count}");
                return;
            }

            IntPtr hTargetMonitor = monitors[monitorIndex];
            MONITORINFO targetInfo = new MONITORINFO { cbSize = Marshal.SizeOf(typeof(MONITORINFO)) };
            GetMonitorInfo(hTargetMonitor, ref targetInfo);

            foreach (var hWnd in hwnds.Distinct())
            {
                IntPtr hCurrentMonitor = MonitorFromWindow(hWnd, MONITOR_DEFAULTTONEAREST);
                MONITORINFO currentInfo = new MONITORINFO { cbSize = Marshal.SizeOf(typeof(MONITORINFO)) };
                GetMonitorInfo(hCurrentMonitor, ref currentInfo);

                GetWindowRect(hWnd, out RECT rect);
                int w = rect.Right - rect.Left;
                int h = rect.Bottom - rect.Top;

                // Relative position
                int relX = rect.Left - currentInfo.rcMonitor.Left;
                int relY = rect.Top - currentInfo.rcMonitor.Top;

                int newX = targetInfo.rcMonitor.Left + relX;
                int newY = targetInfo.rcMonitor.Top + relY;

                _monitorService.AddLogMessage($"[ProcessService] Moving window to Mon {monitorIndex} ({newX}, {newY})");
                if (IsIconic(hWnd)) ShowWindow(hWnd, SW_RESTORE);
                SetWindowPos(hWnd, IntPtr.Zero, newX, newY, w, h, SWP_NOZORDER | SWP_SHOWWINDOW);
                SetForegroundWindow(hWnd);
            }
        }

        private HashSet<int> GetProcessTreePids(int rootPid)
        {
            var result = new HashSet<int> { rootPid };
            try
            {
                // 1. Get all processes to build a mapping
                var allProcs = Process.GetProcesses();
                var parentMap = new Dictionary<int, int>();
                var nameMap = new Dictionary<int, string>();
                var cmdMap = new Dictionary<int, string>();
                
                using (var searcher = new System.Management.ManagementObjectSearcher("SELECT ProcessId, ParentProcessId, Name, CommandLine FROM Win32_Process"))
                {
                    foreach (var obj in searcher.Get())
                    {
                        int pId = Convert.ToInt32(obj["ProcessId"]);
                        int parentId = Convert.ToInt32(obj["ParentProcessId"]);
                        string name = obj["Name"]?.ToString() ?? "";
                        string cmd = obj["CommandLine"]?.ToString() ?? "";
                        parentMap[pId] = parentId;
                        nameMap[pId] = name;
                        cmdMap[pId] = cmd;
                    }
                }

                // 2. Add children (recursive)
                void AddChildren(int pId)
                {
                    foreach (var kvp in parentMap)
                    {
                        if (kvp.Value == pId && !result.Contains(kvp.Key))
                        {
                            result.Add(kvp.Key);
                            _monitorService.AddLogMessage($"[ProcessService] Tree: Found child PID {kvp.Key} for parent {pId}");
                            AddChildren(kvp.Key);
                        }
                    }
                }
                AddChildren(rootPid);

                // 3. Add parents up to explorer and their critical children (like conhost)
                int currentPid = rootPid;
                while (parentMap.TryGetValue(currentPid, out int parentId) && parentId != 0)
                {
                    if (nameMap.TryGetValue(parentId, out string parentName) && parentName.ToLower().Contains("explorer")) 
                        break;
                    
                    result.Add(parentId);
                    
                    // Add siblings that are console hosts
                    foreach (var kvp in parentMap)
                    {
                        if (kvp.Value == parentId && !result.Contains(kvp.Key))
                        {
                            if (nameMap.TryGetValue(kvp.Key, out string siblingName) && 
                                (siblingName.ToLower().Contains("conhost") || siblingName.ToLower().Contains("openconsole")))
                            {
                                result.Add(kvp.Key);
                                _monitorService.AddLogMessage($"[ProcessService] Tree: Added sibling console host PID {kvp.Key} ({siblingName})");
                            }
                        }
                    }
                    currentPid = parentId;
                }

                // 4. Special Terminal Search: If we found OpenConsole or Conhost but didn't find its hosting Terminal
                // because Terminal isn't a direct parent, we look for ALL WindowsTerminal processes 
                // and see if any of them are 'responsible' for this tree.
                var consoles = result.Where(p => nameMap.ContainsKey(p) && 
                                                (nameMap[p].ToLower().Contains("openconsole") || 
                                                 nameMap[p].ToLower().Contains("conhost"))).ToList();
                if (consoles.Any())
                {
                    foreach (var termPid in nameMap.Where(kvp => kvp.Value.ToLower().Contains("windowsterminal") || 
                                                               kvp.Value.ToLower().Contains("conhost")).Select(kvp => kvp.Key))
                    {
                        // In modern Windows Terminal, OpenConsole.exe is launched by svchost but linked via some magic.
                        // However, WindowsTerminal.exe is the one with the window.
                        // Since we can't perfectly link them via PID, we add ALL Terminal PIDs to the candidate list
                        // if we detect we are in an OpenConsole environment.
                        // The GetVisibleWindowsForPids will then find the one with the window.
                        result.Add(termPid);
                        _monitorService.AddLogMessage($"[ProcessService] Tree: Added potential Terminal PID {termPid} due to console host presence.");
                    }
                }
            }
            catch (Exception ex)
            {
                _monitorService.AddLogMessage($"[ProcessService] Error in GetProcessTreePids: {ex.Message}");
            }
            return result;
        }

        private List<IntPtr> GetVisibleWindowsForPids(HashSet<int> pids, string? titleHint = null)
        {
            var candidates = new List<(IntPtr hWnd, int priority, long area)>();
            
            EnumWindows((hWnd, lParam) =>
            {
                if (IsWindowVisible(hWnd))
                {
                    GetWindowThreadProcessId(hWnd, out uint processId);
                    
                    var sb = new StringBuilder(256);
                    GetWindowText(hWnd, sb, sb.Capacity);
                    string title = sb.ToString();

                    GetWindowRect(hWnd, out RECT rect);
                    int w = rect.Right - rect.Left;
                    int h = rect.Bottom - rect.Top;

                    if (w > 10 && h > 10)
                    {
                        bool isTreeMatch = pids.Contains((int)processId);
                        bool isTitleMatch = !string.IsNullOrEmpty(titleHint) && title.Contains(titleHint, StringComparison.OrdinalIgnoreCase);

                        if (isTreeMatch && isTitleMatch)
                        {
                            _monitorService.AddLogMessage($"[ProcessService] Windows: Found EXACT window HWND {hWnd} (Tree + Title Hint match '{titleHint}'). Title: '{title}' ({w}x{h})");
                            candidates.Add((hWnd, 20, (long)w * h));
                        }
                        else if (isTreeMatch)
                        {
                            _monitorService.AddLogMessage($"[ProcessService] Windows: Found visible window HWND {hWnd} for matching PID {processId}. Title: '{title}' ({w}x{h})");
                            candidates.Add((hWnd, 10, (long)w * h));
                        }
                        else if (isTitleMatch)
                        {
                            // Title matches but not in tree. Check if it's a terminal to avoid generic explorer stealing focus.
                            string procName = "";
                            try { procName = Process.GetProcessById((int)processId).ProcessName; } catch { }
                            bool isTerminal = procName.Contains("Terminal", StringComparison.OrdinalIgnoreCase) || 
                                              procName.Contains("cmd", StringComparison.OrdinalIgnoreCase) || 
                                              procName.Contains("powershell", StringComparison.OrdinalIgnoreCase);
                            
                            if (isTerminal)
                            {
                                _monitorService.AddLogMessage($"[ProcessService] Windows: Found TERMINAL window HWND {hWnd} via Title Hint match '{titleHint}' (not in tree). Title: '{title}' ({w}x{h})");
                                candidates.Add((hWnd, 5, (long)w * h));
                            }
                        }
                        else 
                        {
                            bool isFallbackMatch = (title == "[system32]" || title.Contains("node.exe") || title.Contains("cmd.exe") || title.Contains("WindowsTerminal"));
                            if (isFallbackMatch)
                            {
                                // Lower priority fallback
                                candidates.Add((hWnd, 0, (long)w * h));
                            }
                        }
                    }
                }
                return true;
            }, IntPtr.Zero);

            if (candidates.Count == 0) return new List<IntPtr>();

            // Log all candidates for debugging
            foreach (var c in candidates.OrderByDescending(x => x.priority).ThenByDescending(x => x.area))
            {
                var sb = new StringBuilder(256);
                GetWindowText(c.hWnd, sb, sb.Capacity);
                _monitorService.AddLogMessage($"[ProcessService] Candidate: HWND {c.hWnd}, Priority {c.priority}, Area {c.area}, Title '{sb}'");
            }

            // Pick the single best window: highest priority, then largest area
            var best = candidates
                .OrderByDescending(c => c.priority)
                .ThenByDescending(c => c.area)
                .First();

            return new List<IntPtr> { best.hWnd };
        }

        private List<IntPtr> GetWindowsByTitleOrProcessName(string target)
        {
            var results = new List<IntPtr>();
            var targetLower = target.ToLower();
            EnumWindows((hWnd, lParam) =>
            {
                if (IsWindowVisible(hWnd))
                {
                    var sb = new StringBuilder(256);
                    GetWindowText(hWnd, sb, sb.Capacity);
                    var title = sb.ToString().ToLower();

                    GetWindowThreadProcessId(hWnd, out uint processId);
                    string procName = "";
                    try { procName = Process.GetProcessById((int)processId).ProcessName.ToLower(); } catch { }

                    if (title.Contains(targetLower) || procName.Contains(targetLower))
                    {
                        GetWindowRect(hWnd, out RECT rect);
                        if (rect.Right - rect.Left > 0 && rect.Bottom - rect.Top > 0)
                        {
                            results.Add(hWnd);
                        }
                    }
                }
                return true;
            }, IntPtr.Zero);
            return results;
        }

        private void MoveHwndsToOppositeMonitor(List<IntPtr> hwnds)
        {
            if (hwnds.Count == 0)
            {
                _monitorService.AddLogMessage("[ProcessService] MoveHwndsToOppositeMonitor: No valid windows found.");
                return;
            }

            var monitors = new List<IntPtr>();
            EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero, (IntPtr hMonitor, IntPtr hdcMonitor, ref RECT lprcMonitor, IntPtr dwData) =>
            {
                monitors.Add(hMonitor);
                return true;
            }, IntPtr.Zero);

            if (monitors.Count < 2) 
            {
                _monitorService.AddLogMessage("[ProcessService] Only one monitor detected. Cannot toggle.");
                return;
            }

            foreach (var hWnd in hwnds.Distinct())
            {
                IntPtr hCurrentMonitor = MonitorFromWindow(hWnd, MONITOR_DEFAULTTONEAREST);
                int currentIndex = -1;
                for (int i = 0; i < monitors.Count; i++)
                {
                    if (monitors[i] == hCurrentMonitor)
                    {
                        currentIndex = i;
                        break;
                    }
                }

                if (currentIndex == -1) continue;

                int targetIndex = (currentIndex + 1) % monitors.Count;
                IntPtr hTargetMonitor = monitors[targetIndex];

                MONITORINFO currentInfo = new MONITORINFO { cbSize = Marshal.SizeOf(typeof(MONITORINFO)) };
                GetMonitorInfo(hCurrentMonitor, ref currentInfo);

                MONITORINFO targetInfo = new MONITORINFO { cbSize = Marshal.SizeOf(typeof(MONITORINFO)) };
                GetMonitorInfo(hTargetMonitor, ref targetInfo);

                GetWindowRect(hWnd, out RECT rect);
                int w = rect.Right - rect.Left;
                int h = rect.Bottom - rect.Top;

                // Relative position to current monitor's top-left
                int relX = rect.Left - currentInfo.rcMonitor.Left;
                int relY = rect.Top - currentInfo.rcMonitor.Top;

                // New absolute position on target monitor
                int newX = targetInfo.rcMonitor.Left + relX;
                int newY = targetInfo.rcMonitor.Top + relY;

                _monitorService.AddLogMessage($"[ProcessService] Toggling window to Mon {targetIndex} ({newX}, {newY})");
                
                if (IsIconic(hWnd)) ShowWindow(hWnd, SW_RESTORE);
                
                // SetWindowPos with SWP_NOSENDCHANGING to be more robust
                SetWindowPos(hWnd, IntPtr.Zero, newX, newY, w, h, SWP_NOZORDER | SWP_SHOWWINDOW);
                SetForegroundWindow(hWnd);
            }
        }
    }

    public class ProcessInfo
    {
        public int Id { get; set; }
        public string Name { get; set; }
        public string MainWindowTitle { get; set; }
        public long MemoryUsage { get; set; }
        public double CpuUsage { get; set; }
    }
}
