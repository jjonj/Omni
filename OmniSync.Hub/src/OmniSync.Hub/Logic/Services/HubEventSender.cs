using Microsoft.AspNetCore.SignalR;
using OmniSync.Hub.Presentation.Hubs;
using OmniSync.Hub.Infrastructure.Services;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;

namespace OmniSync.Hub.Logic.Services
{
    public class HubEventSender
    {
        private readonly IHubContext<RpcApiHub> _hubContext;
        private readonly ProcessService _processService;
        private readonly InputService _inputService;
        private readonly ShutdownService _shutdownService;
        private readonly CommandDispatcher _commandDispatcher;
        private readonly FileService _fileService; // Added FileService dependency
        private readonly Dictionary<string, string> _clientCommandOutputSubscriptions = new Dictionary<string, string>(); // ClientId -> ConnectionId for command output

        public HubEventSender(IHubContext<RpcApiHub> hubContext, ProcessService processService, InputService inputService, ShutdownService shutdownService, CommandDispatcher commandDispatcher, FileService fileService) // Added FileService
        {
            _hubContext = hubContext;
            _processService = processService;
            _inputService = inputService;
            _shutdownService = shutdownService;
            _commandDispatcher = commandDispatcher;
            _fileService = fileService; // Assign FileService

            _processService.CommandOutputReceived += OnCommandOutputReceived;
            _inputService.ModifierStateChanged += OnModifierStateChanged;
            _shutdownService.ShutdownScheduled += OnShutdownScheduled;
            _shutdownService.ModeChanged += OnShutdownModeChanged;
            _commandDispatcher.AddCleanupPatternRequested += OnAddCleanupPatternRequested;
            // Subscribe to FileService events
            _fileService.FileWritten += OnFileWritten;
            _fileService.BrowseFileWritten += OnBrowseFileWritten;
            _fileService.FileChanged += OnFileSystemChanged;
        }

        private async void OnShutdownModeChanged(object? sender, ShutdownMode mode)
        {
            await _hubContext.Clients.All.SendAsync("ShutdownModeUpdated", mode.ToString());
        }

        private async void OnAddCleanupPatternRequested(object? sender, string pattern)
        {
            await _hubContext.Clients.All.SendAsync("ReceiveBrowserCommand", "AddCleanupPattern", pattern, false);
        }

        private async void OnModifierStateChanged(object? sender, ModifierStateEventArgs e)
        {
            await _hubContext.Clients.All.SendAsync("ModifierStateUpdated", e.Modifier.ToString(), e.IsPressed);
        }

        private async void OnShutdownScheduled(object? sender, DateTime? scheduledTime)
        {
            await _hubContext.Clients.All.SendAsync("ShutdownScheduled", scheduledTime);
        }

        public async Task BroadcastLogEntryAdded(string message)
        {
            await _hubContext.Clients.All.SendAsync("LogEntryAdded", message);
        }

        public async Task BroadcastCommandUpdate(string command)
        {
            await _hubContext.Clients.All.SendAsync("CommandUpdateOccurred", command);
        }

        public async Task BroadcastConnectionAdded(string connectionId)
        {
            await _hubContext.Clients.All.SendAsync("ConnectionAdded", connectionId);
        }

        public async Task BroadcastConnectionRemoved(string connectionId)
        {
            await _hubContext.Clients.All.SendAsync("ConnectionRemoved", connectionId);
        }

        // Method to be called by RpcApiHub when a client connects and wants command output
        public void SubscribeForCommandOutput(string clientId, string connectionId)
        {
            _clientCommandOutputSubscriptions[clientId] = connectionId;
        }

        public void UnsubscribeFromCommandOutput(string clientId)
        {
            _clientCommandOutputSubscriptions.Remove(clientId);
        }

        private async void OnFileWritten(object? sender, string filePath)
        {
            await BroadcastLogEntryAdded($"File '{filePath}' synced to PC.");
        }

        private async void OnBrowseFileWritten(object? sender, string filePath)
        {
            await BroadcastLogEntryAdded($"Browse file '{filePath}' synced to PC.");
        }

        private async void OnFileSystemChanged(object? sender, string fullPath)
        {
            try
            {
                await _hubContext.Clients.All.SendAsync("FileChanged", fullPath, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Failed to broadcast FileChanged for {fullPath}: {ex.Message}");
            }
        }

        private async void OnCommandOutputReceived(object sender, string output)
        {
            // Iterate over all subscribed clients and send the output
            foreach (var connectionId in _clientCommandOutputSubscriptions.Values.ToList()) // Use ToList() to avoid modification during iteration
            {
                try
                {
                    await _hubContext.Clients.Client(connectionId).SendAsync("ReceiveCommandOutput", output);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error sending command output to client {connectionId}: {ex.Message}");
                    // Optionally, remove disconnected clients from the subscription list here.
                }
            }
        }
    }
}
