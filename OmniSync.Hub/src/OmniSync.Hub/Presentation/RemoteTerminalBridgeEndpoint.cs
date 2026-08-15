using System.Net;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Presentation;

public sealed class RemoteTerminalBridgeEndpoint
{
    private readonly RemoteTerminalSessionManager _sessions;
    private readonly ILogger<RemoteTerminalBridgeEndpoint> _logger;

    public RemoteTerminalBridgeEndpoint(RemoteTerminalSessionManager sessions, ILogger<RemoteTerminalBridgeEndpoint> logger)
    {
        _sessions = sessions;
        _logger = logger;
    }

    public async Task HandleAsync(HttpContext context, string sessionId)
    {
        if (!context.WebSockets.IsWebSocketRequest || context.Connection.RemoteIpAddress is not { } address || !IPAddress.IsLoopback(address))
        {
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        var registration = await ReceiveRegistrationAsync(socket, context.RequestAborted);
        if (registration is null || !_sessions.CanAttach(sessionId, registration.Token))
        {
            await socket.CloseAsync(WebSocketCloseStatus.PolicyViolation, "Invalid remote terminal registration", context.RequestAborted);
            return;
        }

        await _sessions.AttachAsync(sessionId, socket);
        var buffer = new byte[64 * 1024];
        while (socket.State == WebSocketState.Open && !context.RequestAborted.IsCancellationRequested)
        {
            var result = await socket.ReceiveAsync(buffer, context.RequestAborted);
            if (result.MessageType == WebSocketMessageType.Close) break;
            if (result.MessageType == WebSocketMessageType.Binary)
            {
                await _sessions.PublishOutputAsync(sessionId, buffer[..result.Count]);
            }
        }

        _logger.LogInformation("Omnish disconnected from remote terminal {SessionId}", sessionId);
    }

    private static async Task<RemoteTerminalRegistration?> ReceiveRegistrationAsync(WebSocket socket, CancellationToken cancellationToken)
    {
        var buffer = new byte[4096];
        var result = await socket.ReceiveAsync(buffer, cancellationToken);
        if (result.MessageType != WebSocketMessageType.Text || !result.EndOfMessage) return null;
        return JsonSerializer.Deserialize<RemoteTerminalRegistration>(Encoding.UTF8.GetString(buffer, 0, result.Count));
    }

    private sealed record RemoteTerminalRegistration([property: JsonPropertyName("token")] string Token);
}
