using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace OmniSync.Hub.Logic.Services.Gateway;

// Shared on-disk contract with OmniMCP-Lite. The Hub remains the only launcher.
public sealed class GatewayTool
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Description { get; set; } = "";
    public string WorkingDirectory { get; set; } = "";
    public string Executable { get; set; } = "";
    public List<string> Arguments { get; set; } = new();
    public string Url { get; set; } = "";
    public List<GatewayCheck> Checks { get; set; } = new();
    public int StartupTimeoutSeconds { get; set; } = 60;
}

public sealed class GatewayCheck
{
    public string Url { get; set; } = "";
    public string? Contains { get; set; }
}

public sealed class GatewayRegistry
{
    public int Version { get; set; } = 1;
    public List<GatewayTool> Tools { get; set; } = new();
}

public sealed record GatewayToolStatus(
    string Id, string Name, string Description, string Url, string Status, string? Error,
    bool Launching);

public sealed record GatewayLaunchResult(bool Ok, string? Url, string? Error);

public sealed class GatewayToolManager
{
    public static string RegistryPath => Environment.GetEnvironmentVariable("OMNI_GATEWAY_REGISTRY_PATH") ?? Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "OmniSync", "gateway-tools.json");

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };
    private static readonly Regex Slug = new("^[a-z0-9][a-z0-9-]{1,49}$", RegexOptions.Compiled);
    private static readonly HttpClient Client = new(new HttpClientHandler
    {
        AllowAutoRedirect = false,
        UseProxy = false
    }) { Timeout = TimeSpan.FromSeconds(2) };
    // Lazy is essential here: ConcurrentDictionary.GetOrAdd may invoke its value
    // factory more than once. Starting the process inside that factory races.
    private readonly ConcurrentDictionary<string, Lazy<Task<GatewayLaunchResult>>> _launches =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, string> _errors =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, Process> _owned =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly object _logLock = new();

    public GatewayToolManager()
    {
        // Seed only once; later Lite registrations are never overwritten by Hub startup.
        if (!File.Exists(RegistryPath))
        {
            Directory.CreateDirectory(Path.GetDirectoryName(RegistryPath)!);
            var seed = new GatewayRegistry { Tools = Defaults() };
            try
            {
                using var stream = new FileStream(RegistryPath, FileMode.CreateNew, FileAccess.Write);
                JsonSerializer.Serialize(stream, seed, Json);
            }
            catch (IOException) when (File.Exists(RegistryPath)) { }
        }
    }

    internal static List<GatewayTool> Defaults() => new()
    {
        new GatewayTool
        {
            Id = "animation-lab", Name = "Wartribes Animation Lab",
            Description = "Animation authoring, scene library, and quality review",
            WorkingDirectory = @"D:\SSDProjects\Tools\WartribesAnimationLab",
            Executable = "py", Arguments = new() { "launch.py", "--no-browser" },
            Url = "http://127.0.0.1:5178/",
            Checks = new()
            {
                new() { Url = "http://127.0.0.1:5178/", Contains = "<title>Wartribes Animation Lab</title>" },
                new() { Url = "http://127.0.0.1:8000/api/health" }
            }
        },
        new GatewayTool
        {
            Id = "combat-sim", Name = "Wartribes Combat Simulator",
            Description = "Replay and inspect actor-backed combat simulations",
            WorkingDirectory = @"D:\Wartribes\Tools\CombatSimVisualizer",
            Executable = "py", Arguments = new() { "-B", "server.py", "8766" },
            Url = "http://127.0.0.1:8766/",
            Checks = new()
            {
                new() { Url = "http://127.0.0.1:8766/", Contains = "<title>WARTRIBES / Combat Simulation Laboratory</title>" }
            }
        },
        new GatewayTool
        {
            Id = "settling-altraea", Name = "Settling Altraea",
            Description = "Local game, AI tournament and multiplayer server",
            WorkingDirectory = @"D:\SSDProjects\SettlingAltraea",
            Executable = "py", Arguments = new() { "run_server.py" },
            Url = "http://127.0.0.1:5173/",
            Checks = new()
            {
                new() { Url = "http://127.0.0.1:5173/", Contains = "<title>Settling Altraea</title>" },
                new() { Url = "http://127.0.0.1:8787/api/health", Contains = "\"ok\":true" }
            }
        }
    };

    private static Uri CheckedLocalUrl(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)
            || uri.Scheme != Uri.UriSchemeHttp
            || uri.UserInfo.Length != 0
            || uri.Host is not ("localhost" or "127.0.0.1" or "::1")
            || uri.Port < 1024)
            throw new InvalidDataException("Gateway URLs must use http://localhost or 127.0.0.1 with a non-privileged port.");
        return uri;
    }

    public static void Validate(GatewayTool tool)
    {
        if (!Slug.IsMatch(tool.Id)) throw new InvalidDataException("Invalid tool ID (use lowercase letters, digits and hyphens).");
        if (string.IsNullOrWhiteSpace(tool.Name) || tool.Name.Length > 100
            || tool.Description.Length > 500)
            throw new InvalidDataException("Invalid tool name or description.");
        if (string.IsNullOrWhiteSpace(tool.WorkingDirectory) || !Path.IsPathFullyQualified(tool.WorkingDirectory))
            throw new InvalidDataException("Working directory must be an absolute path: " + tool.WorkingDirectory);
        // Projects can be moved/unmounted after registration. Report that tool
        // as offline rather than making the whole Gateway registry unreadable.
        if (string.IsNullOrWhiteSpace(tool.Executable) || tool.Executable.Length > 260 ||
            tool.Arguments.Count > 32 || tool.Arguments.Any(a => a.Length > 1000))
            throw new InvalidDataException("Invalid executable or arguments.");
        if (tool.StartupTimeoutSeconds is < 5 or > 180)
            throw new InvalidDataException("Startup timeout must be between 5 and 180 seconds.");
        CheckedLocalUrl(tool.Url);
        if (tool.Checks.Count is < 1 or > 8) throw new InvalidDataException("One to eight health checks required.");
        foreach (var check in tool.Checks)
        {
            CheckedLocalUrl(check.Url);
            if (check.Contains is { Length: > 250 })
                throw new InvalidDataException("Health check response marker too long.");
        }
    }

    public IReadOnlyList<GatewayTool> GetTools()
    {
        try
        {
            using var stream = new FileStream(RegistryPath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            var registry = JsonSerializer.Deserialize<GatewayRegistry>(stream, Json)
                ?? throw new InvalidDataException("Empty registry.");
            if (registry.Version != 1 || registry.Tools is null || registry.Tools.Count > 200)
                throw new InvalidDataException("Unsupported registry version or excessive tool count.");
            foreach (var tool in registry.Tools) Validate(tool);
            if (registry.Tools.Select(t => t.Id).Distinct(StringComparer.OrdinalIgnoreCase).Count() != registry.Tools.Count)
                throw new InvalidDataException("Duplicate gateway IDs.");
            return registry.Tools;
        }
        catch (Exception ex) when (ex is IOException or JsonException or InvalidDataException or ArgumentException)
        {
            throw new InvalidDataException("Invalid OmniHub gateway registry: " + ex.Message, ex);
        }
    }

    public GatewayTool? Find(string id) =>
        GetTools().FirstOrDefault(t => t.Id.Equals(id, StringComparison.OrdinalIgnoreCase));

    private static async Task<bool> Probe(GatewayCheck check)
    {
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
            using var request = new HttpRequestMessage(HttpMethod.Get, CheckedLocalUrl(check.Url));
            using var response = await Client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
            if (!response.IsSuccessStatusCode) return false;
            if (string.IsNullOrEmpty(check.Contains)) return true;
            using var stream = await response.Content.ReadAsStreamAsync(timeout.Token);
            using var reader = new StreamReader(stream);
            // ReadAsync (not ReadBlockAsync) handles chunked/long-lived responses.
            // Always bound both bytes and time, including after HTTP headers arrive.
            var buffer = new char[4096];
            string carry = "";
            int read = 0;
            while (read < 128 * 1024)
            {
                var count = await reader.ReadAsync(buffer.AsMemory(0, Math.Min(buffer.Length, 128 * 1024 - read)), timeout.Token);
                if (count == 0) return false;
                read += count;
                string chunk = carry + new string(buffer, 0, count);
                if (chunk.Contains(check.Contains, StringComparison.OrdinalIgnoreCase)) return true;
                carry = chunk.Length > check.Contains.Length - 1
                    ? chunk[^Math.Max(0, check.Contains.Length - 1)..]
                    : chunk;
            }
            return false;
        }
        catch (Exception ex) when (ex is HttpRequestException or IOException or TaskCanceledException or InvalidDataException)
        {
            return false;
        }
    }

    private static async Task<bool> Healthy(GatewayTool tool)
    {
        var checks = await Task.WhenAll(tool.Checks.Select(Probe));
        return checks.All(x => x);
    }

    public async Task<IReadOnlyList<GatewayToolStatus>> StatusAsync()
    {
        var tools = GetTools();
        return await Task.WhenAll(tools.Select(async t =>
        {
            var launching = _launches.TryGetValue(t.Id, out var pending) &&
                (!pending.IsValueCreated || !pending.Value.IsCompleted);
            var running = await Healthy(t);
            _errors.TryGetValue(t.Id, out var error);
            if (!running && !Directory.Exists(t.WorkingDirectory))
                error = "Registered working directory is missing: " + t.WorkingDirectory;
            return new GatewayToolStatus(t.Id, t.Name, t.Description, t.Url,
                running ? "running" : launching ? "starting" : error is not null ? "error" : "stopped",
                running ? null : error, launching);
        }));
    }

    public async Task<GatewayLaunchResult> LaunchAsync(string id)
    {
        GatewayTool? tool = Find(id);
        if (tool is null) return new(false, null, "Tool not registered.");
        if (await Healthy(tool)) return new(true, tool.Url, null);

        var flight = _launches.GetOrAdd(tool.Id, _ => new Lazy<Task<GatewayLaunchResult>>(
            () => StartAndWaitAsync(tool), LazyThreadSafetyMode.ExecutionAndPublication));
        try { return await flight.Value; }
        finally
        {
            // Remove exactly this flight; a later caller can safely retry after failure.
            if (flight.IsValueCreated && flight.Value.IsCompleted)
                ((ICollection<KeyValuePair<string, Lazy<Task<GatewayLaunchResult>>>>)_launches)
                    .Remove(new KeyValuePair<string, Lazy<Task<GatewayLaunchResult>>>(tool.Id, flight));
        }
    }

    private async Task<GatewayLaunchResult> StartAndWaitAsync(GatewayTool tool)
    {
        _errors.TryRemove(tool.Id, out _);
        try
        {
            // Recheck after single-flight admission to avoid duplicate starts.
            if (await Healthy(tool)) return new(true, tool.Url, null);
            if (!Directory.Exists(tool.WorkingDirectory))
                throw new DirectoryNotFoundException("Registered working directory no longer exists: " + tool.WorkingDirectory);
            var start = new ProcessStartInfo(tool.Executable)
            {
                WorkingDirectory = tool.WorkingDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            foreach (var arg in tool.Arguments) start.ArgumentList.Add(arg);
            var process = new Process { StartInfo = start, EnableRaisingEvents = true };
            process.OutputDataReceived += (_, e) => Log(tool.Id, e.Data);
            process.ErrorDataReceived += (_, e) => Log(tool.Id, e.Data);
            if (!process.Start()) throw new InvalidOperationException("Process did not start.");
            _owned[tool.Id] = process;
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            Log(tool.Id, $"Started {tool.Name} (PID {process.Id}).");
            var deadline = Stopwatch.StartNew();
            while (deadline.Elapsed.TotalSeconds < tool.StartupTimeoutSeconds)
            {
                if (await Healthy(tool)) return new(true, tool.Url, null);
                if (process.HasExited && process.ExitCode != 0)
                    throw new InvalidOperationException($"Launcher exited with code {process.ExitCode}. See gateway log.");
                // Some launchers (e.g. Altraea) spawn detached servers and exit successfully.
                await Task.Delay(500);
            }
            throw new TimeoutException($"Timed out after {tool.StartupTimeoutSeconds}s. See gateway log.");
        }
        catch (Exception ex) when (ex is not OutOfMemoryException)
        {
            var message = ex.Message;
            _errors[tool.Id] = message;
            Log(tool.Id, "ERROR: " + message);
            return new(false, null, message);
        }
    }

    private void Log(string id, string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return;
        try
        {
            lock (_logLock)
            {
                var dir = Path.Combine(Path.GetDirectoryName(RegistryPath)!, "gateway-logs");
                Directory.CreateDirectory(dir);
                var path = Path.Combine(dir, id + ".log");
                if (File.Exists(path) && new FileInfo(path).Length > 2 * 1024 * 1024)
                    File.Move(path, path + ".old", true);
                File.AppendAllText(path, $"[{DateTimeOffset.Now:O}] {line}{Environment.NewLine}");
            }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { } // logging must never interrupt server management
    }

    public string ReadLog(string id)
    {
        if (Find(id) is null) throw new KeyNotFoundException(id);
        var path = Path.Combine(Path.GetDirectoryName(RegistryPath)!, "gateway-logs", id + ".log");
        if (!File.Exists(path)) return "No launch log yet.";
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
        stream.Seek(Math.Max(0, stream.Length - 16_000), SeekOrigin.Begin);
        using var reader = new StreamReader(stream);
        return reader.ReadToEnd();
    }
}
