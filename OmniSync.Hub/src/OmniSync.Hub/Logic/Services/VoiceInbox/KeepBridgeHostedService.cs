using System.Diagnostics;
using System.IO;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Logic.Services.VoiceInbox;

/// <summary>
/// Keeps OmniSync.Misc\KeepBridge\keep_bridge.py running. It watches the Google Keep list "Omni"
/// ("Hey Google, add ... to my Omni list") and posts new items to /api/voice-inbox.
/// Does nothing until the one-time `keep_bridge.py setup` has created google_keep.json.
/// </summary>
public sealed class KeepBridgeHostedService : BackgroundService
{
    private static readonly TimeSpan StartupDelay = TimeSpan.FromSeconds(15);
    private static readonly TimeSpan CredentialCheckInterval = TimeSpan.FromMinutes(1);
    private readonly HubMonitorService _monitorService;
    private readonly IConfiguration _configuration;
    private readonly ILogger<KeepBridgeHostedService> _logger;

    public KeepBridgeHostedService(HubMonitorService monitorService, IConfiguration configuration, ILogger<KeepBridgeHostedService> logger)
    {
        _monitorService = monitorService;
        _configuration = configuration;
        _logger = logger;
    }

    private static string CredentialsPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniSync", "google_keep.json");

    internal static string? FindRepoRoot()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null && !File.Exists(Path.Combine(dir.FullName, "build_run_omnihub.py"))) dir = dir.Parent;
        return dir?.FullName;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await Task.Delay(StartupDelay, stoppingToken);
        var repo = FindRepoRoot();
        var script = repo is null ? null : Path.Combine(repo, "OmniSync.Misc", "KeepBridge", "keep_bridge.py");
        var python = repo is null ? null : Path.Combine(repo, "artifacts", "keep-venv", "Scripts", "python.exe");
        if (script is null || !File.Exists(script) || !File.Exists(python))
        {
            _logger.LogInformation("[KeepBridge] Script or venv missing; Keep voice inbox disabled.");
            return;
        }

        var backoff = TimeSpan.FromSeconds(10);
        var loggedWaiting = false;
        while (!stoppingToken.IsCancellationRequested)
        {
            if (!File.Exists(CredentialsPath))
            {
                if (!loggedWaiting) _monitorService.AddLogMessage("[KeepBridge] Waiting for Keep credentials (run keep_bridge.py setup).");
                loggedWaiting = true;
                await Task.Delay(CredentialCheckInterval, stoppingToken);
                continue;
            }

            var started = DateTimeOffset.UtcNow;
            await RunBridgeAsync(python!, script, stoppingToken);
            if (stoppingToken.IsCancellationRequested) break;
            backoff = DateTimeOffset.UtcNow - started > TimeSpan.FromMinutes(5) ? TimeSpan.FromSeconds(10) : TimeSpan.FromSeconds(Math.Min(backoff.TotalSeconds * 2, 600));
            _monitorService.AddLogMessage($"[KeepBridge] Bridge exited; restarting in {backoff.TotalSeconds:0}s.");
            await Task.Delay(backoff, stoppingToken);
        }
    }

    private async Task RunBridgeAsync(string python, string script, CancellationToken stoppingToken)
    {
        var psi = new ProcessStartInfo(python)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        psi.ArgumentList.Add("-u");
        psi.ArgumentList.Add(script);
        psi.ArgumentList.Add("run");
        psi.ArgumentList.Add("--parent-pid");
        psi.ArgumentList.Add(Environment.ProcessId.ToString());
        psi.Environment["OMNI_API_KEY"] = _configuration["AuthApiKey"] ?? "test_api_key";

        using var process = new Process { StartInfo = psi };
        process.OutputDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data)) _monitorService.AddLogMessage($"[KeepBridge] {e.Data}"); };
        process.ErrorDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data)) _logger.LogWarning("[KeepBridge] {Line}", e.Data); };
        try
        {
            process.Start();
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            await process.WaitForExitAsync(stoppingToken);
        }
        catch (OperationCanceledException)
        {
            try { process.Kill(entireProcessTree: true); } catch { }
        }
        catch (Exception ex)
        {
            _monitorService.AddLogMessage($"[KeepBridge] Failed to start: {ex.Message}");
        }
    }
}
