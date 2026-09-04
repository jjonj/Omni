using System.Security.Cryptography;
using System.Text.Json;
using System.IO;
using System.Collections.Concurrent;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.SignalR;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Presentation.Hubs;

namespace OmniSync.Hub.Presentation.Controllers;

[ApiController]
[Route("api/android-update")]
public sealed class AndroidUpdateController : ControllerBase
{
    private const string ApiKeyHeader = "X-Omni-Api-Key";
    private static readonly ConcurrentDictionary<string, DateTimeOffset> DownloadTickets = new();
    private readonly AuthService _authService;
    private readonly IConfiguration _configuration;
    private readonly IHubContext<RpcApiHub> _hubContext;
    private readonly AndroidUpdateDeploymentCoordinator _deploymentCoordinator;
    private readonly ILogger<AndroidUpdateController> _logger;

    public AndroidUpdateController(
        AuthService authService,
        IConfiguration configuration,
        IHubContext<RpcApiHub> hubContext,
        AndroidUpdateDeploymentCoordinator deploymentCoordinator,
        ILogger<AndroidUpdateController> logger)
    {
        _authService = authService;
        _configuration = configuration;
        _hubContext = hubContext;
        _deploymentCoordinator = deploymentCoordinator;
        _logger = logger;
    }

    [HttpGet]
    public async Task<IActionResult> GetMetadata()
    {
        if (!IsAuthorized()) return Unauthorized();

        var apkPath = FindApkPath();
        if (apkPath is null)
        {
            return NotFound(new { message = "No Android update APK has been built yet." });
        }

        _logger.LogInformation(
            "[AndroidDeploy] Metadata requested by {RemoteIp}; APK={ApkPath}",
            HttpContext.Connection.RemoteIpAddress,
            apkPath);
        var metadata = await ReadMetadataAsync(apkPath);
        return Ok(new
        {
            metadata.VersionCode,
            metadata.VersionName,
            metadata.Sha256,
            Size = new FileInfo(apkPath).Length,
            metadata.BuiltAtUtc,
            DownloadPath = "/api/android-update/apk"
        });
    }

    [HttpGet("apk")]
    public IActionResult DownloadApk()
    {
        if (!IsAuthorized() && !HasValidDownloadTicket()) return Unauthorized();

        var apkPath = FindApkPath();
        if (apkPath is null)
        {
            return NotFound(new { message = "No Android update APK has been built yet." });
        }

        _logger.LogInformation(
            "[AndroidDeploy] APK download requested by {RemoteIp}; bytes={Size}; ticket={UsesTicket}",
            HttpContext.Connection.RemoteIpAddress,
            new FileInfo(apkPath).Length,
            !IsAuthorized());
        return PhysicalFile(
            apkPath,
            "application/vnd.android.package-archive",
            Path.GetFileName(apkPath),
            enableRangeProcessing: true);
    }

    [HttpPost("deploy")]
    public async Task<IActionResult> Deploy(
        [FromBody] AndroidDeployRequest? request,
        CancellationToken cancellationToken)
    {
        if (!IsAuthorized()) return Unauthorized();
        if (FindApkPath() is null)
        {
            return NotFound(new { message = "No Android update APK has been built yet." });
        }

        var publicBaseUrl = NormalizePublicBaseUrl(request?.PublicBaseUrl);
        if (publicBaseUrl is null)
        {
            return BadRequest(new { message = "A valid HTTP(S) publicBaseUrl is required." });
        }

        var connectionDeadline = DateTimeOffset.UtcNow.AddSeconds(60);
        while (_deploymentCoordinator.RemoteClientCount == 0 &&
               DateTimeOffset.UtcNow < connectionDeadline)
        {
            await Task.Delay(250, cancellationToken);
        }
        if (_deploymentCoordinator.RemoteClientCount == 0)
        {
            _logger.LogWarning("[AndroidDeploy] Deployment rejected because no remote SignalR clients are connected.");
            return StatusCode(
                StatusCodes.Status503ServiceUnavailable,
                new { message = "No remote SignalR client is connected to receive the Android deployment." });
        }

        var requestId = _deploymentCoordinator.Begin();
        _logger.LogInformation(
            "[AndroidDeploy] Dispatching native update request {RequestId} to {ClientCount} connected clients; public base URL {PublicBaseUrl}",
            requestId,
            _deploymentCoordinator.RemoteClientCount,
            publicBaseUrl);
        await _hubContext.Clients.All.SendAsync(
            "AndroidUpdateRequested",
            requestId,
            cancellationToken);

        var firstReport = await _deploymentCoordinator.WaitForFirstReportAsync(
            requestId,
            TimeSpan.FromSeconds(3),
            cancellationToken);
        if (firstReport is not null)
        {
            _logger.LogInformation(
                "[AndroidDeploy] Native client acknowledged request {RequestId}: {Status}",
                requestId,
                firstReport.Status);
            return Accepted(new
            {
                requestId,
                mode = "native",
                firstReport.Status,
                firstReport.Message
            });
        }

        var ticket = CreateDownloadTicket();
        var downloadUrl = $"{publicBaseUrl}/api/android-update/apk?ticket={Uri.EscapeDataString(ticket)}";
        _deploymentCoordinator.Report(
            requestId,
            "browser_opened",
            "The connected legacy client was sent a one-time APK download.");
        _logger.LogWarning(
            "[AndroidDeploy] No native acknowledgement for {RequestId}; opening authenticated browser fallback.",
            requestId);
        await _hubContext.Clients.All.SendAsync(
            "ReceiveTabToPhone",
            downloadUrl,
            cancellationToken);

        return Accepted(new
        {
            requestId,
            mode = "browser",
            status = "browser_opened",
            message = "A one-time APK download was opened on the connected Android client."
        });
    }

    [HttpGet("deploy/{requestId}")]
    public IActionResult GetDeploymentStatus(string requestId)
    {
        if (!IsAuthorized()) return Unauthorized();
        var status = _deploymentCoordinator.Get(requestId);
        return status is null ? NotFound() : Ok(status);
    }

    private bool IsAuthorized()
    {
        return Request.Headers.TryGetValue(ApiKeyHeader, out var apiKey) &&
               _authService.Validate(apiKey.ToString());
    }

    private bool HasValidDownloadTicket()
    {
        if (!Request.Query.TryGetValue("ticket", out var suppliedTicket)) return false;
        var now = DateTimeOffset.UtcNow;
        foreach (var ticket in DownloadTickets)
        {
            if (ticket.Value <= now) DownloadTickets.TryRemove(ticket.Key, out _);
        }
        return DownloadTickets.TryGetValue(suppliedTicket.ToString(), out var expiresAt) &&
               expiresAt > now;
    }

    private static string CreateDownloadTicket()
    {
        var ticket = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        DownloadTickets[ticket] = DateTimeOffset.UtcNow.AddMinutes(10);
        return ticket;
    }

    private static string? NormalizePublicBaseUrl(string? value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) ||
            (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps) ||
            string.IsNullOrWhiteSpace(uri.Host))
        {
            return null;
        }
        return uri.GetLeftPart(UriPartial.Authority).TrimEnd('/');
    }

    private string? FindApkPath()
    {
        var configuredPath = _configuration["AndroidUpdate:ApkPath"];
        if (!string.IsNullOrWhiteSpace(configuredPath))
        {
            var expandedPath = Environment.ExpandEnvironmentVariables(configuredPath);
            var absolutePath = Path.IsPathRooted(expandedPath)
                ? Path.GetFullPath(expandedPath)
                : Path.GetFullPath(expandedPath, FindRepositoryRoot() ?? AppContext.BaseDirectory);
            return System.IO.File.Exists(absolutePath) ? absolutePath : null;
        }

        var repositoryRoot = FindRepositoryRoot();
        if (repositoryRoot is null) return null;
        var defaultPath = Path.Combine(
            repositoryRoot,
            "OmniSync.Android",
            "app",
            "build",
            "outputs",
            "apk",
            "debug",
            "app-debug.apk");
        return System.IO.File.Exists(defaultPath) ? defaultPath : null;
    }

    private static string? FindRepositoryRoot()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        for (var depth = 0; directory is not null && depth < 12; depth++, directory = directory.Parent)
        {
            if (Directory.Exists(Path.Combine(directory.FullName, "OmniSync.Android")) &&
                Directory.Exists(Path.Combine(directory.FullName, "OmniSync.Hub")))
            {
                return directory.FullName;
            }
        }
        return null;
    }

    private static async Task<AndroidUpdateMetadata> ReadMetadataAsync(string apkPath)
    {
        var metadataPath = Path.ChangeExtension(apkPath, ".update.json");
        AndroidUpdateMetadata? metadata = null;
        if (System.IO.File.Exists(metadataPath))
        {
            await using var metadataStream = System.IO.File.OpenRead(metadataPath);
            metadata = await JsonSerializer.DeserializeAsync<AndroidUpdateMetadata>(
                metadataStream,
                new JsonSerializerOptions { PropertyNameCaseInsensitive = true });
        }

        await using var apkStream = System.IO.File.OpenRead(apkPath);
        var sha256 = Convert.ToHexString(await SHA256.HashDataAsync(apkStream)).ToLowerInvariant();
        var metadataMatchesApk = metadata?.Sha256.Equals(
            sha256,
            StringComparison.OrdinalIgnoreCase) == true;
        return new AndroidUpdateMetadata
        {
            VersionCode = metadataMatchesApk ? metadata!.VersionCode : 0,
            VersionName = metadataMatchesApk ? metadata!.VersionName : "development",
            Sha256 = sha256,
            BuiltAtUtc = metadataMatchesApk
                ? metadata!.BuiltAtUtc
                : System.IO.File.GetLastWriteTimeUtc(apkPath)
        };
    }

    private sealed class AndroidUpdateMetadata
    {
        public long VersionCode { get; init; }
        public string VersionName { get; init; } = "development";
        public string Sha256 { get; init; } = string.Empty;
        public DateTime BuiltAtUtc { get; init; }
    }

    public sealed class AndroidDeployRequest
    {
        public string? PublicBaseUrl { get; init; }
    }
}
