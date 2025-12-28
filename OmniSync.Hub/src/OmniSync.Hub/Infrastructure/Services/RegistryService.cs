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
                using (RegistryKey? rk = Registry.CurrentUser.OpenSubKey(RunRegistryPath, false))
                {
                    return rk?.GetValue(AppName) != null;
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error checking if run on startup is enabled.");
                return false;
            }
        }

        public void SetRunOnStartup(bool enable)
        {
            try
            {
                using (RegistryKey? rk = Registry.CurrentUser.OpenSubKey(RunRegistryPath, true))
                {
                    if (rk == null) return;

                    if (enable)
                    {
                        string? executablePath = GetExecutablePath();
                        if (executablePath != null)
                        {
                            rk.SetValue(AppName, executablePath);
                            _logger.LogInformation($"Enabled '{AppName}' to run on startup.");
                        }
                    }
                    else
                    {
                        rk.DeleteValue(AppName, false);
                        _logger.LogInformation($"Disabled '{AppName}' from running on startup.");
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"Error setting run on startup to {enable}.");
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
