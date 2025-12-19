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
                        string? executablePath = Environment.ProcessPath;
                        if (executablePath != null)
                        {
                            if (executablePath.EndsWith(".dll", StringComparison.OrdinalIgnoreCase))
                            {
                                executablePath = System.IO.Path.ChangeExtension(executablePath, ".exe");
                            }
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
                _logger.LogError(ex, $"Error setting run on startup to {{enable}}.");
            }
        }
    }
}
