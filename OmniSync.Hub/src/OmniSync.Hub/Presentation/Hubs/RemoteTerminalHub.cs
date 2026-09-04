using Microsoft.AspNetCore.SignalR;
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Presentation.Hubs;

public sealed class RemoteTerminalHub : Microsoft.AspNetCore.SignalR.Hub
{
    private readonly AuthService _authService;
    private readonly RemoteTerminalSessionManager _sessions;

    public RemoteTerminalHub(AuthService authService, RemoteTerminalSessionManager sessions)
    {
        _authService = authService;
        _sessions = sessions;
    }

    public Task<bool> Authenticate(string apiKey)
    {
        var valid = _authService.Validate(apiKey);
        if (valid) Context.Items["IsAuthenticated"] = true;
        return Task.FromResult(valid);
    }

    public async Task<RemoteTerminalSessionInfo> StartRemoteTerminal(string command, string workspace, int columns = 120, int rows = 40)
    {
        RequireAuthentication();
        var session = _sessions.Start(command, workspace, columns, rows);
        await Groups.AddToGroupAsync(Context.ConnectionId, $"RemoteTerminal_{session.SessionId}");
        await Clients.Caller.SendAsync("RemoteTerminalStarted", session.SessionId, session.Command, session.Workspace);
        await Clients.Caller.SendAsync("RemoteTerminalStatus", session.SessionId, _sessions.GetStatus(session.SessionId));
        return session;
    }

    public async Task SubscribeRemoteTerminal(string sessionId)
    {
        RequireAuthentication();
        await _sessions.SubscribeAsync(Context.ConnectionId, sessionId);
        await Clients.Caller.SendAsync("RemoteTerminalStatus", sessionId, _sessions.GetStatus(sessionId));
    }

    public Task SendRemoteTerminalInput(string sessionId, string dataBase64)
    {
        RequireAuthentication();
        return _sessions.SendInputAsync(sessionId, Convert.FromBase64String(dataBase64));
    }

    public Task ResizeRemoteTerminal(string sessionId, int columns, int rows)
    {
        RequireAuthentication();
        return _sessions.ResizeAsync(sessionId, columns, rows);
    }

    public Task StopRemoteTerminal(string sessionId)
    {
        RequireAuthentication();
        return _sessions.StopAsync(sessionId);
    }

    public override Task OnDisconnectedAsync(Exception? exception)
    {
        return base.OnDisconnectedAsync(exception);
    }

    private void RequireAuthentication()
    {
        if (!Context.Items.TryGetValue("IsAuthenticated", out var authenticated) || authenticated is not true)
            throw new HubException("Unauthorized");
    }
}
