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
        // New: Event to notify of any command received by the Hub
        public static event EventHandler<string>? AnyCommandReceived;

        // New: Events for client connection/disconnection
        public static event EventHandler<string>? ClientConnectedEvent;
        public static event EventHandler<string>? ClientDisconnectedEvent;

        private readonly AuthService _authService;
        private readonly FileService _fileService;
        private readonly ClipboardService _clipboardService;
        private readonly CommandDispatcher _commandDispatcher;
        private readonly ProcessService _processService;
        private readonly HubEventSender _hubEventSender;
        private readonly InputService _inputService;
        private readonly AudioService _audioService; // Add AudioService

        public RpcApiHub(AuthService authService, FileService fileService, ClipboardService clipboardService, CommandDispatcher commandDispatcher, ProcessService processService, HubEventSender hubEventSender, InputService inputService, AudioService audioService) // Add AudioService to constructor
        {
            _authService = authService;
            _fileService = fileService;
            _clipboardService = clipboardService;
            _commandDispatcher = commandDispatcher;
            _processService = processService;
            _hubEventSender = hubEventSender;
            _inputService = inputService;
            _audioService = audioService; // Assign AudioService
        }
        public override async Task OnConnectedAsync()
        {
            System.Console.WriteLine($"Client connected: {Context.ConnectionId}. Awaiting authentication.");
            ClientConnectedEvent?.Invoke(this, Context.ConnectionId); // New: Invoke event
            _hubEventSender.SubscribeForCommandOutput(Context.UserIdentifier ?? Context.ConnectionId, Context.ConnectionId);
            await base.OnConnectedAsync();
        }

        public override async Task OnDisconnectedAsync(Exception exception)
        {
            System.Console.WriteLine($"Client disconnected: {Context.ConnectionId}");
            ClientDisconnectedEvent?.Invoke(this, Context.ConnectionId); // New: Invoke event
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

        public float GetVolume()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "GetVolume");
                return _audioService.GetMasterVolume();
            }
            throw new UnauthorizedAccessException("Client is not authenticated.");
        }

        public bool IsMuted()
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "IsMuted");
                return _audioService.IsMuted();
            }
            throw new UnauthorizedAccessException("Client is not authenticated.");
        }

        public void SendPayload(string command, JsonElement payload)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                // Invoke the event for SendPayload commands
                AnyCommandReceived?.Invoke(this, command);

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

        public void MouseMove(JsonElement payload)
        {
            if (Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) && (bool)isAuthenticated)
            {
                AnyCommandReceived?.Invoke(this, "MouseMove");

                try
                {
                    // Get double values and round to nearest integer
                    int x = (int)Math.Round(payload.GetProperty("X").GetDouble());
                    int y = (int)Math.Round(payload.GetProperty("Y").GetDouble());
                    _inputService.MoveMouse(x, y);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"Error moving mouse: {ex.Message}");
                }
            }
        }        public void UpdateClipboard(string text)
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

                // Invoke the event for ExecuteCommand commands
                AnyCommandReceived?.Invoke(this, command);

                (string commandName, List<string> args) = ParseCommand(command);

                switch (commandName.ToLowerInvariant())
                {
                    case "write_file":
                        if (args.Count == 2)
                        {
                            // Special handling for write_file if needed
                            _fileService.WriteFile(args[0], args[1]);
                            await Clients.Caller.SendAsync("ReceiveCommandOutput", $"File '{args[0]}' written successfully.");
                        }
                        else
                        {
                            await Clients.Caller.SendAsync("ReceiveCommandOutput", "Usage: write_file \"filepath\" \"content\"");
                        }
                        break;
                    case "list_notes":
                        var files = Directory.GetFiles(_fileService.GetNoteRootPath(), "*.md", SearchOption.TopDirectoryOnly);
                        var fileNames = new List<string>();
                        foreach (var file in files)
                        {
                            fileNames.Add(Path.GetFileName(file));
                        }
                        await Clients.Caller.SendAsync("ReceiveCommandOutput", string.Join("\n", fileNames));
                        break;
                    // Add other commands here
                    default:
                        // Fallback to process service for unrecognized commands
                        await _processService.ExecuteCommand(command);
                        await Clients.Caller.SendAsync("CommandExecutionCompleted", command);
                        break;
                }
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

        public async Task<IEnumerable<OmniSync.Hub.Models.FileSystemEntry>> ListDirectory(string relativePath)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }

                // Invoke the event for ListDirectory commands
                AnyCommandReceived?.Invoke(this, $"ListDirectory: {relativePath}");

                return _fileService.ListDirectoryContents(relativePath);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error listing directory '{relativePath}': {ex.Message}");
                // Return an empty list or throw a more specific exception that the client can handle
                throw new HubException($"Error listing directory: {ex.Message}", ex);
            }
        }

        public async Task<byte[]> GetFileChunk(string filePath, long offset, int chunkSize)
        {
            try
            {
                if (!Context.Items.TryGetValue("IsAuthenticated", out var isAuthenticated) || !(bool)isAuthenticated)
                {
                    throw new UnauthorizedAccessException("Client is not authenticated.");
                }

                // Invoke the event for GetFileChunk commands
                AnyCommandReceived?.Invoke(this, $"GetFileChunk: {filePath} Offset: {offset} Size: {chunkSize}");

                return _fileService.GetFileChunk(filePath, offset, chunkSize);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"Error getting file chunk for '{filePath}': {ex.Message}");
                throw new HubException($"Error getting file chunk: {ex.Message}", ex);
            }
        }

        private (string commandName, List<string> args) ParseCommand(string commandString)
        {
            var parts = new List<string>();
            var inQuote = false;
            var currentPart = new System.Text.StringBuilder();

            for (int i = 0; i < commandString.Length; i++)
            {
                if (commandString[i] == '"')
                {
                    inQuote = !inQuote;
                    // If we just closed a quote, and the current part has content, add it.
                    // This handles cases like `command "arg1" "arg2"`
                    if (!inQuote && currentPart.Length > 0)
                    {
                        parts.Add(currentPart.ToString());
                        currentPart.Clear();
                    }
                    else if (inQuote && currentPart.Length > 0)
                    {
                        // If we just opened a quote, and there's content, that means it's a command name followed by a space then a quote
                        parts.Add(currentPart.ToString());
                        currentPart.Clear();
                    }
                }
                else if (commandString[i] == ' ' && !inQuote)
                {
                    if (currentPart.Length > 0)
                    {
                        parts.Add(currentPart.ToString());
                        currentPart.Clear();
                    }
                }
                else
                {
                    currentPart.Append(commandString[i]);
                }
            }

            if (currentPart.Length > 0)
            {
                parts.Add(currentPart.ToString());
            }

            if (parts.Count == 0)
            {
                return (string.Empty, new List<string>());
            }

            string commandName = parts[0];
            List<string> args = parts.Count > 1 ? parts.GetRange(1, parts.Count - 1) : new List<string>();

            return (commandName, args);
        }
    }
}

