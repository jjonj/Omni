using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;
using Microsoft.AspNetCore.SignalR;
using OmniSync.Hub.Presentation.Hubs;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic.Services
{
    public interface IQuickAction
    {
        string Name { get; }
        Task ExecuteAsync();
    }

    public class BrowserTabCleanupAction : IQuickAction
    {
        private readonly IHubContext<RpcApiHub> _hubContext;
        private readonly ILogger _logger;

        public string Name => "BrowserTabCleanup";

        public BrowserTabCleanupAction(IHubContext<RpcApiHub> hubContext, ILogger logger)
        {
            _hubContext = hubContext;
            _logger = logger;
        }

        public async Task ExecuteAsync()
        {
            _logger.LogInformation("Executing BrowserTabCleanup quick action...");
            // Wait a bit for the extension to connect (if recently started)
            await Task.Delay(3000); 
            await _hubContext.Clients.All.SendAsync("ReceiveBrowserCommand", "CleanTabs", "", false);
        }
    }

    public class JarvisLaunchAction : IQuickAction
    {
        private readonly OmniSync.Hub.Infrastructure.Services.AiCliService _aiCliService;
        private readonly OmniSync.Hub.Infrastructure.Services.HubSettingsService _settingsService;
        private readonly ILogger _logger;
        private readonly HubMonitorService _hubMonitorService;

        public string Name => "JarvisLaunch";

        public JarvisLaunchAction(OmniSync.Hub.Infrastructure.Services.AiCliService aiCliService, OmniSync.Hub.Infrastructure.Services.HubSettingsService settingsService, ILogger logger, HubMonitorService hubMonitorService)
        {
            _aiCliService = aiCliService;
            _settingsService = settingsService;
            _logger = logger;
            _hubMonitorService = hubMonitorService;
        }

        public async Task ExecuteAsync()
        {
            if (!_settingsService.Settings.JarvisAutoStart) return;

            try
            {
                string workspace = _settingsService.Settings.JarvisWorkspace;
                string systemPromptPath = _settingsService.Settings.JarvisSystemContextPath;
                string model = _settingsService.Settings.JarvisModel;

                _logger.LogInformation($"QuickActionService: Auto-launching Jarvis session in workspace: {workspace} (Model: {model})");
                _hubMonitorService.AddLogMessage($"[AI] Daily Auto-launch: Starting Jarvis in {workspace}...");

                var pid = await _aiCliService.LaunchSessionAsync(workspace, null, model, systemPromptPath);

                if (pid.HasValue)
                {
                    _logger.LogInformation($"QuickActionService: Jarvis session started with PID {pid.Value}. Sending 'Begin'.");
                    _hubMonitorService.AddLogMessage($"[AI] Jarvis started (PID {pid.Value}). Sending 'Begin'.");
                    
                    // Send the "Begin" message to kick off the loop
                    await Task.Delay(5000); // Give CLI more time to settle during startup
                    await _aiCliService.SendPromptAsync("Begin", pid.Value);
                }
                else
                {
                    _logger.LogWarning("QuickActionService: Failed to auto-start Jarvis session.");
                    _hubMonitorService.AddLogMessage("[Error] Failed to auto-start Jarvis.");
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "QuickActionService: Failed to launch Jarvis.");
                _hubMonitorService.AddLogMessage($"[Error] Jarvis launch failed: {ex.Message}");
            }
        }
    }

    public class StartupState
    {
        public DateTime? LastRun { get; set; }
    }

    public class QuickActionService
    {
        private readonly ILogger<QuickActionService> _logger;
        private readonly IConfiguration _configuration;
        private readonly IServiceProvider _serviceProvider;
        private readonly IHubContext<RpcApiHub> _hubContext;
        private readonly HubMonitorService _hubMonitorService;
        private readonly OmniSync.Hub.Infrastructure.Services.AiCliService _aiCliService;
        private readonly OmniSync.Hub.Infrastructure.Services.HubSettingsService _settingsService;
        private readonly string _statePath;
        private StartupState _state = new();

        public QuickActionService(
            ILogger<QuickActionService> logger,
            IConfiguration configuration,
            IServiceProvider serviceProvider,
            IHubContext<RpcApiHub> hubContext,
            HubMonitorService hubMonitorService,
            OmniSync.Hub.Infrastructure.Services.AiCliService aiCliService,
            OmniSync.Hub.Infrastructure.Services.HubSettingsService settingsService)
        {
            _logger = logger;
            _configuration = configuration;
            _serviceProvider = serviceProvider;
            _hubContext = hubContext;
            _hubMonitorService = hubMonitorService;
            _aiCliService = aiCliService;
            _settingsService = settingsService;

            string appDataPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniSync");
            if (!Directory.Exists(appDataPath))
            {
                Directory.CreateDirectory(appDataPath);
            }
            _statePath = Path.Combine(appDataPath, "startup_state.json");
            
            LoadState();
        }

        private void LoadState()
        {
            try
            {
                if (File.Exists(_statePath))
                {
                    string json = File.ReadAllText(_statePath);
                    // Ensure we handle empty or invalid files gracefully
                    if (string.IsNullOrWhiteSpace(json))
                    {
                        _state = new StartupState();
                    }
                    else
                    {
                        _state = JsonSerializer.Deserialize<StartupState>(json) ?? new StartupState();
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error loading startup state.");
                _state = new StartupState();
            }
        }

        private void SaveState()
        {
            try
            {
                string json = JsonSerializer.Serialize(_state, new JsonSerializerOptions { WriteIndented = true });
                File.WriteAllText(_statePath, json);
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Error saving startup state.");
            }
        }

        public async Task RunStartupRoutinesAsync()
        {
            if (!_configuration.GetValue<bool>("StartupRoutines:Enabled", true))
            {
                _logger.LogInformation("Startup routines are disabled in configuration.");
                return;
            }

            var today = DateTime.Today;
            if (_state.LastRun == null || _state.LastRun.Value.Date < today)
            {
                _logger.LogInformation("QuickActionService: Running once-per-day startup routines...");
                _hubMonitorService.AddLogMessage("[System] Running daily startup routines...");

                var actionsToRun = _configuration.GetSection("StartupRoutines:Actions").Get<List<string>>() ?? new List<string> { "BrowserTabCleanup" };
                
                // Add JarvisLaunch to actions if enabled
                if (_settingsService.Settings.JarvisAutoStart && !actionsToRun.Contains("JarvisLaunch"))
                {
                    actionsToRun.Add("JarvisLaunch");
                }

                foreach (var actionName in actionsToRun)
                {
                    IQuickAction? action = actionName switch
                    {
                        "BrowserTabCleanup" => new BrowserTabCleanupAction(_hubContext, _logger),
                        "JarvisLaunch" => new JarvisLaunchAction(_aiCliService, _settingsService, _logger, _hubMonitorService),
                        _ => null
                    };

                    if (action != null)
                    {
                        try
                        {
                            await action.ExecuteAsync();
                        }
                        catch (Exception ex)
                        {
                            _logger.LogError(ex, $"Error executing quick action: {actionName}");
                            _hubMonitorService.AddLogMessage($"[Error] Quick Action {actionName} failed: {ex.Message}");
                        }
                    }
                }

                _state.LastRun = DateTime.Now;
                SaveState();
                _logger.LogInformation("QuickActionService: Startup routines completed.");
                _hubMonitorService.AddLogMessage("[System] Startup routines completed.");
            }
            else
            {
                _logger.LogInformation($"QuickActionService: Startup routines already ran today ({_state.LastRun.Value}).");
            }
        }
    }
}
