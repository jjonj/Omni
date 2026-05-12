using Microsoft.AspNetCore.SignalR;
using System;
using System.Threading.Tasks;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Presentation.Hubs
{
    public class TerminalHub : Microsoft.AspNetCore.SignalR.Hub
    {
        private readonly ITerminalService _terminalService;
        private readonly AuthService _authService;
        private readonly HubMonitorService _monitorService;

        public TerminalHub(ITerminalService terminalService, AuthService authService, HubMonitorService monitorService)
        {
            _terminalService = terminalService;
            _authService = authService;
            _monitorService = monitorService;
            
            // Subscribing once at static level if needed, but better to inject into the service and have it broadcast.
            // For this PoC, we'll have the Service raise events and this Hub (or a singleton background worker) broadcast them.
        }

        public async Task<bool> Authenticate(string apiKey)
        {
            if (_authService.Validate(apiKey))
            {
                Context.Items["IsAuthenticated"] = true;
                return true;
            }
            return false;
        }

        public async Task<int?> SpawnTerminal(string shell, string? workspace = null)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var auth) && (bool)auth)
            {
                try
                {
                    return await _terminalService.SpawnTerminalAsync(shell, workspace);
                }
                catch
                {
                    return null;
                }
            }
            throw new UnauthorizedAccessException();
        }

        public async Task<bool> SendCommand(int pid, string command)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var auth) && (bool)auth)
            {
                return await _terminalService.SendCommandAsync(pid, command);
            }
            throw new UnauthorizedAccessException();
        }

        public async Task Subscribe(int pid)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var auth) && (bool)auth)
            {
                await Groups.AddToGroupAsync(Context.ConnectionId, $"Terminal_{pid}");
            }
        }

        public async Task Unsubscribe(int pid)
        {
            await Groups.RemoveFromGroupAsync(Context.ConnectionId, $"Terminal_{pid}");
        }
    }

    // Background service to bridge events to SignalR
    public class TerminalOutputBroadcaster : Microsoft.Extensions.Hosting.BackgroundService
    {
        private readonly ITerminalService _terminalService;
        private readonly IHubContext<TerminalHub> _hubContext;

        public TerminalOutputBroadcaster(ITerminalService terminalService, IHubContext<TerminalHub> hubContext)
        {
            _terminalService = terminalService;
            _hubContext = hubContext;
            _terminalService.OutputReceived += OnOutputReceived;
        }

        private void OnOutputReceived(object? sender, TerminalOutputEventArgs e)
        {
            _hubContext.Clients.Group($"Terminal_{e.Pid}").SendAsync("ReceiveOutput", e.Pid, e.Data);
        }

        protected override Task ExecuteAsync(System.Threading.CancellationToken stoppingToken) => Task.CompletedTask;
    }
}
