using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Configuration;
using Microsoft.AspNetCore.SignalR;
using OmniSync.Hub.Presentation.Hubs;
using OmniSync.Hub.Infrastructure.Services;
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
        private readonly IHostApplicationLifetime _appLifetime;
        private readonly HubSettingsService _settingsService;
        private readonly QuickActionService _quickActionService;
        private readonly AiCliService _aiCliService;
        private readonly IHubContext<RpcApiHub> _hubContext;

        public HubStartupService(
            ILogger<HubStartupService> logger, 
            IConfiguration configuration, 
            IHostApplicationLifetime appLifetime,
            HubSettingsService settingsService,
            QuickActionService quickActionService,
            AiCliService aiCliService,
            IHubContext<RpcApiHub> hubContext)
        {
            _logger = logger;
            _configuration = configuration;
            _appLifetime = appLifetime;
            _settingsService = settingsService;
            _quickActionService = quickActionService;
            _aiCliService = aiCliService;
            _hubContext = hubContext;
        }

        public Task StartAsync(CancellationToken cancellationToken)
        {
            _logger.LogInformation("HubStartupService: Registering startup tasks.");
            _appLifetime.ApplicationStarted.Register(OnApplicationStarted);

            return Task.CompletedTask;
        }

        private void OnApplicationStarted()
        {
            // Run startup routines (Quick Actions)
            _ = Task.Run(() => _quickActionService.RunStartupRoutinesAsync());

            bool autoStart = _configuration.GetValue<bool>("AiSettings:AutoStartComponents", true);
            
            if (autoStart)
            {
                // Only launch if the computer booted in the last 5 minutes (300,000 milliseconds)
                if (Environment.TickCount64 < 300000)
                {
                    LaunchFirefox();
                }
                else
                {
                    _logger.LogInformation($"HubStartupService: System has been up for {Environment.TickCount64 / 1000 / 60} minutes. Skipping Firefox auto-launch.");
                }
            }
        }

        private void LaunchFirefox()
        {
            try
            {
                string firefoxPath = @"C:\Program Files\Mozilla Firefox\firefox.exe";
                string url = "http://localhost:3333/Scheduler.html";

                _logger.LogInformation($"HubStartupService: Launching Firefox at {url}...");

                var startInfo = new ProcessStartInfo
                {
                    FileName = firefoxPath,
                    Arguments = url,
                    UseShellExecute = true
                };

                Process.Start(startInfo);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "HubStartupService: Failed to launch Firefox.");
            }
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
