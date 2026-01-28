using System;
using Microsoft.Win32;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Infrastructure.Services
{
    public class RegistryService
    {
        private const string AppName = "OmniSync Hub";
        private const string RunRegistryPath = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
        private readonly ILogger<RegistryService> _logger;

        public RegistryService(ILogger<RegistryService> logger)
        {
            _logger = logger;
        }

        public bool IsRunOnStartupEnabled()
        {
            try
            {
                var startInfo = new System.Diagnostics.ProcessStartInfo
                {
                    FileName = "schtasks.exe",
                    Arguments = $"/Query /TN \"{AppName}\"",
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true
                };

                using (var process = System.Diagnostics.Process.Start(startInfo))
                {
                    process?.WaitForExit();
                    return process?.ExitCode == 0;
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error checking if run on startup is enabled via schtasks.");
                return false;
            }
        }

        public void SetRunOnStartup(bool enable)
        {
            try
            {
                if (enable)
                {
                    string? executablePath = GetExecutablePath();
                    if (executablePath != null)
                    {
                        // Delete existing task if it exists to ensure fresh settings
                        RunSchTasks($"/Delete /TN \"{AppName}\" /F");

                        // Create new task:
                        // /Create - create a new task
                        // /TN - Task Name
                        // /TR - Task Run (path to exe)
                        // /SC ONLOGON - Schedule on logon
                        // /RL HIGHEST - Run with highest privileges (Admin)
                        // /F - Force creation (overwrite)
                        string args = $"/Create /TN \"{AppName}\" /TR \"\\\"{executablePath}\"\" /SC ONLOGON /RL HIGHEST /F";
                        int exitCode = RunSchTasks(args);
                        
                        if (exitCode == 0)
                            _logger.LogInformation($"Enabled '{AppName}' to run on startup via Scheduled Task.");
                        else
                            _logger.LogError($"Failed to enable '{AppName}' on startup. Exit code: {exitCode}");
                    }
                }
                else
                {
                    int exitCode = RunSchTasks($"/Delete /TN \"{AppName}\" /F");
                    if (exitCode == 0)
                        _logger.LogInformation($"Disabled '{AppName}' from running on startup.");
                }

                // Also clean up old registry key if it exists
                try
                {
                    using (RegistryKey? rk = Registry.CurrentUser.OpenSubKey(RunRegistryPath, true))
                    {
                        if (rk?.GetValue(AppName) != null)
                        {
                            rk.DeleteValue(AppName, false);
                            _logger.LogInformation("Cleaned up old registry startup key.");
                        }
                    }
                }
                catch { /* Ignore registry cleanup errors */ }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error setting run on startup to {enable} via schtasks.");
            }
        }

        private int RunSchTasks(string arguments)
        {
            var startInfo = new System.Diagnostics.ProcessStartInfo
            {
                FileName = "schtasks.exe",
                Arguments = arguments,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };

            using (var process = System.Diagnostics.Process.Start(startInfo))
            {
                process?.WaitForExit();
                return process?.ExitCode ?? -1;
            }
        }

        public void RegisterContextMenu()
        {
            try
            {
                string? exePath = GetExecutablePath();
                if (exePath == null) return;

                string iconPath = System.IO.Path.Combine(AppContext.BaseDirectory, "OmniIcon.ico");
                
                // Register for files (*)
                RegisterForFiles(exePath, iconPath);
                
                // Register for folders (Directory)
                RegisterForFolders(exePath, iconPath);
                
                _logger.LogInformation("Omni context menu registered successfully in HKCU.");
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error registering context menu.");
            }
        }

        private void RegisterForFiles(string exePath, string iconPath)
        {
            string baseKeyPath = @"Software\Classes\*\shell\Omni";
            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(baseKeyPath))
            {
                key.SetValue("MUIVerb", "Omni");
                key.SetValue("SubCommands", "");
                if (System.IO.File.Exists(iconPath))
                {
                    key.SetValue("Icon", iconPath);
                }

                using (RegistryKey shellKey = key.CreateSubKey("shell"))
                {
                    // Open on Android
                    using (RegistryKey openAndroidKey = shellKey.CreateSubKey("OpenOnAndroid"))
                    {
                        openAndroidKey.SetValue("MUIVerb", "Open on Android");
                        using (RegistryKey commandKey = openAndroidKey.CreateSubKey("command"))
                        {
                            commandKey.SetValue("", $"\"{exePath}\" --open-on-android \"%1\"");
                        }
                    }
                }
            }
        }

        private void RegisterForFolders(string exePath, string iconPath)
        {
            string baseKeyPath = @"Software\Classes\Directory\shell\Omni";
            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(baseKeyPath))
            {
                key.SetValue("MUIVerb", "Omni");
                key.SetValue("SubCommands", "");
                if (System.IO.File.Exists(iconPath))
                {
                    key.SetValue("Icon", iconPath);
                }

                using (RegistryKey shellKey = key.CreateSubKey("shell"))
                {
                    // Open on Android
                    using (RegistryKey openAndroidKey = shellKey.CreateSubKey("OpenOnAndroid"))
                    {
                        openAndroidKey.SetValue("MUIVerb", "Open on Android");
                        using (RegistryKey commandKey = openAndroidKey.CreateSubKey("command"))
                        {
                            commandKey.SetValue("", $"\"{exePath}\" --open-on-android \"%1\"");
                        }
                    }

                    // CLI Here
                    using (RegistryKey cliHereKey = shellKey.CreateSubKey("CliHere"))
                    {
                        cliHereKey.SetValue("MUIVerb", "CLI Here");
                        using (RegistryKey commandKey = cliHereKey.CreateSubKey("command"))
                        {
                            commandKey.SetValue("", $"\"{exePath}\" --cli-here \"%1\"");
                        }
                    }
                }
            }
        }

        public void UnregisterContextMenu()
        {
            try
            {
                Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\*\shell\Omni", false);
                Registry.CurrentUser.DeleteSubKeyTree(@"Software\Classes\Directory\shell\Omni", false);
                _logger.LogInformation("Omni context menu unregistered successfully from HKCU.");
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error unregistering context menu.");
            }
        }

        private string? GetExecutablePath()
        {
            string? executablePath = Environment.ProcessPath;
            if (executablePath != null && executablePath.EndsWith(".dll", StringComparison.OrdinalIgnoreCase))
            {
                executablePath = System.IO.Path.ChangeExtension(executablePath, ".exe");
            }
            return executablePath;
        }
    }
}
