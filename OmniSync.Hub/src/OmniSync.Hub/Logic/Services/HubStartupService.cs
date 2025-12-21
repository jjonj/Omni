using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Configuration;
using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace OmniSync.Hub.Logic.Services
{
    public class HubStartupService : IHostedService
    {
        private readonly ILogger<HubStartupService> _logger;
        private readonly IConfiguration _configuration;

        public HubStartupService(ILogger<HubStartupService> logger, IConfiguration configuration)
        {
            _logger = logger;
            _configuration = configuration;
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            bool autoStart = _configuration.GetValue<bool>("AiSettings:AutoStartComponents", true);
            
            if (autoStart)
            {
                _logger.LogInformation("HubStartupService: Starting AI components...");
                // Gemini CLI now auto-launches on demand via ai_listener.py
                LaunchComponent("launch_ai_listener.py");
            }
            else
            {
                _logger.LogInformation("HubStartupService: AI auto-start is disabled in configuration.");
            }

            return Task.CompletedTask;
        }

        private void LaunchComponent(string scriptName)
        {
            try
            {
                // Navigate up from bin/Debug/net9.0-windows to the project root
                // 1:net9.0-windows, 2:Debug, 3:bin, 4:OmniSync.Hub, 5:src, 6:Root
                string rootPath = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", ".."));
                string scriptPath = Path.Combine(rootPath, scriptName);

                if (!File.Exists(scriptPath))
                {
                    _logger.LogWarning($"HubStartupService: Script not found at {scriptPath}");
                    return;
                }

                _logger.LogInformation($"HubStartupService: Launching {scriptName}...");
                
                var startInfo = new ProcessStartInfo
                {
                    FileName = "python",
                    Arguments = scriptPath,
                    WorkingDirectory = rootPath,
                    UseShellExecute = true, // Required for opening a new window
                    CreateNoWindow = false
                };

                Process.Start(startInfo);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, $"HubStartupService: Failed to launch {scriptName}");
            }
        }

        public Task StopAsync(CancellationToken cancellationToken)
        {
            return Task.CompletedTask;
        }
    }
}
