using System;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Presentation.Hubs;
using Open.Nat;

namespace OmniSync.Hub.Logic.Services;

/// <summary>
/// Keeps track of the address this PC is reachable at from outside the LAN and
/// pushes it to connected clients, so the phone always has a current WAN address
/// for port forwarded connections and wake on LAN.
/// </summary>
public sealed class ExternalIpService : BackgroundService
{
    /// <summary>How often the address is re-resolved. A WAN address only changes when the ISP or router reassigns it.</summary>
    internal static readonly TimeSpan RefreshInterval = TimeSpan.FromHours(6);

    private static readonly TimeSpan StartupDelay = TimeSpan.FromSeconds(20);
    private static readonly TimeSpan UpnpTimeout = TimeSpan.FromSeconds(10);
    private static readonly TimeSpan EchoTimeout = TimeSpan.FromSeconds(10);
    internal const string EchoUrl = "https://api.ipify.org";

    private readonly HttpClient _httpClient;
    private readonly HubSettingsService _settingsService;
    private readonly HubMonitorService _monitorService;
    private readonly IHubContext<RpcApiHub> _hubContext;
    private readonly ILogger<ExternalIpService> _logger;
    private readonly SemaphoreSlim _refreshGate = new(1, 1);
    private NatDevice? _natDevice;

    public ExternalIpService(
        HttpClient httpClient,
        HubSettingsService settingsService,
        HubMonitorService monitorService,
        IHubContext<RpcApiHub> hubContext,
        ILogger<ExternalIpService> logger)
    {
        _httpClient = httpClient;
        _settingsService = settingsService;
        _monitorService = monitorService;
        _hubContext = hubContext;
        _logger = logger;
    }

    /// <summary>Last known public address, or null when it has never resolved.</summary>
    public string? CurrentExternalIp { get; private set; }

    /// <summary>Where the last value came from, for the activity log.</summary>
    public string CurrentSource { get; private set; } = "none";

    public DateTimeOffset? UpdatedAtUtc { get; private set; }

    /// <summary>Unix seconds of the last successful resolve, for push payloads.</summary>
    public long UpdatedAtUnix => UpdatedAtUtc?.ToUnixTimeSeconds() ?? 0;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        CurrentExternalIp = NormalizeExternalIp(_settingsService.Settings.ExternalIp);
        UpdatedAtUtc = _settingsService.Settings.ExternalIpUpdatedAtUtc;

        try
        {
            await Task.Delay(StartupDelay, stoppingToken);
        }
        catch (OperationCanceledException)
        {
            return;
        }

        await RefreshAsync(stoppingToken);

        using var timer = new PeriodicTimer(RefreshInterval);
        try
        {
            while (await timer.WaitForNextTickAsync(stoppingToken))
                await RefreshAsync(stoppingToken);
        }
        catch (OperationCanceledException)
        {
            // Host is shutting down.
        }
    }

    /// <summary>
    /// Resolves the current public address, stores it and pushes it to every
    /// connected client. Safe to call at any time; concurrent calls collapse.
    /// </summary>
    public async Task<string?> RefreshAsync(CancellationToken cancellationToken = default)
    {
        if (!await _refreshGate.WaitAsync(0, cancellationToken))
            return CurrentExternalIp;

        try
        {
            var (ip, source) = await ResolveAsync(cancellationToken);
            if (ip is null)
            {
                _monitorService.AddLogMessage("[ExternalIp] Could not resolve the public IP address, keeping the last known one.");
                return CurrentExternalIp;
            }

            var changed = !string.Equals(ip, CurrentExternalIp, StringComparison.Ordinal);
            CurrentExternalIp = ip;
            CurrentSource = source;
            UpdatedAtUtc = DateTimeOffset.UtcNow;
            _settingsService.UpdateExternalIp(ip, UpdatedAtUtc.Value);

            if (changed)
                _monitorService.AddLogMessage($"[ExternalIp] Public address is {ip} (via {source}).");

            await PushAsync(cancellationToken);
            return ip;
        }
        finally
        {
            _refreshGate.Release();
        }
    }

    /// <summary>Sends the cached address to every connected client.</summary>
    public async Task PushAsync(CancellationToken cancellationToken = default)
    {
        var ip = CurrentExternalIp;
        if (ip is null)
            return;

        try
        {
            await _hubContext.Clients.All.SendAsync("UpdateExternalIp", ip, UpdatedAtUnix, cancellationToken);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Failed to push the external IP to clients.");
        }
    }

    /// <summary>UPnP first because it is the router's own view, with an HTTPS echo as fallback.</summary>
    private async Task<(string? Ip, string Source)> ResolveAsync(CancellationToken cancellationToken)
    {
        var viaUpnp = await TryResolveViaUpnpAsync(cancellationToken);
        if (viaUpnp is not null)
            return (viaUpnp, "UPnP");

        var viaEcho = await TryResolveViaEchoAsync(cancellationToken);
        if (viaEcho is not null)
            return (viaEcho, "HTTPS echo");

        return (null, "none");
    }

    private async Task<string?> TryResolveViaUpnpAsync(CancellationToken cancellationToken)
    {
        try
        {
            if (_natDevice is null)
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                timeout.CancelAfter(UpnpTimeout);
                _natDevice = await new NatDiscoverer().DiscoverDeviceAsync(PortMapper.Upnp, timeout);
            }

            if (_natDevice is null)
                return null;

            var address = NormalizeExternalIp((await _natDevice.GetExternalIPAsync())?.ToString());
            if (address is null)
                return null;

            // A router that reports its own LAN side is behind another NAT, and
            // the phone needs the address the internet sees, not that one.
            return IsPublicIpv4(address) ? address : null;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            _natDevice = null;
            return null;
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "UPnP external IP lookup failed.");
            _natDevice = null;
            return null;
        }
    }

    private async Task<string?> TryResolveViaEchoAsync(CancellationToken cancellationToken)
    {
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(EchoTimeout);
            var body = await _httpClient.GetStringAsync(EchoUrl, timeout.Token);
            return NormalizeExternalIp(body);
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            return null;
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "External IP echo lookup failed.");
            return null;
        }
    }

    /// <summary>Trims a raw response down to a usable address, or null when it is not one.</summary>
    internal static string? NormalizeExternalIp(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
            return null;

        var trimmed = raw.Trim();
        if (!IPAddress.TryParse(trimmed, out var address))
            return null;

        if (address.AddressFamily != AddressFamily.InterNetwork)
            return null;
        if (IPAddress.IsLoopback(address) || address.Equals(IPAddress.Any) || address.Equals(IPAddress.Broadcast))
            return null;
        if (address.GetAddressBytes()[0] == 169)
            return null;

        return address.ToString();
    }

    /// <summary>True for an address that is routable on the internet rather than a private range.</summary>
    internal static bool IsPublicIpv4(string ip)
    {
        if (!IPAddress.TryParse(ip, out var address) || address.AddressFamily != AddressFamily.InterNetwork)
            return false;

        var octets = address.GetAddressBytes();
        return octets[0] switch
        {
            0 or 127 => false,
            10 => false,
            169 => octets[1] != 254,
            172 => octets[1] is < 16 or > 31,
            192 => octets[1] != 168,
            _ => octets[0] < 224
        };
    }
}
