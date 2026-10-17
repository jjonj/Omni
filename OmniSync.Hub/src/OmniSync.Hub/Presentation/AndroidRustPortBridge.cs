using System;
using System.Buffers;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.WebSockets;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.DependencyInjection;

namespace OmniSync.Hub.Presentation;

/// <summary>
/// Short-lived, reversible Android transport handoff while the C# Hub still
/// owns the public :5000 listener. Existing devices keep their configured
/// URL, but their LAN/WAN SignalR connections and Android media/API paths
/// terminate at the Rust Hub on local loopback :5001. C# localhost clients
/// retain the original Hub. Neither gateway nor Google Home is affected.
/// </summary>
internal static class AndroidRustPortBridge
{
    internal static string Marker => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "OmniHub", "android-rust.flag");

    private static readonly HttpClient Client = new(new HttpClientHandler {
        UseProxy = false, UseCookies = false, AllowAutoRedirect = false,
        AutomaticDecompression = DecompressionMethods.None
    }) { Timeout = Timeout.InfiniteTimeSpan };

    private static bool IsAndroidPath(PathString path) =>
        path.StartsWithSegments("/signalrhub", StringComparison.OrdinalIgnoreCase)
        || path.StartsWithSegments("/terminalhub", StringComparison.OrdinalIgnoreCase)
        || path.StartsWithSegments("/remoteterminalhub", StringComparison.OrdinalIgnoreCase)
        || path.StartsWithSegments("/internal/remote-terminal", StringComparison.OrdinalIgnoreCase)
        || path.StartsWithSegments("/api/stream", StringComparison.OrdinalIgnoreCase)
        || path.StartsWithSegments("/api/screenshot", StringComparison.OrdinalIgnoreCase)
        || path.StartsWithSegments("/api/assistant", StringComparison.OrdinalIgnoreCase)
        || path.StartsWithSegments("/api/voice-inbox", StringComparison.OrdinalIgnoreCase)
        || path.StartsWithSegments("/api/external", StringComparison.OrdinalIgnoreCase);

    internal static bool ShouldHandle(HttpContext context)
    {
        if (!File.Exists(Marker) || context.Connection.LocalPort != 5000
            || !IsAndroidPath(context.Request.Path)) return false;
        var ip = context.Connection.RemoteIpAddress;
        // Never intercept the C# app's own loopback SignalR traffic, even
        // when a device uses localhost inside the Windows machine.
        return ip is not null && !IPAddress.IsLoopback(ip);
    }

    internal static async Task ForwardAsync(HttpContext context, IConfiguration config)
    {
        context.Response.Headers.CacheControl = "no-store";
        var secret = config["AuthApiKey"];
        if (string.IsNullOrWhiteSpace(secret))
        {
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            return;
        }
        var upstream = new Uri("http://127.0.0.1:5001/");
        if (!await HealthyAsync(upstream, secret, context.RequestAborted))
        {
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            await context.Response.WriteAsync("Rust Android transport is unavailable.", context.RequestAborted);
            return;
        }
        // Log connection ownership without recording auth tokens or query
        // parameters; this also lets the operator verify that real Android
        // clients returned to Rust after C# was restarted.
        if (context.WebSockets.IsWebSocketRequest ||
            context.Request.Path.Value?.EndsWith("/negotiate",StringComparison.OrdinalIgnoreCase) == true)
            context.RequestServices.GetService<ILoggerFactory>()?
                .CreateLogger("AndroidRustCutover")
                .LogInformation("Delegating {Path} from {Remote} to Rust :5001",
                    context.Request.Path.Value, context.Connection.RemoteIpAddress);
        if (context.WebSockets.IsWebSocketRequest)
        {
            await ForwardSocketAsync(context);
            return;
        }
        await ForwardHttpAsync(context, upstream);
    }

    private static async Task<bool> HealthyAsync(Uri backend, string secret, CancellationToken token)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get,
                new Uri(backend, "api/omnihub/identity"));
            request.Headers.TryAddWithoutValidation("x-omni-api-key", secret);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
            timeout.CancelAfter(TimeSpan.FromSeconds(4));
            using var response = await Client.SendAsync(request,
                HttpCompletionOption.ResponseHeadersRead, timeout.Token);
            if (!response.IsSuccessStatusCode || response.Content.Headers.ContentLength is > 4096)
                return false;
            await using var content = await response.Content.ReadAsStreamAsync(timeout.Token);
            using var doc = await JsonDocument.ParseAsync(content,
                new JsonDocumentOptions { MaxDepth = 6 }, timeout.Token);
            return doc.RootElement.TryGetProperty("service", out var name) &&
                name.GetString() == "omnihub-rust" &&
                doc.RootElement.TryGetProperty("protocol", out var protocol) &&
                protocol.GetInt32() == 1;
        }
        catch (Exception error) when (error is HttpRequestException or IOException
             or TaskCanceledException or JsonException or InvalidOperationException)
        {
            return false;
        }
    }

    private static Uri Target(HttpContext context, bool websocket = false)
    {
        var prefix = websocket ? "ws" : "http";
        var path = context.Request.Path.Value?.TrimStart('/') ?? "";
        return new Uri($"{prefix}://127.0.0.1:5001/{path}{context.Request.QueryString}");
    }

    private static async Task ForwardHttpAsync(HttpContext ctx, Uri backend)
    {
        try
        {
            using var request = new HttpRequestMessage(new HttpMethod(ctx.Request.Method), Target(ctx));
            if (ctx.Request.ContentLength is > 0 || ctx.Request.Headers.ContainsKey("Transfer-Encoding"))
                request.Content = new StreamContent(ctx.Request.Body);
            foreach (var header in ctx.Request.Headers)
            {
                if (header.Key.Equals("Host", StringComparison.OrdinalIgnoreCase)
                    || header.Key.Equals("Connection", StringComparison.OrdinalIgnoreCase)
                    || header.Key.Equals("Transfer-Encoding", StringComparison.OrdinalIgnoreCase)
                    || header.Key.Equals("Upgrade", StringComparison.OrdinalIgnoreCase)) continue;
                if (!(request.Headers.TryAddWithoutValidation(header.Key,header.Value.ToArray())))
                    request.Content?.Headers.TryAddWithoutValidation(header.Key,header.Value.ToArray());
            }
            using var reply = await Client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead,
                ctx.RequestAborted);
            ctx.Response.StatusCode = (int)reply.StatusCode;
            ctx.Response.Headers["X-Omni-Android-Owner"] = "rust";
            foreach (var header in reply.Headers.Concat(reply.Content.Headers))
            {
                if (header.Key.Equals("Transfer-Encoding",StringComparison.OrdinalIgnoreCase)
                    || header.Key.Equals("Connection",StringComparison.OrdinalIgnoreCase)) continue;
                ctx.Response.Headers[header.Key] = header.Value.ToArray();
            }
            await reply.Content.CopyToAsync(ctx.Response.Body, ctx.RequestAborted);
        }
        catch (Exception error) when (error is HttpRequestException or IOException
               or TaskCanceledException or InvalidOperationException)
        {
            if (!ctx.Response.HasStarted)
                ctx.Response.StatusCode = StatusCodes.Status502BadGateway;
        }
    }

    private static async Task ForwardSocketAsync(HttpContext ctx)
    {
        using var upstream = new ClientWebSocket();
        upstream.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
        if (ctx.Request.Headers.TryGetValue("Authorization", out var authorization))
            upstream.Options.SetRequestHeader("Authorization", authorization.ToString());
        if (ctx.Request.Headers.TryGetValue("X-Omni-Api-Key", out var apiKey))
            upstream.Options.SetRequestHeader("X-Omni-Api-Key", apiKey.ToString());
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ctx.RequestAborted);
            timeout.CancelAfter(TimeSpan.FromSeconds(8));
            await upstream.ConnectAsync(Target(ctx, websocket:true), timeout.Token);
        }
        catch (Exception error) when (error is WebSocketException or HttpRequestException
               or TaskCanceledException or InvalidOperationException)
        {
            ctx.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            return;
        }
        using var downstream = await ctx.WebSockets.AcceptWebSocketAsync();
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(ctx.RequestAborted);
        var upTask=RelayAsync(downstream,upstream,lifetime.Token);
        var downTask=RelayAsync(upstream,downstream,lifetime.Token);
        await Task.WhenAny(upTask,downTask);
        lifetime.Cancel();
        try {await Task.WhenAll(upTask,downTask);} catch (OperationCanceledException) {}
        catch (WebSocketException) {} catch (IOException) {}
    }

    private static async Task RelayAsync(WebSocket source, WebSocket destination,
        CancellationToken token)
    {
        var buffer = ArrayPool<byte>.Shared.Rent(64*1024);
        try
        {
            while (!token.IsCancellationRequested)
            {
                var message=await source.ReceiveAsync(new ArraySegment<byte>(buffer),token);
                if (message.MessageType == WebSocketMessageType.Close)
                {
                    if (destination.State == WebSocketState.Open)
                        await destination.CloseOutputAsync(WebSocketCloseStatus.NormalClosure,
                            "Connection closed",token);
                    break;
                }
                await destination.SendAsync(new ArraySegment<byte>(buffer,0,message.Count),
                    message.MessageType,message.EndOfMessage,token);
            }
        }
        finally {ArrayPool<byte>.Shared.Return(buffer);}
    }
}
