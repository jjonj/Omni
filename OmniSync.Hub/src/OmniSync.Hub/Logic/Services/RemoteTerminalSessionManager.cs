using System.Collections.Concurrent;
using System;
using System.Diagnostics;
using System.IO;
using System.Net.WebSockets;
using System.Security.Cryptography;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Presentation.Hubs;

namespace OmniSync.Hub.Logic.Services;

public sealed record RemoteTerminalSessionInfo(string SessionId, string Command, string Workspace, int Columns, int Rows);

public sealed class RemoteTerminalSessionManager
{
    private readonly ConcurrentDictionary<string, RemoteTerminalSession> _sessions = new();
    private readonly IHubContext<RemoteTerminalHub> _hubContext;
    private readonly IConfiguration _configuration;
    private readonly ILogger<RemoteTerminalSessionManager> _logger;

    public RemoteTerminalSessionManager(
        IHubContext<RemoteTerminalHub> hubContext,
        IConfiguration configuration,
        ILogger<RemoteTerminalSessionManager> logger)
    {
        _hubContext = hubContext;
        _configuration = configuration;
        _logger = logger;
    }

    public RemoteTerminalSessionInfo Start(string command, string workspace, int columns, int rows)
    {
        if (string.IsNullOrWhiteSpace(command)) throw new ArgumentException("A terminal command is required.", nameof(command));
        if (!Directory.Exists(workspace)) throw new DirectoryNotFoundException($"Workspace does not exist: {workspace}");

        var id = Guid.NewGuid().ToString("N");
        var token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));
        var session = new RemoteTerminalSession(id, token, command, workspace, columns, rows);
        if (!_sessions.TryAdd(id, session)) throw new InvalidOperationException("Could not allocate remote terminal session.");

        try
        {
            session.Process = LaunchOmnish(session);
            _ = PublishStatusAsync(session, "launching");
            return new RemoteTerminalSessionInfo(id, command, workspace, columns, rows);
        }
        catch
        {
            _sessions.TryRemove(id, out _);
            throw;
        }
    }

    public bool CanAttach(string sessionId, string? token)
    {
        if (!_sessions.TryGetValue(sessionId, out var session) || string.IsNullOrWhiteSpace(token)) return false;
        try
        {
            return CryptographicOperations.FixedTimeEquals(
                Convert.FromBase64String(session.Token), Convert.FromBase64String(token));
        }
        catch (FormatException)
        {
            return false;
        }
    }

    public async Task AttachAsync(string sessionId, WebSocket socket)
    {
        if (!_sessions.TryGetValue(sessionId, out var session)) throw new InvalidOperationException("Remote terminal session was not found.");
        lock (session.Sync)
        {
            if (session.HostSocket is not null) throw new InvalidOperationException("Remote terminal session already has an Omnish host.");
            session.HostSocket = socket;
        }

        await PublishStatusAsync(session, "ready");
    }

    public async Task SendInputAsync(string sessionId, byte[] bytes)
    {
        if (!_sessions.TryGetValue(sessionId, out var session)) throw new InvalidOperationException("Remote terminal session was not found.");
        var socket = session.HostSocket ?? throw new InvalidOperationException("Omnish has not connected to this session yet.");
        await session.SendLock.WaitAsync();
        try
        {
            await socket.SendAsync(bytes, WebSocketMessageType.Binary, true, CancellationToken.None);
        }
        finally
        {
            session.SendLock.Release();
        }
    }

    public Task PublishOutputAsync(string sessionId, byte[] bytes) =>
        _hubContext.Clients.Group(GroupName(sessionId)).SendAsync("RemoteTerminalOutput", sessionId, Convert.ToBase64String(bytes));

    public async Task StopAsync(string sessionId)
    {
        if (!_sessions.TryRemove(sessionId, out var session)) return;
        if (session.HostSocket is { State: WebSocketState.Open })
        {
            await session.HostSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, "Session stopped", CancellationToken.None);
        }
        if (session.Process is { HasExited: false }) session.Process.Kill(entireProcessTree: true);
        await PublishStatusAsync(session, "stopped");
    }

    public Task SubscribeAsync(string connectionId, string sessionId) =>
        _hubContext.Groups.AddToGroupAsync(connectionId, GroupName(sessionId));

    public Task UnsubscribeAsync(string connectionId, string sessionId) =>
        _hubContext.Groups.RemoveFromGroupAsync(connectionId, GroupName(sessionId));

    public string GetStatus(string sessionId)
    {
        if (!_sessions.TryGetValue(sessionId, out var session)) return "stopped";
        return session.HostSocket is { State: WebSocketState.Open } ? "ready" : "launching";
    }

    private Process LaunchOmnish(RemoteTerminalSession session)
    {
        var executable = _configuration["RemoteTerminal:OmnishPath"]
            ?? @"D:\SSDProjects\Tools\Omnish\main\target\release\omnish.exe";
        if (!File.Exists(executable)) throw new FileNotFoundException("Omnish executable was not found. Set RemoteTerminal:OmnishPath.", executable);

        var hubUrl = _configuration["RemoteTerminal:HubUrl"] ?? "http://127.0.0.1:5000";
        var startInfo = new ProcessStartInfo(executable)
        {
            WorkingDirectory = session.Workspace,
            UseShellExecute = false,
            CreateNoWindow = false
        };
        startInfo.ArgumentList.Add(session.Workspace);
        startInfo.Environment["OMNI_REMOTE_TERMINAL_HUB_URL"] = hubUrl;
        startInfo.Environment["OMNI_REMOTE_TERMINAL_SESSION_ID"] = session.Id;
        startInfo.Environment["OMNI_REMOTE_TERMINAL_TOKEN"] = session.Token;
        startInfo.Environment["OMNI_REMOTE_TERMINAL_COMMAND"] = session.Command;
        startInfo.Environment["OMNI_REMOTE_TERMINAL_COLUMNS"] = session.Columns.ToString();
        startInfo.Environment["OMNI_REMOTE_TERMINAL_ROWS"] = session.Rows.ToString();
        _logger.LogInformation("Launching Omnish remote terminal {SessionId} in {Workspace}", session.Id, session.Workspace);
        return Process.Start(startInfo) ?? throw new InvalidOperationException("Omnish did not start.");
    }

    private Task PublishStatusAsync(RemoteTerminalSession session, string status) =>
        _hubContext.Clients.Group(GroupName(session.Id)).SendAsync("RemoteTerminalStatus", session.Id, status);

    private static string GroupName(string sessionId) => $"RemoteTerminal_{sessionId}";

    private sealed class RemoteTerminalSession
    {
        public RemoteTerminalSession(string id, string token, string command, string workspace, int columns, int rows)
        {
            Id = id; Token = token; Command = command; Workspace = workspace; Columns = columns; Rows = rows;
        }
        public string Id { get; }
        public string Token { get; }
        public string Command { get; }
        public string Workspace { get; }
        public int Columns { get; }
        public int Rows { get; }
        public object Sync { get; } = new();
        public SemaphoreSlim SendLock { get; } = new(1, 1);
        public Process? Process { get; set; }
        public WebSocket? HostSocket { get; set; }
    }
}
