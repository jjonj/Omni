using System.Text.Json;

namespace OmniSync.Hub.Logic.Monitoring;

public sealed record ConnectivityAlert(bool Active, string Title, string Detail, bool Confirmed = true);

/// <summary>Confirms outages and reports changes once, independently of UI/IO.</summary>
public sealed class ConnectivityAlertState
{
    private int _failures;
    private bool _active;
    private string? _lastTitle;
    private DateTimeOffset? _healthySince;
    private static readonly TimeSpan RecoveryConfirmation = TimeSpan.FromMinutes(2);

    public ConnectivityAlert? Observe(ConnectivityAlert? problem, DateTimeOffset now, bool paused = false)
    {
        if (paused) { _failures = 0; _healthySince = null; return null; }
        if (problem == null)
        {
            _failures = 0;
            if (!_active) return null;
            _healthySince ??= now;
            if (now - _healthySince.Value < RecoveryConfirmation) return null;
            _active = false;
            _lastTitle = null;
            _healthySince = null;
            return new(false, "Connection restored", "Tailscale and public OmniMCP access are responding again.");
        }
        _healthySince = null;
        // Public probes run once a minute. Re-reading one cached failure every
        // five seconds does not constitute independent failure confirmation.
        // Pending degradation also cannot resolve an already active incident.
        if (!problem.Confirmed) { _failures = 0; return null; }
        if (++_failures < 2) return null;
        if (!_active || problem.Title != _lastTitle)
        {
            _active = true;
            _lastTitle = problem.Title;
            return problem;
        }
        return null;
    }

    public static ConnectivityAlert? Classify(JsonElement status, DateTimeOffset now)
    {
        if (!status.TryGetProperty("checkedAt", out var stamp) ||
            !stamp.TryGetDateTimeOffset(out var checkedAt) || now - checkedAt > TimeSpan.FromSeconds(45) ||
            checkedAt - now > TimeSpan.FromSeconds(30))
            return new(true, "Tailscale monitoring stopped", "The independent connection watchdog has stopped reporting. Remote access cannot be verified.");
        // Intentional maintenance suppresses alerts as well as recovery.
        if (status.TryGetProperty("paused", out var paused) && paused.ValueKind == JsonValueKind.True) return null;
        if (status.TryGetProperty("tailscale", out var transport))
        {
            string state = transport.TryGetProperty("state", out var stateValue) ? stateValue.GetString() ?? "Unknown" : "Unknown";
            if ((transport.TryGetProperty("authRequired", out var auth) && auth.ValueKind == JsonValueKind.True) ||
                state is "NeedsLogin" or "NeedsMachineAuth")
                return new(true, "Tailscale authorization required", "Remote access is offline. Open Tailscale to sign in or approve this computer. Automatic restarts cannot repair authorization.");
            if (state != "Running" || !transport.TryGetProperty("online", out var online) || online.ValueKind != JsonValueKind.True)
                return new(true, "Tailscale connection lost", $"Tailscale is {state}. Remote access is unavailable. Automatic recovery is being monitored.");
        }
        else return new(true, "Tailscale monitoring needs attention", "The watchdog is running an older version without Tailscale authorization checks.");
        if (status.TryGetProperty("localHealthy", out var local) && local.ValueKind == JsonValueKind.False)
            return new(true, "OmniMCP-Lite is offline", "Local MCP calls are failing. The independent watchdog is attempting to restore the server.");
        if (!status.TryGetProperty("publicHealthy", out var healthy) || healthy.ValueKind != JsonValueKind.True)
        {
            bool dns = status.TryGetProperty("publicError", out var error) && error.ValueKind == JsonValueKind.String;
            bool confirmed = dns || !status.TryGetProperty("publicFailures", out var failures) ||
                !failures.TryGetInt32(out var count) || count >= 3;
            return new(true, "Public remote access is failing", dns
                ? "Public connection checks failed. Check your internet connection and Tailscale; remote MCP access cannot be verified."
                : "One or more public Tailscale routes failed actual MCP calls. Remote agents may be unable to connect. The watchdog is attempting recovery.", confirmed);
        }
        return null;
    }
}
