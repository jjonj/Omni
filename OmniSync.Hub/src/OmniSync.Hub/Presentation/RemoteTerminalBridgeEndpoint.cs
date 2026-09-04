using System.Net;
using System.Net.WebSockets;
using System.IO;
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
            _logger.LogWarning("Rejected remote terminal bridge session={SessionId} websocket={IsWebSocket} remoteAddress={RemoteAddress}", sessionId, context.WebSockets.IsWebSocketRequest, context.Connection.RemoteIpAddress);
            context.Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        using var socket = await context.WebSockets.AcceptWebSocketAsync();
        _logger.LogInformation("Remote terminal bridge socket accepted session={SessionId}", sessionId);
        var registration = await ReceiveRegistrationAsync(socket, context.RequestAborted);
        if (registration is null || !_sessions.CanAttach(sessionId, registration.Token))
        {
            _logger.LogWarning("Remote terminal bridge registration rejected session={SessionId} registrationPresent={RegistrationPresent}", sessionId, registration is not null);
            await socket.CloseAsync(WebSocketCloseStatus.PolicyViolation, "Invalid remote terminal registration", context.RequestAborted);
            return;
        }

        await _sessions.AttachAsync(sessionId, socket);
        _logger.LogInformation("Remote terminal bridge registration accepted session={SessionId}", sessionId);
        try
        {
            var buffer = new byte[64 * 1024];
            while (socket.State == WebSocketState.Open && !context.RequestAborted.IsCancellationRequested)
            {
                using var output = new MemoryStream();
                WebSocketReceiveResult result;
                do
                {
                    result = await socket.ReceiveAsync(buffer, context.RequestAborted);
                    if (result.MessageType == WebSocketMessageType.Close)
                    {
                        _logger.LogInformation("Remote terminal bridge closed by Omnish session={SessionId} closeStatus={CloseStatus}", sessionId, result.CloseStatus);
                        return;
                    }
                    if (result.MessageType != WebSocketMessageType.Binary)
                    {
                        _logger.LogWarning("Remote terminal bridge ignored non-binary message session={SessionId} messageType={MessageType}", sessionId, result.MessageType);
                        break;
                    }
                    output.Write(buffer, 0, result.Count);
                } while (!result.EndOfMessage);

                if (result.MessageType == WebSocketMessageType.Binary && output.Length > 0)
                {
                    _logger.LogInformation("Remote terminal bridge received output session={SessionId} bytes={ByteCount}", sessionId, output.Length);
                    await _sessions.PublishOutputAsync(sessionId, output.ToArray());
                }
            }
        }
        finally
        {
            _sessions.Detach(sessionId, socket);
            _logger.LogInformation("Omnish disconnected from remote terminal {SessionId}", sessionId);
        }
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
