using System.Text.Json;
using System.IO;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Logic.Monitoring;

public sealed class ConnectivityAlertService(HubMonitorService monitor, ILogger<ConnectivityAlertService> logger) : BackgroundService
{
    private readonly string _path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "OmniSync", "lite-watchdog", "status.json");
    private ConnectivityAlertState _policy = new();
    private bool _presenting;

    private async Task<bool> RustOwnsPresentationAsync(CancellationToken cancellationToken)
    {
        try
        {
            using var lease = JsonDocument.Parse(await File.ReadAllTextAsync(
                Path.Combine(Path.GetDirectoryName(_path)!, "rust-presentation.json"), cancellationToken));
            var root = lease.RootElement;
            if (root.GetProperty("owner").GetString() != "rust" ||
                !root.GetProperty("checkedAt").TryGetDateTimeOffset(out var at) ||
                DateTimeOffset.UtcNow - at > TimeSpan.FromSeconds(15) || at > DateTimeOffset.UtcNow.AddSeconds(5)) return false;
            using var owner = System.Diagnostics.Process.GetProcessById(root.GetProperty("pid").GetInt32());
            return !owner.HasExited && owner.ProcessName.StartsWith("omnihub", StringComparison.OrdinalIgnoreCase);
        }
        catch (Exception error) when (error is IOException or JsonException or InvalidOperationException or
            KeyNotFoundException or ArgumentException or UnauthorizedAccessException or FormatException or System.ComponentModel.Win32Exception)
        { return false; }
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Give the tray subscriber and scheduled watchdog time to start at logon.
        await Task.Delay(TimeSpan.FromSeconds(15), stoppingToken);
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5));
        do
        {
            if (await RustOwnsPresentationAsync(stoppingToken))
            {
                if (_presenting)
                {
                    monitor.ReportConnectivityAlert(new(false, "Monitoring moved to Rust OmniHub",
                        "The Rust Hub now presents connection alerts. Legacy monitoring remains a fallback."));
                    _presenting = false;
                    _policy = new();
                }
                continue;
            }
            ConnectivityAlert? problem;
            bool paused = false;
            try
            {
                using var document = JsonDocument.Parse(await File.ReadAllTextAsync(_path, stoppingToken));
                problem = ConnectivityAlertState.Classify(document.RootElement, DateTimeOffset.UtcNow);
                paused = document.RootElement.TryGetProperty("paused", out var pause) && pause.ValueKind == JsonValueKind.True &&
                    document.RootElement.TryGetProperty("checkedAt", out var stamp) && stamp.TryGetDateTimeOffset(out var at) &&
                    DateTimeOffset.UtcNow - at <= TimeSpan.FromSeconds(45);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception error) when (error is IOException or JsonException or UnauthorizedAccessException or InvalidOperationException or FormatException)
            {
                problem = new(true, "Tailscale monitoring unavailable", "OmniHub cannot read connection health. The independent watchdog may have stopped.");
                logger.LogDebug(error, "Unable to read connectivity watchdog status");
            }
            var alert = _policy.Observe(problem, DateTimeOffset.UtcNow, paused);
            if (alert != null)
            {
                _presenting = alert.Active;
                logger.LogWarning("Connectivity alert: {Title}: {Detail}", alert.Title, alert.Detail);
                monitor.ReportConnectivityAlert(alert);
            }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
