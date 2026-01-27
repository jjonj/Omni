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

        public virtual void ShellExecute(string path, string? arguments = null)
        {
            _monitorService.AddLogMessage($"[ProcessService] ShellExecute: '{path}' Args: '{arguments}'");
            
            Action action = () => {
                try
                {
                    var psi = new ProcessStartInfo
                    {
                        FileName = path,
                        Arguments = arguments ?? "",
                        UseShellExecute = true
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

        public void WinActivatePid(int pid)
        {
            Action action = () => {
                try
                {
                    // Use synchronous call to ensure activation happens before we return
                    string script = $@"
$targetPid = {pid}
$wshell = New-Object -ComObject WScript.Shell

function Try-Activate($targetId) {{
    $p = Get-Process -Id $targetId -ErrorAction SilentlyContinue
    if ($null -eq $p) {{ return $false }}
    if ($wshell.AppActivate($p.Id)) {{ return $true }}
    return $false
}}

if (Try-Activate($targetPid)) {{ exit }}

$currPid = $targetPid
for ($i=0; $i -lt 5; $i++) {{
    $procInfo = Get-CimInstance Win32_Process -Filter ""ProcessId = $currPid""
    $parent = $procInfo.ParentProcessId
    if (!$parent) {{ break }}
    if (Try-Activate($parent)) {{ exit }}
    $currPid = $parent
}}
";
                    RunPowerShellSynchronous(script);

                    // C# Fallback attempt using handle if available
                    var proc = Process.GetProcessById(pid);
                    if (proc.MainWindowHandle != IntPtr.Zero)
                    {
                        ActivateWindow(proc.MainWindowHandle);
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

        private void ActivateWindow(IntPtr handle)
        {
            if (handle == IntPtr.Zero) return;
            if (IsIconic(handle)) ShowWindow(handle, SW_RESTORE);
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

        public async Task ExecuteMacro(JsonElement commands, InputService inputService, ClipboardService clipboardService)
        {
            foreach (var cmd in commands.EnumerateArray())
            {
                var type = cmd.GetProperty("type").GetString();
                switch (type?.ToLower())
                {
                    case "send":
                        var keys = ExpandVariables(cmd.GetProperty("keys").GetString() ?? "", inputService);
                        inputService.SendKeys(keys);
                        break;
                    case "sleep":
                        var ms = cmd.GetProperty("durationMs").GetInt64();
                        if (ms > 0) await Task.Delay((int)ms);
                        break;
                    case "run":
                        var path = ExpandVariables(cmd.GetProperty("path").GetString() ?? "", inputService);
                        await ExecuteCommand(path);
                        break;
                    case "winactivate":
                        WinActivate(cmd.GetProperty("title").GetString() ?? "");
                        break;
                    case "winclose":
                        WinClose(cmd.GetProperty("title").GetString() ?? "");
                        break;
                    case "winminimize":
                        WinMinimize(cmd.GetProperty("title").GetString() ?? "");
                        break;
                    case "winmaximize":
                        WinMaximize(cmd.GetProperty("title").GetString() ?? "");
                        break;
                    case "winhide":
                        WinHide(cmd.GetProperty("title").GetString() ?? "");
                        break;
                    case "waitwinactive":
                        var target = cmd.GetProperty("title").GetString() ?? "";
                        var timeout = cmd.GetProperty("timeoutMs").GetInt32();
                        WaitWinActive(target, timeout);
                        break;
                    case "volup":
                        inputService.SendKeyPress(0xAF); // VK_VOLUME_UP
                        break;
                    case "voldown":
                        inputService.SendKeyPress(0xAE); // VK_VOLUME_DOWN
                        break;
                    case "volmute":
                        inputService.SendKeyPress(0xAD); // VK_VOLUME_MUTE
                        break;
                    case "screenshot":
                        // This needs to be handled by the HubEventSender or similar
                        // For now just invoke the command via dispatcher if possible or skip
                        break;
                    case "keydown":
                        var kdKey = cmd.GetProperty("key").GetString() ?? "";
                        // Map key string to code... inputService usually handles this via RpcApiHub
                        // We might need a helper here
                        break;
                    case "keyup":
                        var kuKey = cmd.GetProperty("key").GetString() ?? "";
                        break;
                    case "clipboard":
                        var text = ExpandVariables(cmd.GetProperty("text").GetString() ?? "", inputService);
                        clipboardService.SetClipboardText(text);
                        break;
                    case "powershell":
                        var code = ExpandVariables(cmd.GetProperty("code").GetString() ?? "", inputService);
                        RunPowerShell(code);
                        break;
                    case "mousemoveabs":
                        MouseMoveAbs(cmd.GetProperty("x").GetInt32(), cmd.GetProperty("y").GetInt32());
                        break;
                    case "mouseclickat":
                        MouseClickAt(cmd.GetProperty("button").GetString() ?? "left", cmd.GetProperty("x").GetInt32(), cmd.GetProperty("y").GetInt32());
                        break;
                }
            }
        }

        private string ExpandVariables(string text, InputService inputService)
        {
            if (string.IsNullOrEmpty(text)) return text;
            
            return text
                .Replace("{DATE}", DateTime.Now.ToString("yyyy-MM-dd"))
                .Replace("{TIME}", DateTime.Now.ToString("HH:mm:ss"))
                .Replace("{PC_UPTIME}", (DateTime.Now - Process.GetCurrentProcess().StartTime).ToString(@"dd\.hh\:mm\:ss"))
                .Replace("{ACTIVE_WINDOW_TITLE}", inputService.GetActiveWindowTitle());
        }

        private void RunPowerShell(string script)
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
