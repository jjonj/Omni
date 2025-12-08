using Microsoft.AspNetCore.SignalR;
using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Infrastructure.Services; // Added for FileService

namespace OmniSync.Hub.Presentation.Hubs
{
    public class RpcApiHub : Microsoft.AspNetCore.SignalR.Hub
    {
        private readonly AuthService _authService;
        private readonly FileService _fileService;
        private readonly ClipboardService _clipboardService;
        private readonly CommandDispatcher _commandDispatcher;
        private readonly ProcessService _processService;
        private readonly HubEventSender _hubEventSender;

        public RpcApiHub(AuthService authService, FileService fileService, ClipboardService clipboardService, CommandDispatcher commandDispatcher, ProcessService processService, HubEventSender hubEventSender)
        {
            _authService = authService;
            _fileService = fileService;
            _clipboardService = clipboardService;
            _commandDispatcher = commandDispatcher;
            _processService = processService;
            _hubEventSender = hubEventSender;
        }

        public override async Task OnConnectedAsync()
        {
            System.Console.WriteLine($"Client connected: {Context.ConnectionId}. Awaiting authentication.");
            _hubEventSender.SubscribeForCommandOutput(Context.UserIdentifier ?? Context.ConnectionId, Context.ConnectionId);
            await base.OnConnectedAsync();
        }

        public override async Task OnDisconnectedAsync(Exception exception)
        {
            System.Console.WriteLine($"Client disconnected: {Context.ConnectionId}");
            _hubEventSender.UnsubscribeFromCommandOutput(Context.UserIdentifier ?? Context.ConnectionId);
            await base.OnDisconnectedAsync(exception);
        }

        public bool Authenticate(string apiKey)
        {
            var isAuthenticated = _authService.Validate(apiKey);
            if (isAuthenticated)
            {
                Context.Items["IsAuthenticated"] = true;
                System.Console.WriteLine($"Client authenticated: {Context.ConnectionId}");
                return true;
            }

            System.Console.WriteLine($"Client failed authentication: {Context.ConnectionId}");
            Context.Abort();
            return false;
        }

        public void SendPayload(string command, JsonElement payload)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                try
                {
                    _commandDispatcher.Dispatch(command, payload);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error dispatching command '{command}': {ex.Message}");
                }
            }
        }

        public void UpdateClipboard(string text)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                try
                {
                    _clipboardService.SetClipboardText(text);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error setting clipboard text: {ex.Message}");
                }
            }
        }


        public async Task ExecuteCommand(string command)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }
                await _processService.ExecuteCommand(command);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error executing command {command}: {ex.Message}");
                await Clients.Caller.SendAsync("ReceiveCommandOutput", $"Error: {ex.Message}");
            }
        }

        public IEnumerable<ProcessInfo> ListProcesses()
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }
                return _processService.ListProcesses();
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error listing processes: {ex.Message}");
                return new List<ProcessInfo>();
            }
        }

        public bool KillProcess(int processId)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }
                return _processService.KillProcess(processId);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error killing process {processId}: {ex.Message}");
                return false;
            }
        }

        public IEnumerable<string> ListNotes()
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }

                var files = Directory.GetFiles(_fileService.GetRootPath(), "*.md", SearchOption.TopDirectoryOnly);
                var fileNames = new List<string>();
                foreach (var file in files)
                {
                    fileNames.Add(Path.GetFileName(file));
                }
                return fileNames;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error listing notes: {ex.Message}");
                return new List<string>(); // Return empty list on error
            }
        }

        public string GetNoteContent(string filename)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }

                return _fileService.ReadFile(filename);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error getting note content for '{filename}': {ex.Message}");
                return $"Error: Could not retrieve content for '{filename}'.";
            }
        }
    }
}
