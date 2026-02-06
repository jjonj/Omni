using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic
{
    public class ProjectLauncherService
    {
        private readonly ProcessService _processService;
        private readonly HubSettingsService _settingsService;

        public ProjectLauncherService(ProcessService processService, HubSettingsService settingsService)
        {
            _processService = processService;
            _settingsService = settingsService;
        }

        public virtual async Task LaunchProject(Project project)
        {
            foreach (var action in project.Actions)
            {
                try
                {
                    if (action.Type == ProjectActionType.OpenFolder)
                    {
                        await OpenFolderAction(action);
                    }
                    else if (action.Type == ProjectActionType.RunProgram)
                    {
                        await RunProgramAction(action);
                    }
                }
                catch (Exception ex)
                {
                    // Log error and continue with next action
                    Debug.WriteLine($"Error executing project action: {ex.Message}");
                }
            }
        }

        private async Task OpenFolderAction(ProjectAction action)
        {
            string path = action.Path;
            if (!Directory.Exists(path)) return;

            if (_settingsService.Settings.UseOneCommander)
            {
                string ocPath = GetOneCommanderPath();
                if (!string.IsNullOrEmpty(ocPath) && File.Exists(ocPath))
                {
                    // Ignore layout for OneCommander as requested
                    await OpenOneCommanderFolder(ocPath, path, null);
                }
                else
                {
                    await OpenExplorerFolder(path, action.Layout);
                }
            }
            else
            {
                await OpenExplorerFolder(path, action.Layout);
            }
        }

        private async Task OpenOneCommanderFolder(string ocPath, string path, WindowLayout? layout)
        {
            // Try to activate existing OC and close tab if it might be a duplicate
            // Since we can't know which tab is open, we can try to send a 'Close Tab' command 
            // if OC is already running, though this is risky.
            // Better: OneCommander often reuses tabs if the path matches and -newtab is NOT used.
            // But user specifically wants to CLOSE them.
            
            int existingPid;
            if (WindowDetector.IsProcessRunning(ocPath, out existingPid))
            {
                // If it's already running, we can try to bring it to front and send Ctrl+W
                // to close the current tab before opening the new one.
                _processService.WinActivatePid(existingPid);
                await Task.Delay(200);
                System.Windows.Forms.SendKeys.SendWait("^w"); 
                await Task.Delay(200);
            }

            var psi = new ProcessStartInfo
            {
                FileName = ocPath,
                Arguments = $"-o \"{path}\" -newtab",
                CreateNoWindow = true,
                UseShellExecute = false
            };
            var proc = Process.Start(psi);
            
            if (layout != null && proc != null)
            {
                // Give it some time to open/focus
                await Task.Delay(1000);
                var bounds = LayoutHelper.CalculateBounds(layout);
                // For OneCommander we might need to find the main window handle
                // Since the process we start might just send a message and exit
                int pid;
                if (WindowDetector.IsProcessRunning(ocPath, out pid))
                {
                    var handle = Process.GetProcessById(pid).MainWindowHandle;
                    _processService.MoveWindow(handle, bounds.X, bounds.Y, bounds.Width, bounds.Height);
                }
            }
        }

        private async Task OpenExplorerFolder(string path, WindowLayout? layout)
        {
            IntPtr existingHwnd = WindowDetector.GetExplorerWindowForPath(path);
            
            if (existingHwnd != IntPtr.Zero)
            {
                // Already open, just activate and position
                _processService.WinActivatePid(GetWindowThreadProcessId(existingHwnd, out uint pid) != 0 ? (int)pid : 0);
                if (layout != null)
                {
                    var bounds = LayoutHelper.CalculateBounds(layout);
                    _processService.MoveWindow(existingHwnd, bounds.X, bounds.Y, bounds.Width, bounds.Height);
                }
            }
            else
            {
                // Not open at this path, check for ANY explorer window to use tabs
                IntPtr anyExplorer = WindowDetector.FindWindowByTitle(""); // Needs better check for "CabinetWClass"
                
                // For now, let's just use the prototype logic via PowerShell/SendKeys
                // This is a bit "hacky" but was requested as a prototype.
                string script = $"\n$path = '{path.Replace("'", "''")}'\n$wshell = New-Object -ComObject WScript.Shell\n$explorer = Get-Process -Name explorer | Where-Object {{ $_.MainWindowTitle -ne '' }} | Select-Object -First 1\n\nif ($explorer) {{\n    $wshell.AppActivate($explorer.Id)\n    Sleep -Milliseconds 500\n    $wshell.SendKeys('^t')\n    Sleep -Milliseconds 500\n    $wshell.SendKeys('%d')\n    Sleep -Milliseconds 200\n    $wshell.SendKeys($path)\n    $wshell.SendKeys('{{ENTER}}')\n}} else {{\n    Start-Process explorer.exe $path\n}}\n";
                // Execute via ProcessService or RunPowerShell directly
                // This is a simplification for the prototype
                Process.Start("powershell.exe", $"-NoProfile -ExecutionPolicy Bypass -Command \"{script}\"");

                if (layout != null)
                {
                    await Task.Delay(2000); // Wait for window
                    IntPtr newHwnd = WindowDetector.GetExplorerWindowForPath(path);
                    if (newHwnd != IntPtr.Zero)
                    {
                        var bounds = LayoutHelper.CalculateBounds(layout);
                        _processService.MoveWindow(newHwnd, bounds.X, bounds.Y, bounds.Width, bounds.Height);
                    }
                }
            }
        }

        private async Task RunProgramAction(ProjectAction action)
        {
            string path = action.Path;
            if (!File.Exists(path))
            {
                // Try resolving via Exe Mappings
                var mappedPath = _settingsService.GetPath(path);
                if (!string.IsNullOrEmpty(mappedPath) && File.Exists(mappedPath))
                {
                    path = mappedPath;
                }
            }

            var psi = new ProcessStartInfo
            {
                FileName = path,
                Arguments = action.Arguments,
                UseShellExecute = true
            };
            
            var proc = Process.Start(psi);
            if (action.Layout != null && proc != null)
            {
                await Task.Delay(1000); // Wait for window
                if (proc.MainWindowHandle != IntPtr.Zero)
                {
                    var bounds = LayoutHelper.CalculateBounds(action.Layout);
                    _processService.MoveWindow(proc.MainWindowHandle, bounds.X, bounds.Y, bounds.Width, bounds.Height);
                }
            }
        }

        private string GetOneCommanderPath()
        {
            return _settingsService.GetPath("OneCommander") ?? _settingsService.GetPath("oc") ?? @"E:\Program Files\OneCommander\OneCommander.exe";
        }

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
    }
}
