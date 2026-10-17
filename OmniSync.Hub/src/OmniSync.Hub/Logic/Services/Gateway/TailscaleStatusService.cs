using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace OmniSync.Hub.Logic.Services.Gateway;

// Read-only view of this PC's Tailscale node: its DNS name, hosted Serve/Funnel URLs
// and online peers. Only fixed `tailscale status|serve status --json` commands run;
// nothing from the browser reaches the process arguments.
public static class TailscaleStatusService
{
    private static readonly string[] Candidates =
    {
        @"C:\Program Files\Tailscale\tailscale.exe",
        "tailscale.exe",
    };
    private static readonly TimeSpan CacheTime = TimeSpan.FromSeconds(10);
    private static readonly SemaphoreSlim Gate = new(1, 1);
    private static object? _cached;
    private static DateTime _cachedAt = DateTime.MinValue;

    public static async Task<object> SnapshotAsync()
    {
        await Gate.WaitAsync();
        try
        {
            if (_cached is not null && DateTime.UtcNow - _cachedAt < CacheTime) return _cached;
            _cached = await BuildAsync();
            _cachedAt = DateTime.UtcNow;
            return _cached;
        }
        finally { Gate.Release(); }
    }

    private static async Task<object> BuildAsync()
    {
        var exe = Candidates.FirstOrDefault(File.Exists) ?? Candidates[^1];
        var status = await RunJsonAsync(exe, "status", "--json");
        if (status is null)
            return new { available = false, error = "Tailscale is not installed or not responding." };

        var self = status.Value.TryGetProperty("Self", out var s) ? s : default;
        var serve = await RunJsonAsync(exe, "serve", "status", "--json");
        var funnel = await RunJsonAsync(exe, "funnel", "status", "--json");

        var urls = ReadServe(serve, funnel);
        var devices = ReadDevices(status.Value);

        return new
        {
            available = true,
            backend = Str(status.Value, "BackendState"),
            self = new
            {
                name = Trim(Str(self, "HostName")),
                dns = Trim(Str(self, "DNSName")),
                ip = FirstIPv4(self),
                online = Bool(self, "Online"),
            },
            urls,
            devices,
        };
    }

    private static List<object> ReadServe(JsonElement? serve, JsonElement? funnel)
    {
        var result = new List<object>();
        if (serve is null || !serve.Value.TryGetProperty("Web", out var web)) return result;
        var allowFunnel = funnel is not null && funnel.Value.TryGetProperty("AllowFunnel", out var af)
            ? af
            : default;
        foreach (var host in web.EnumerateObject())
        {
            // Host keys look like "name.tailnet.ts.net:443"; the default port is implied.
            var colon = host.Name.LastIndexOf(':');
            var name = colon > 0 ? host.Name[..colon] : host.Name;
            var port = colon > 0 ? host.Name[(colon + 1)..] : "443";
            var url = port == "443" ? $"https://{name}" : $"https://{name}:{port}";
            var isFunnel = allowFunnel.ValueKind == JsonValueKind.Object &&
                           allowFunnel.TryGetProperty(host.Name, out var on) && on.ValueKind == JsonValueKind.True;
            var routes = new List<object>();
            if (host.Value.TryGetProperty("Handlers", out var handlers))
                foreach (var h in handlers.EnumerateObject())
                    routes.Add(new { path = h.Name, target = Str(h.Value, "Proxy") });
            result.Add(new { url, funnel = isFunnel, routes });
        }
        return result;
    }

    private sealed record Device(string name, string dns, string ip, string os, bool online);

    private static List<object> ReadDevices(JsonElement status)
    {
        var devices = new List<Device>();
        if (!status.TryGetProperty("Peer", out var peers)) return new List<object>();
        foreach (var p in peers.EnumerateObject())
        {
            var host = Str(p.Value, "HostName");
            // Funnel ingress relays are infrastructure, not devices the user owns.
            if (host == "funnel-ingress-node") continue;
            devices.Add(new Device(host, Trim(Str(p.Value, "DNSName")), FirstIPv4(p.Value), Str(p.Value, "OS"), Bool(p.Value, "Online")));
        }
        return devices
            .OrderByDescending(d => d.online).ThenBy(d => d.name, StringComparer.OrdinalIgnoreCase)
            .Cast<object>().ToList();
    }

    private static async Task<JsonElement?> RunJsonAsync(string exe, params string[] args)
    {
        try
        {
            var start = new ProcessStartInfo(exe)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            foreach (var a in args) start.ArgumentList.Add(a);
            using var process = Process.Start(start);
            if (process is null) return null;
            var stdout = process.StandardOutput.ReadToEndAsync();
            var exited = process.WaitForExitAsync();
            if (await Task.WhenAny(exited, Task.Delay(TimeSpan.FromSeconds(5))) != exited)
            {
                try { process.Kill(entireProcessTree: true); } catch { }
                return null;
            }
            var text = await stdout;
            if (process.ExitCode != 0 || string.IsNullOrWhiteSpace(text)) return null;
            using var doc = JsonDocument.Parse(text);
            return doc.RootElement.Clone();
        }
        catch (Exception) { return null; }
    }

    private static string Str(JsonElement e, string name) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() ?? "" : "";

    private static bool Bool(JsonElement e, string name) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.True;

    private static string Trim(string dns) => dns.TrimEnd('.');

    private static string FirstIPv4(JsonElement e)
    {
        if (e.ValueKind != JsonValueKind.Object || !e.TryGetProperty("TailscaleIPs", out var ips)) return "";
        foreach (var ip in ips.EnumerateArray())
        {
            var text = ip.GetString() ?? "";
            if (text.Contains('.')) return text;
        }
        return "";
    }
}
