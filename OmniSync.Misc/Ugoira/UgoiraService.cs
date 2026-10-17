using System.Diagnostics;
using System.IO;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Logic.Services;

public sealed class UgoiraService : IHostedService
{
    private readonly object _gate = new();
    private readonly CancellationTokenSource _stopping = new();
    private readonly HubMonitorService _monitor;
    private readonly ILogger<UgoiraService> _logger;
    private Task _job = Task.CompletedTask;
    private bool _running;
    private string _summary = "Ready.";
    private string? _output;
    private string? _jobId;
    private List<JsonObject> _items = new();
    private long _polls;
    private readonly Queue<string> _recent = new();

    public string ContentDirectory { get; } = Path.Combine(AppContext.BaseDirectory, "Misc", "Ugoira");

    private static string StateFile { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OmniSync", "ugoira-last-output.txt");

    public UgoiraService(HubMonitorService monitor, ILogger<UgoiraService> logger)
    {
        _monitor = monitor;
        _logger = logger;
        _output = Recall();
    }

    public void Log(string message)
    {
        lock (_gate)
        {
            _recent.Enqueue($"{DateTime.Now:HH:mm:ss} [PID {Environment.ProcessId}] {message}");
            while (_recent.Count > 400) _recent.Dequeue();
        }
        _logger.LogInformation("[Ugoira] {Message}", message);
        _monitor.AddLogMessage($"[Ugoira] {message}");
    }

    public IReadOnlyList<string> RecentLog()
    {
        lock (_gate) return _recent.ToArray();
    }

    public string? LastOutput
    {
        get { lock (_gate) return _output; }
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        Log($"Backend ready in OmniHub PID {Environment.ProcessId}; assets={ContentDirectory}; page=/misc/ugoira/ugoira.html");
        return Task.CompletedTask;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        Log($"Hub shutdown; cancelling download worker; active={_running}; job={_jobId}");
        _stopping.Cancel();
        await _job.WaitAsync(cancellationToken);
    }

    public JsonElement Snapshot()
    {
        lock (_gate)
        {
            if (_output != null && !_running && !Directory.Exists(_output))
            {
                Log($"Forgetting the output folder {_output}; it no longer exists.");
                _output = null;
            }
            if (++_polls % 10 == 1)
                Log($"Status poll {_polls}; running={_running}; job={_jobId}; items={_items.Count}");
            return JsonSerializer.SerializeToElement(new { running = _running, items = _items, summary = _summary, output = _output, jobId = _jobId });
        }
    }

    public bool TryStart(string text, string output)
    {
        var lines = text.Split('\n').Select(line => line.Trim()).Where(line => line.Length > 0 && !line.StartsWith('#')).ToArray();
        if (lines.Length == 0) throw new ArgumentException("Paste at least one Pixiv artwork link.");
        if (!Path.IsPathFullyQualified(output)) throw new ArgumentException("Choose an absolute download folder path.");
        output = Path.GetFullPath(output);
        lock (_gate)
        {
            if (_running) return false;
            Directory.CreateDirectory(output);
            _running = true;
            _output = output;
            _jobId = Guid.NewGuid().ToString("N")[..8];
            _summary = $"Processing {lines.Length} links...";
            _items = lines.Select(line => new JsonObject { ["link"] = line, ["status"] = "Waiting", ["detail"] = "" }).ToList();
            Log($"Batch accepted; job={_jobId}; count={lines.Length}; output={output}");
            Remember(output);
            _job = Task.Run(() => RunAsync(lines, output));
            return true;
        }
    }

    private void Remember(string output)
    {
        var temporary = Path.GetFullPath(Path.GetTempPath());
        if (output.StartsWith(temporary, StringComparison.OrdinalIgnoreCase))
        {
            Log($"Not remembering the temporary folder {output}.");
            return;
        }
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(StateFile)!);
            File.WriteAllText(StateFile, output);
        }
        catch (Exception ex) { Log($"Could not remember the output folder: {ex.Message}"); }
    }

    private string? Recall()
    {
        try
        {
            if (!File.Exists(StateFile)) return null;
            var path = File.ReadAllText(StateFile).Trim();
            return Directory.Exists(path) ? path : null;
        }
        catch (Exception ex)
        {
            Log($"Could not read the remembered output folder: {ex.Message}");
            return null;
        }
    }

    public string? VideoPath(string fileName)
    {
        lock (_gate)
        {
            if (_output == null || !_items.Any(item => item["video"]?.GetValue<string>() == $"/api/ugoira/videos/{fileName}")) return null;
            return Path.Combine(_output, fileName);
        }
    }

    private async Task RunAsync(string[] lines, string output)
    {
        bool completed = false;
        try
        {
            var info = new ProcessStartInfo("python")
            {
                WorkingDirectory = ContentDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };
            info.ArgumentList.Add("-u");
            info.ArgumentList.Add("-B");
            info.ArgumentList.Add(Path.Combine(ContentDirectory, "worker.py"));
            info.Environment["PYTHONIOENCODING"] = "utf-8";
            Log($"Starting Python worker; job={_jobId}; cwd={ContentDirectory}");
            using var process = Process.Start(info) ?? throw new InvalidOperationException("Could not start Python.");
            Log($"Worker started; job={_jobId}; pid={process.Id}");
            using var cancellation = _stopping.Token.Register(() =>
            {
                try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { }
            });
            var stderr = Task.Run(async () =>
            {
                while (await process.StandardError.ReadLineAsync() is { } line)
                    Log($"Worker {_jobId} stderr: {line}");
            });
            await process.StandardInput.WriteLineAsync(JsonSerializer.Serialize(new { lines, output }));
            process.StandardInput.Close();
            while (await process.StandardOutput.ReadLineAsync() is { } line)
            {
                Log($"Worker {_jobId} progress: {line}");
                var progress = JsonNode.Parse(line)!.AsObject();
                lock (_gate)
                {
                    if (progress["type"]?.GetValue<string>() == "done")
                    {
                        _summary = progress["summary"]!.GetValue<string>();
                        completed = true;
                    }
                    else
                    {
                        int index = progress["index"]!.GetValue<int>();
                        foreach (var (key, value) in progress)
                            if (key != "type" && key != "index") _items[index][key] = value?.DeepClone();
                    }
                }
            }
            await process.WaitForExitAsync();
            await stderr;
            Log($"Worker exited; job={_jobId}; pid={process.Id}; exit={process.ExitCode}; completed={completed}");
            if (process.ExitCode != 0 || !completed)
                throw new InvalidOperationException($"Download worker stopped (exit {process.ExitCode}). See the Hub activity log for details.");
        }
        catch (Exception ex)
        {
            Log($"Batch {_jobId} ERROR: {ex}");
            lock (_gate)
            {
                _summary = _stopping.IsCancellationRequested ? "Downloads stopped with OmniHub." : ex.Message;
                foreach (var item in _items.Where(item => item["status"]?.GetValue<string>() is "Waiting" or "Working"))
                {
                    item["status"] = "Failed";
                    item["detail"] = _summary;
                }
            }
        }
        finally
        {
            lock (_gate) _running = false;
            Log($"Batch {_jobId} closed: {_summary}");
        }
    }
}
