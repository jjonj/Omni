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
        private readonly string _statePath;
        private StartupState _state = new();

        public QuickActionService(
            ILogger<QuickActionService> logger,
            IConfiguration configuration,
            IServiceProvider serviceProvider,
            IHubContext<RpcApiHub> hubContext,
            HubMonitorService hubMonitorService)
        {
            _logger = logger;
            _configuration = configuration;
            _serviceProvider = serviceProvider;
            _hubContext = hubContext;
            _hubMonitorService = hubMonitorService;

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
                
                foreach (var actionName in actionsToRun)
                {
                    IQuickAction? action = actionName switch
                    {
                        "BrowserTabCleanup" => new BrowserTabCleanupAction(_hubContext, _logger),
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
