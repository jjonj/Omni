using System.Diagnostics;
using System.Net.Http;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic.Monitoring;

/// <summary>
/// Supervises the independent OmniMCP-Lite server, not the deprecated Hub MCP endpoint.
/// Task Scheduler remains responsible for launching the versioned executable at logon;
/// the Hub repairs failures after Task Scheduler's own limited restart attempts are exhausted.
/// </summary>
public sealed class OmniMcpLiteWatchdogService : BackgroundService
{
    private const string TaskName = "OmniMCP-Lite";
    private static readonly Uri HealthUri = new("http://127.0.0.1:8771/health");
    private static readonly TimeSpan PollInterval = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan HealthTimeout = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan CommandTimeout = TimeSpan.FromSeconds(15);

    private readonly ILogger<OmniMcpLiteWatchdogService> _logger;
    private readonly HubMonitorService _monitor;
    private readonly bool _enabled;
    private readonly HttpClient _client = new();
    private readonly OmniMcpLiteWatchdogState _state = new();

    public OmniMcpLiteWatchdogService(
        ILogger<OmniMcpLiteWatchdogService> logger,
        IConfiguration configuration,
        HubMonitorService monitor)
    {
        _logger = logger;
        _monitor = monitor;
        _enabled = configuration.GetValue("OmniMcpLiteWatchdog:Enabled", true);
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!_enabled)
        {
            _logger.LogInformation("[OmniMCP-Lite watchdog] Disabled in configuration.");
            return;
        }

        _logger.LogInformation(
            "[OmniMCP-Lite watchdog] Watching {Uri} every {Interval}s; restart task {TaskName} after {Threshold} failures.",
            HealthUri, PollInterval.TotalSeconds, TaskName, OmniMcpLiteWatchdogState.FailureThreshold);

        try
        {
            // Check immediately, without waiting for the first timer tick.
            await CheckAsync(stoppingToken);
            using var timer = new PeriodicTimer(PollInterval);
            while (await timer.WaitForNextTickAsync(stoppingToken))
                await CheckAsync(stoppingToken);
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // Normal Hub shutdown. Leave the independent Lite server running.
        }
    }

    private async Task CheckAsync(CancellationToken cancellationToken)
    {
        try
        {
            bool healthy = await IsHealthyAsync(cancellationToken);
            var decision = _state.Observe(healthy, DateTimeOffset.UtcNow);

            if (decision.Recovered)
            {
                _logger.LogInformation("[OmniMCP-Lite watchdog] Server recovered at {Uri}.", HealthUri);
                _monitor.AddLogMessage("[OmniMCP-Lite] Health restored.");
            }
            else if (decision.FirstFailure)
            {
                _logger.LogWarning("[OmniMCP-Lite watchdog] Health probe failed; confirming before restarting.");
            }

            if (!decision.Restart) return;

            _logger.LogWarning(
                "[OmniMCP-Lite watchdog] Unhealthy for {Failures} checks; restarting scheduled task {TaskName}.",
                _state.ConsecutiveFailures, TaskName);
            _monitor.AddLogMessage("[OmniMCP-Lite] Unhealthy; watchdog is restarting its scheduled task.");
            await RestartScheduledTaskAsync(cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            // A failed recovery must not terminate the Hub or disable future checks.
            _logger.LogError(ex, "[OmniMCP-Lite watchdog] Probe/recovery failed; will retry after cooldown.");
        }
    }

    private async Task<bool> IsHealthyAsync(CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(HealthTimeout);
        try
        {
            using var response = await _client.GetAsync(
                HealthUri, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
            if (!response.IsSuccessStatusCode) return false;
            return (await response.Content.ReadAsStringAsync(timeout.Token)).Trim() == "ok";
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return false; // Timed out.
        }
        catch (HttpRequestException)
        {
            return false;
        }
    }

    private async Task RestartScheduledTaskAsync(CancellationToken cancellationToken)
    {
        // /End is harmless if the task already stopped. It is needed if Lite is still alive
        // but hung; /Run alone would be ignored by Task Scheduler (MultipleInstances=IgnoreNew).
        var end = await ExecuteSchtasksAsync("/End", cancellationToken);
        if (end.ExitCode == 0)
            await Task.Delay(TimeSpan.FromSeconds(1), cancellationToken);

        var run = await ExecuteSchtasksAsync("/Run", cancellationToken);
        if (run.ExitCode == 0)
        {
            _logger.LogInformation("[OmniMCP-Lite watchdog] Task Scheduler accepted the restart.");
            return;
        }

        // Deliberately do not launch a hardcoded .exe: the scheduled task is authoritative
        // for the currently published Lite version, elevation and single-instance policy.
        _logger.LogError(
            "[OmniMCP-Lite watchdog] Could not start task {TaskName} (exit {Code}): {Output}",
            TaskName, run.ExitCode, run.Output);
        _monitor.AddLogMessage("[OmniMCP-Lite] Restart failed; check scheduled task OmniMCP-Lite.");
    }

    private static async Task<(int ExitCode, string Output)> ExecuteSchtasksAsync(
        string action, CancellationToken cancellationToken)
    {
        var info = new ProcessStartInfo("schtasks.exe")
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };
        info.ArgumentList.Add(action);
        info.ArgumentList.Add("/TN");
        info.ArgumentList.Add(TaskName);

        using var process = Process.Start(info)
            ?? throw new InvalidOperationException("Could not start schtasks.exe.");

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(CommandTimeout);

        try
        {
            var stdout = process.StandardOutput.ReadToEndAsync(timeout.Token);
            var stderr = process.StandardError.ReadToEndAsync(timeout.Token);
            await process.WaitForExitAsync(timeout.Token);
            var output = (await stdout + " " + await stderr).Trim();
            return (process.ExitCode, output.Length > 500 ? output[..500] : output);
        }
        catch (OperationCanceledException)
        {
            if (!process.HasExited)
            {
                try { process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { }
            }

            throw;
        }
    }

    public override void Dispose()
    {
        _client.Dispose();
        base.Dispose();
    }
}

/// <summary>
/// Pure, deterministic restart policy, separate from HTTP and Windows Task Scheduler.
/// </summary>
public sealed class OmniMcpLiteWatchdogState
{
    public const int FailureThreshold = 3;
    public static readonly TimeSpan RestartCooldown = TimeSpan.FromSeconds(60);

    private DateTimeOffset? _lastRestart;

    public int ConsecutiveFailures { get; private set; }

    public WatchdogDecision Observe(bool healthy, DateTimeOffset now)
    {
        if (healthy)
        {
            bool recovered = ConsecutiveFailures > 0;
            ConsecutiveFailures = 0;
            return new WatchdogDecision(false, recovered, false);
        }

        ConsecutiveFailures = Math.Min(int.MaxValue, ConsecutiveFailures + 1);
        bool restart = ConsecutiveFailures >= FailureThreshold &&
            (_lastRestart == null || now - _lastRestart >= RestartCooldown);
        if (restart)
            _lastRestart = now; // Rate-limit attempts even when schtasks fails.

        return new WatchdogDecision(ConsecutiveFailures == 1, false, restart);
    }
}

public readonly record struct WatchdogDecision(bool FirstFailure, bool Recovered, bool Restart);
