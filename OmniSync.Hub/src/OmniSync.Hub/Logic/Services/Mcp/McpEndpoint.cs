using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.AspNetCore.Http;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Logic.Services.Mcp
{
    /// DEPRECATED: legacy OmniHub-hosted MCP endpoint. Replaced by the independent
    /// D:\\SSDProjects\\Tools\\McpHelloProbe OmniMCP-Lite service (port 8771).
    /// Keep disabled (Mcp.Enabled=false) until deliberate eventual removal.
    /// Do not add new tools or re-enable port 8770; implement new capabilities in Lite.
    [Obsolete("Legacy OmniHub MCP server is deprecated. Use standalone OmniMCP-Lite; do not re-enable port 8770.")]
    public sealed class McpEndpoint
    {
        private static readonly string[] SupportedVersions = { "2025-11-25", "2025-06-18", "2025-03-26" };
        private const string FallbackVersion = "2025-06-18";

        private readonly HubMonitorService _monitor;
        private readonly McpTools _tools;
        private readonly McpDesktopTools _desktopTools;
        private readonly McpWindowTools _windowTools;

        // In-flight requests keyed by JSON-RPC id, so notifications/cancelled can stop them.
        private readonly ConcurrentDictionary<string, CancellationTokenSource> _inFlight = new();

        public McpEndpoint(HubMonitorService monitor, McpTools tools)
        {
            _monitor = monitor;
            _tools = tools;
            _desktopTools = new McpDesktopTools(new UiInspector(), new VirtualDesktopService());
            _windowTools = new McpWindowTools();
        }

        public async Task HandleAsync(HttpContext context)
        {
            var ct = context.RequestAborted;
            var path = (context.Request.Path.Value ?? "").TrimEnd('/');
            if (path.Length != 0 && !path.Equals("/mcp", StringComparison.OrdinalIgnoreCase))
            {
                context.Response.StatusCode = StatusCodes.Status404NotFound;
                return;
            }

            if (HttpMethods.IsDelete(context.Request.Method))
            {
                context.Response.StatusCode = StatusCodes.Status200OK; // no sessions to tear down
                return;
            }
            if (!HttpMethods.IsPost(context.Request.Method))
            {
                context.Response.Headers["Allow"] = "POST, DELETE";
                context.Response.StatusCode = StatusCodes.Status405MethodNotAllowed;
                return;
            }

            JsonNode? body;
            try
            {
                body = await JsonNode.ParseAsync(context.Request.Body, cancellationToken: ct);
            }
            catch (JsonException)
            {
                await WriteJsonAsync(context, ErrorResponse(null, -32700, "Parse error"), StatusCodes.Status400BadRequest);
                return;
            }

            switch (body)
            {
                case JsonArray { Count: 0 }:
                    await WriteJsonAsync(context, ErrorResponse(null, -32600, "Empty batch"), StatusCodes.Status400BadRequest);
                    return;
                case JsonArray batch:
                {
                    var replies = new JsonArray();
                    foreach (var item in batch)
                    {
                        var reply = await ProcessMessageAsync(item, ct);
                        if (reply != null) replies.Add(reply);
                    }
                    await WriteReplyAsync(context, replies.Count == 0 ? null : replies);
                    return;
                }
                default:
                    await WriteReplyAsync(context, await ProcessMessageAsync(body, ct));
                    return;
            }
        }

        /// Returns the JSON-RPC reply, or null for notifications and client responses (HTTP 202).
        private async Task<JsonNode?> ProcessMessageAsync(JsonNode? message, CancellationToken ct)
        {
            if (message is not JsonObject obj) return ErrorResponse(null, -32600, "Invalid request");

            bool hasId = obj.TryGetPropertyValue("id", out var idNode);
            var id = idNode?.DeepClone();
            string? method = obj["method"] is JsonValue m && m.TryGetValue<string>(out var name) ? name : null;
            var parameters = obj["params"] as JsonObject;

            if (method == null)
            {
                bool isClientResponse = obj.ContainsKey("result") || obj.ContainsKey("error");
                return isClientResponse ? null : ErrorResponse(id, -32600, "Missing method");
            }

            if (!hasId)
            {
                HandleNotification(method, parameters);
                return null;
            }

            var key = idNode?.ToJsonString() ?? "null";
            using var requestCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            _inFlight[key] = requestCts;
            try
            {
                var result = await DispatchAsync(method, parameters, requestCts.Token);
                return ResultResponse(id, result);
            }
            catch (McpRpcException ex)
            {
                return ErrorResponse(id, ex.Code, ex.Message);
            }
            catch (OperationCanceledException) when (requestCts.IsCancellationRequested)
            {
                return ErrorResponse(id, -32800, "Request cancelled");
            }
            finally
            {
                _inFlight.TryRemove(key, out _);
            }
        }

        private void HandleNotification(string method, JsonObject? parameters)
        {
            if (method == "notifications/cancelled" && parameters?["requestId"] is JsonNode requestId
                && _inFlight.TryGetValue(requestId.ToJsonString(), out var cts))
            {
                try { cts.Cancel(); }
                catch (ObjectDisposedException) { } // request finished between lookup and cancel
            }
            // notifications/initialized and anything else need no action.
        }

        private async Task<JsonNode> DispatchAsync(string method, JsonObject? parameters, CancellationToken ct)
        {
            switch (method)
            {
                case "initialize":
                    return Initialize(parameters);
                case "ping":
                    return new JsonObject();
                case "tools/list":
                    // JsonNodes already belong to their source array, so clone before adding them to the combined list.
                    var allTools = new JsonArray();
                    foreach (var tool in _tools.DescribeTools().Concat(_desktopTools.DescribeTools()).Concat(_windowTools.DescribeTools())) allTools.Add(tool!.DeepClone());
                    return new JsonObject { ["tools"] = allTools };
                case "tools/call":
                    return await CallToolAsync(parameters, ct);
                default:
                    throw new McpRpcException(-32601, $"Method not found: {method}");
            }
        }

        private static JsonObject Initialize(JsonObject? parameters)
        {
            var requested = parameters?["protocolVersion"] is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;
            var version = requested != null && Array.IndexOf(SupportedVersions, requested) >= 0 ? requested : FallbackVersion;
            return new JsonObject
            {
                ["protocolVersion"] = version,
                ["capabilities"] = new JsonObject { ["tools"] = new JsonObject { ["listChanged"] = false } },
                ["serverInfo"] = new JsonObject { ["name"] = "omnihub", ["version"] = "1.0.0" },
                ["instructions"] = "Full access to this Windows PC: shell commands, background processes, file read/write/edit/move/delete, search, and processes.",
            };
        }

        private async Task<JsonNode> CallToolAsync(JsonObject? parameters, CancellationToken ct)
        {
            var name = parameters?["name"] is JsonValue n && n.TryGetValue<string>(out var s) ? s : null;
            if (string.IsNullOrEmpty(name)) throw new McpRpcException(-32602, "tools/call requires a tool name.");
            bool isDesktopTool = _desktopTools.Contains(name);
            bool isWindowTool = _windowTools.Contains(name);
            if (!isDesktopTool && !isWindowTool && !_tools.Contains(name)) throw new McpRpcException(-32602, $"Unknown tool: {name}");
            var arguments = parameters?["arguments"] as JsonObject ?? new JsonObject();

            var sw = Stopwatch.StartNew();
            McpToolResult result = new("Operation did not return", true);
            McpWartribesGuard? guard = null;
            try
            {
                guard = await McpWartribesGuard.BeginAsync(name, arguments, ct);
                result = isDesktopTool
                    ? await _desktopTools.InvokeAsync(name, arguments, ct)
                    : isWindowTool
                        ? await _windowTools.InvokeAsync(name, arguments, ct)
                        : await _tools.InvokeAsync(name, arguments, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                result = new McpToolResult(ex.Message, true);
            }
            finally
            {
                if (guard is not null)
                {
                    try { await guard.FinishAsync(result.IsError || ct.IsCancellationRequested); }
                    catch (Exception ex) { result = new McpToolResult("Coordination completion failed: " + ex.Message, true); }
                }
            }
            _monitor.AddLogMessage($"[MCP] {name} {(result.IsError ? "failed" : "ok")} in {sw.ElapsedMilliseconds} ms");

            var content = new JsonArray(new JsonObject { ["type"] = "text", ["text"] = result.Text });
            if (result.Image != null)
            {
                content.Add(new JsonObject
                {
                    ["type"] = "image",
                    ["data"] = Convert.ToBase64String(result.Image),
                    ["mimeType"] = result.ImageMime,
                });
            }
            return new JsonObject { ["content"] = content, ["isError"] = result.IsError };
        }

        private static JsonObject ResultResponse(JsonNode? id, JsonNode result) =>
            new() { ["jsonrpc"] = "2.0", ["id"] = id, ["result"] = result };

        private static JsonObject ErrorResponse(JsonNode? id, int code, string message) =>
            new() { ["jsonrpc"] = "2.0", ["id"] = id, ["error"] = new JsonObject { ["code"] = code, ["message"] = message } };

        private static async Task WriteReplyAsync(HttpContext context, JsonNode? reply)
        {
            if (reply == null)
            {
                context.Response.StatusCode = StatusCodes.Status202Accepted;
                return;
            }
            await WriteJsonAsync(context, reply, StatusCodes.Status200OK);
        }

        private static async Task WriteJsonAsync(HttpContext context, JsonNode node, int status)
        {
            context.Response.StatusCode = status;
            context.Response.ContentType = "application/json";
            await context.Response.WriteAsync(node.ToJsonString());
        }
    }

    internal sealed class McpRpcException : Exception
    {
        public McpRpcException(int code, string message) : base(message) => Code = code;
        public int Code { get; }
    }
}
