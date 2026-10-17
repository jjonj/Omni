using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Http;

namespace OmniSync.Hub.Logic.Services.Gateway;

// C# keeps :3333, :5000 and :5055 during the intermediate dual-Hub stage.
// Delegation only proxies /gateway* on :3333 to the loopback Rust :3334.
// Removing the marker immediately restores C# routing, without a restart.
internal static class GatewayDelegation
{
    internal const string Owner = "omnihub-rust-gateway";
    private const int MaxBody = 128 * 1024;
    private static readonly HttpClient Client = new(new HttpClientHandler {
        UseProxy = false, AllowAutoRedirect = false, AutomaticDecompression = DecompressionMethods.None
    }) { Timeout = TimeSpan.FromSeconds(200) }; // tool startup can take 180 s

    internal static string Marker => Environment.GetEnvironmentVariable("OMNI_GATEWAY_RUST_DELEGATION_MARKER")
        is { Length: > 0 } path ? path : Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "OmniHub", "gateway-rust.flag");
    internal static bool Enabled => File.Exists(Marker);

    internal static bool TryPort(string? value, out int port)
    {
        port = 3334;
        return value is null || (int.TryParse(value, out port) &&
            port is >= 1024 and <= 65535 &&
            port is not (3333 or 5000 or 5001 or 5055));
    }

    internal static bool Matches(PathString path) =>
        path.StartsWithSegments("/gateway", StringComparison.OrdinalIgnoreCase);

    // URI resolution normalizes ../ and percent-encoded dot segments. Do not
    // let a LAN client turn a /gateway proxy into a loopback proxy for /api,
    // Ugoira or other private Rust services.
    internal static bool TryTarget(Uri backend, string? rawPath, string query, out Uri? target)
    {
        target = null;
        if (string.IsNullOrEmpty(rawPath) || !Matches(rawPath) ||
            rawPath.Contains('\\') || rawPath.Contains('%') ||
            rawPath.Split('/').Any(part => part is "." or "..")) return false;
        var destination = new Uri(backend, rawPath.TrimStart('/') + query);
        if (destination.Host != backend.Host || destination.Port != backend.Port ||
            !Matches(destination.AbsolutePath)) return false;
        target = destination;
        return true;
    }

    private static bool ValidOrigin(HttpRequest request)
    {
        var origin = request.Headers.Origin.ToString();
        return string.IsNullOrEmpty(origin) || (Uri.TryCreate(origin, UriKind.Absolute, out var uri) &&
            uri.Scheme.Equals(request.Scheme, StringComparison.OrdinalIgnoreCase) &&
            uri.Authority.Equals(request.Host.Value, StringComparison.OrdinalIgnoreCase) &&
            uri.AbsolutePath == "/" && uri.Query.Length == 0 && uri.Fragment.Length == 0);
    }

    private static async Task<bool> RustOwnsGateway(Uri backend, HttpContext context)
    {
        using var probeLimit = System.Threading.CancellationTokenSource.CreateLinkedTokenSource(context.RequestAborted);
        probeLimit.CancelAfter(TimeSpan.FromSeconds(3));
        using var reply = await Client.GetAsync(new Uri(backend, "gateway/api/owner"),
            HttpCompletionOption.ResponseHeadersRead, probeLimit.Token);
        if (!reply.IsSuccessStatusCode || reply.Content.Headers.ContentLength is > 1024) return false;
        await using var stream = await reply.Content.ReadAsStreamAsync(probeLimit.Token);
        using var doc = await JsonDocument.ParseAsync(stream,
            new JsonDocumentOptions { MaxDepth = 6 }, probeLimit.Token);
        var root = doc.RootElement;
        return root.TryGetProperty("service", out var name) && name.GetString() == Owner &&
            root.TryGetProperty("protocol", out var version) && version.GetInt32() == 1 &&
            root.TryGetProperty("delegated", out var enabled) && enabled.ValueKind == JsonValueKind.True &&
            root.TryGetProperty("port", out var port) && port.GetInt32() == backend.Port;
    }

    internal static async Task ProxyAsync(HttpContext ctx)
    {
        ctx.Response.Headers.CacheControl = "no-store";
        if (!Enabled)
        {
            ctx.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            return;
        }
        if (!TryPort(Environment.GetEnvironmentVariable("OMNI_GATEWAY_RUST_PORT"), out var port))
        {
            ctx.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            return;
        }
        if (!HttpMethods.IsGet(ctx.Request.Method) && !HttpMethods.IsHead(ctx.Request.Method) &&
            !ValidOrigin(ctx.Request))
        {
            ctx.Response.StatusCode = StatusCodes.Status403Forbidden;
            return;
        }
        var backend = new Uri($"http://127.0.0.1:{port}/");
        try
        {
            // Authenticate upstream ownership BEFORE sending ANY action.
            // Never fall through to the old C# launcher when Rust is unavailable.
            if (!await RustOwnsGateway(backend, ctx))
            {
                ctx.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
                await ctx.Response.WriteAsJsonAsync(new { error = "Rust Gateway owner unavailable." });
                return;
            }
            if (!TryTarget(backend, ctx.Request.Path.Value, ctx.Request.QueryString.ToString(), out var target))
            {
                ctx.Response.StatusCode = StatusCodes.Status400BadRequest;
                return;
            }
            using var request = new HttpRequestMessage(new HttpMethod(ctx.Request.Method), target);
            if (!HttpMethods.IsGet(ctx.Request.Method) && !HttpMethods.IsHead(ctx.Request.Method))
            {
                if (ctx.Request.ContentLength is > MaxBody)
                {
                    ctx.Response.StatusCode = StatusCodes.Status413PayloadTooLarge;
                    return;
                }
                await using var data = new MemoryStream();
                var buffer = new byte[8192];
                while (true)
                {
                    var size = await ctx.Request.Body.ReadAsync(buffer, ctx.RequestAborted);
                    if (size == 0) break;
                    if (data.Length + size > MaxBody)
                    {
                        ctx.Response.StatusCode = StatusCodes.Status413PayloadTooLarge;
                        return;
                    }
                    await data.WriteAsync(buffer.AsMemory(0, size), ctx.RequestAborted);
                }
                request.Content = new ByteArrayContent(data.ToArray());
                if (!string.IsNullOrWhiteSpace(ctx.Request.ContentType))
                    request.Content.Headers.TryAddWithoutValidation("Content-Type", ctx.Request.ContentType);
            }
            // Intentionally do not forward Host, cookies, auth or proxy headers.
            // Status, search and static reads fail promptly. A tool-launch POST
            // may validly wait up to 180 s for health checks to complete.
            using var requestLimit = System.Threading.CancellationTokenSource.CreateLinkedTokenSource(ctx.RequestAborted);
            if (!ctx.Request.Path.StartsWithSegments("/gateway/api/launch", StringComparison.OrdinalIgnoreCase))
                requestLimit.CancelAfter(TimeSpan.FromSeconds(30));
            using var reply = await Client.SendAsync(request,
                HttpCompletionOption.ResponseHeadersRead, requestLimit.Token);
            ctx.Response.StatusCode = (int)reply.StatusCode;
            ctx.Response.Headers["X-Omni-Gateway-Owner"] = "rust-delegated";
            if (reply.Content.Headers.ContentType is { } contentType)
                ctx.Response.ContentType = contentType.ToString();
            if (reply.Headers.Location is { IsAbsoluteUri: false } location)
                ctx.Response.Headers.Location = location.ToString();
            await reply.Content.CopyToAsync(ctx.Response.Body, ctx.RequestAborted);
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException
            or IOException or JsonException or InvalidOperationException)
        {
            if (!ctx.Response.HasStarted)
            {
                ctx.Response.StatusCode = StatusCodes.Status502BadGateway;
                await ctx.Response.WriteAsJsonAsync(new {
                    error = "Rust Gateway unavailable. No C# action was executed."
                });
            }
        }
    }
}
