using System;
using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace OmniSync.Hub.Logic.Services.Mcp;

/// <summary>
/// Operation-scoped Wartribes coordination for remote ChatGPT callers.
/// No assumption that an MCP connection represents a ChatGPT conversation.
/// Only paths/commands explicitly touching D:\Wartribes are intercepted.
/// </summary>
internal sealed class McpWartribesGuard
{
    private const string Root = @"D:\Wartribes";
    private const string Script = @"D:\Wartribes\Tools\coordination\omnimcp_guard.py";
    private readonly string _id;

    private McpWartribesGuard(string id) => _id = id;

    private static bool Relevant(string name, JsonObject args)
    {
        if (name is "read_file" or "list_directory" or "search_files" or "find_paths"
            or "read_process_output" or "list_processes" or "get_process_info")
            return false;
        foreach (var key in new[] { "cwd", "path", "source", "destination", "command" })
        {
            var value = args[key]?.ToString() ?? "";
            if (value.Replace('/', '\\').Contains(Root, StringComparison.OrdinalIgnoreCase))
                return true;
        }
        return false;
    }

    public static async Task<McpWartribesGuard?> BeginAsync(
        string name, JsonObject arguments, CancellationToken cancellationToken)
    {
        if (!Relevant(name, arguments)) return null;
        var id = Guid.NewGuid().ToString("N");
        var reply = await InvokeBridgeAsync(new JsonObject
        {
            ["phase"] = "begin", ["id"] = id, ["tool"] = name,
            ["arguments"] = arguments.DeepClone(),
        }, cancellationToken);
        if (reply["guard"]?.ToString() == "skip") return null;
        if (reply["guard"]?.ToString() != "registered")
            throw new InvalidOperationException("Wartribes coordination denied: " + reply.ToJsonString());
        return new McpWartribesGuard(id);
    }

    public async Task FinishAsync(bool failed)
    {
        var reply = await InvokeBridgeAsync(new JsonObject
        {
            ["phase"] = "end", ["id"] = _id, ["failed"] = failed
        }, CancellationToken.None);
        if (reply["guard"]?.ToString() != "completed")
            throw new InvalidOperationException("Wartribes coordination completion failed: " + reply.ToJsonString());
    }

    private static async Task<JsonNode> InvokeBridgeAsync(JsonObject payload, CancellationToken cancellationToken)
    {
        if (!File.Exists(Script))
            throw new FileNotFoundException("Wartribes coordinator is unavailable; refusing scoped write", Script);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(15));
        var python = File.Exists(@"C:\Program Files\Python313\python.exe")
            ? @"C:\Program Files\Python313\python.exe" : "python";
        var start = new ProcessStartInfo(python)
        {
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            UseShellExecute = false, CreateNoWindow = true,
        };
        start.ArgumentList.Add("-B");
        start.ArgumentList.Add(Script);
        using var process = Process.Start(start) ?? throw new InvalidOperationException("Cannot start coordinator");
        try
        {
            await process.StandardInput.WriteAsync(payload.ToJsonString());
            process.StandardInput.Close();
            var output = process.StandardOutput.ReadToEndAsync(deadline.Token);
            var errors = process.StandardError.ReadToEndAsync(deadline.Token);
            await process.WaitForExitAsync(deadline.Token);
            var stdout = await output;
            var stderr = await errors;
            if (process.ExitCode != 0)
                throw new InvalidOperationException(
                    "Wartribes coordination rejected operation: " + (stdout + " " + stderr).Trim());
            return JsonNode.Parse(stdout) ?? throw new InvalidOperationException("Empty coordination response");
        }
        catch
        {
            if (!process.HasExited)
                try { process.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
            throw;
        }
    }
}
