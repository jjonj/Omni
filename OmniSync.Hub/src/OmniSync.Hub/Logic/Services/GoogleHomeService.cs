using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Logic.Services;

/// <summary>
/// Makes the Hub look like a Google smart home provider (cloud-to-cloud integration in test mode)
/// so Google Home / Gemini speakers can trigger Omni commands by voice.
/// Every macro whose Category is "Voice" is exposed as a Google scene: "Hey Google, activate &lt;macro name&gt;".
/// State (client credentials, issued tokens) lives in %APPDATA%\OmniSync\google_home.json.
/// </summary>
public sealed class GoogleHomeService
{
    public const string VoiceCategory = "Voice";
    public const string AgentUserId = "omni-user";
    private const string ScenePrefix = "macro:";
    private static readonly TimeSpan AccessTokenLifetime = TimeSpan.FromDays(1);
    private static readonly TimeSpan AuthCodeLifetime = TimeSpan.FromMinutes(10);

    private readonly HubSettingsService _settingsService;
    private readonly IMacroService _macroService;
    private readonly HubMonitorService _monitorService;
    private readonly HttpClient _httpClient;
    private readonly ILogger<GoogleHomeService> _logger;
    private readonly string _statePath;
    private readonly string _serviceAccountPath;
    private readonly object _lock = new();
    private readonly ConcurrentDictionary<string, DateTimeOffset> _authCodes = new();
    private GoogleHomeState _state;
    private string _lastSyncedSignature = "";
    private CancellationTokenSource? _syncDebounce;

    public GoogleHomeService(
        HubSettingsService settingsService,
        IMacroService macroService,
        HubMonitorService monitorService,
        HttpClient httpClient,
        ILogger<GoogleHomeService> logger)
    {
        _settingsService = settingsService;
        _macroService = macroService;
        _monitorService = monitorService;
        _httpClient = httpClient;
        _logger = logger;

        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniSync");
        Directory.CreateDirectory(dir);
        _statePath = Path.Combine(dir, "google_home.json");
        _serviceAccountPath = Path.Combine(dir, "google_home_service_account.json");
        _state = LoadState();
        _lastSyncedSignature = ComputeSceneSignature();
        _settingsService.SettingsChanged += (_, _) => OnSettingsChanged();
    }

    public string ClientId => _state.ClientId;
    public string ClientSecret => _state.ClientSecret;
    public string StatePath => _statePath;
    public string ServiceAccountPath => _serviceAccountPath;
    public bool IsLinked { get { lock (_lock) return _state.RefreshTokens.Count > 0; } }

    // ---------------- Scenes ----------------

    public IReadOnlyList<MacroConfig> GetVoiceMacros() =>
        (_settingsService.Settings.Macros ?? new())
            .Where(m => string.Equals(m.Category?.Trim(), VoiceCategory, StringComparison.OrdinalIgnoreCase)
                        && !string.IsNullOrWhiteSpace(m.Name))
            .GroupBy(m => m.Name.Trim(), StringComparer.OrdinalIgnoreCase)
            .Select(g => g.First())
            .ToList();

    private string ComputeSceneSignature() =>
        string.Join("|", GetVoiceMacros().Select(m => $"{m.Id}={m.Name.Trim()}").OrderBy(s => s));

    // ---------------- OAuth (single user, fake account linking) ----------------

    public static bool IsAllowedRedirect(string? redirectUri)
    {
        if (!Uri.TryCreate(redirectUri, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps) return false;
        return uri.Host is "oauth-redirect.googleusercontent.com" or "oauth-redirect-sandbox.googleusercontent.com";
    }

    public string IssueAuthCode()
    {
        foreach (var kv in _authCodes.Where(kv => kv.Value < DateTimeOffset.UtcNow).ToList()) _authCodes.TryRemove(kv.Key, out _);
        var code = RandomToken();
        _authCodes[code] = DateTimeOffset.UtcNow + AuthCodeLifetime;
        return code;
    }

    public bool ValidateClient(string? clientId, string? clientSecret) =>
        FixedEquals(clientId, _state.ClientId) && FixedEquals(clientSecret, _state.ClientSecret);

    public (string accessToken, string refreshToken)? RedeemAuthCode(string? code)
    {
        if (string.IsNullOrEmpty(code) || !_authCodes.TryRemove(code, out var expiry) || expiry < DateTimeOffset.UtcNow) return null;
        var refresh = RandomToken();
        var access = RandomToken();
        lock (_lock)
        {
            _state.RefreshTokens.Add(Hash(refresh));
            AddAccessTokenLocked(access);
            SaveStateLocked();
        }
        _monitorService.AddLogMessage("[GoogleHome] Google account linked.");
        return (access, refresh);
    }

    public string? RefreshAccessToken(string? refreshToken)
    {
        if (string.IsNullOrEmpty(refreshToken)) return null;
        var access = RandomToken();
        lock (_lock)
        {
            if (!_state.RefreshTokens.Contains(Hash(refreshToken))) return null;
            AddAccessTokenLocked(access);
            SaveStateLocked();
        }
        return access;
    }

    public int AccessTokenLifetimeSeconds => (int)AccessTokenLifetime.TotalSeconds;

    public bool ValidateAccessToken(string? token)
    {
        if (string.IsNullOrEmpty(token)) return false;
        var hash = Hash(token);
        lock (_lock)
        {
            return _state.AccessTokens.TryGetValue(hash, out var expiry) && expiry > DateTimeOffset.UtcNow;
        }
    }

    public void Unlink()
    {
        lock (_lock)
        {
            _state.RefreshTokens.Clear();
            _state.AccessTokens.Clear();
            SaveStateLocked();
        }
        _monitorService.AddLogMessage("[GoogleHome] Google account unlinked.");
    }

    private void AddAccessTokenLocked(string access)
    {
        var now = DateTimeOffset.UtcNow;
        foreach (var key in _state.AccessTokens.Where(kv => kv.Value < now).Select(kv => kv.Key).ToList()) _state.AccessTokens.Remove(key);
        _state.AccessTokens[Hash(access)] = now + AccessTokenLifetime;
    }

    // ---------------- Fulfillment ----------------

    public async Task<JsonObject> HandleFulfillmentAsync(JsonNode request)
    {
        var requestId = request["requestId"]?.GetValue<string>() ?? "";
        var input = request["inputs"]?.AsArray().FirstOrDefault();
        var intent = input?["intent"]?.GetValue<string>() ?? "";
        JsonObject payload = intent switch
        {
            "action.devices.SYNC" => BuildSyncPayload(),
            "action.devices.QUERY" => BuildQueryPayload(input!),
            "action.devices.EXECUTE" => BuildExecutePayload(input!),
            "action.devices.DISCONNECT" => HandleDisconnect(),
            _ => new JsonObject { ["errorCode"] = "notSupported" }
        };
        if (intent == "action.devices.SYNC") _lastSyncedSignature = ComputeSceneSignature();
        await Task.CompletedTask;
        return new JsonObject { ["requestId"] = requestId, ["payload"] = payload };
    }

    private JsonObject BuildSyncPayload()
    {
        var devices = new JsonArray();
        foreach (var macro in GetVoiceMacros())
        {
            devices.Add(new JsonObject
            {
                ["id"] = ScenePrefix + macro.Id,
                ["type"] = "action.devices.types.SCENE",
                ["traits"] = new JsonArray("action.devices.traits.Scene"),
                ["name"] = new JsonObject { ["name"] = macro.Name.Trim() },
                ["willReportState"] = false,
                ["attributes"] = new JsonObject { ["sceneReversible"] = false },
                ["deviceInfo"] = new JsonObject { ["manufacturer"] = "Omni", ["model"] = "OmniHub voice command" }
            });
        }
        _monitorService.AddLogMessage($"[GoogleHome] SYNC: exposing {devices.Count} voice command(s).");
        return new JsonObject { ["agentUserId"] = AgentUserId, ["devices"] = devices };
    }

    private JsonObject BuildQueryPayload(JsonNode input)
    {
        var known = GetVoiceMacros().Select(m => ScenePrefix + m.Id).ToHashSet();
        var result = new JsonObject();
        foreach (var device in input["payload"]?["devices"]?.AsArray() ?? new JsonArray())
        {
            var id = device?["id"]?.GetValue<string>() ?? "";
            result[id] = known.Contains(id)
                ? new JsonObject { ["online"] = true, ["status"] = "SUCCESS" }
                : new JsonObject { ["status"] = "ERROR", ["errorCode"] = "deviceNotFound" };
        }
        return new JsonObject { ["devices"] = result };
    }

    private JsonObject BuildExecutePayload(JsonNode input)
    {
        var macros = GetVoiceMacros().ToDictionary(m => ScenePrefix + m.Id);
        var ok = new JsonArray();
        var missing = new JsonArray();
        foreach (var command in input["payload"]?["commands"]?.AsArray() ?? new JsonArray())
        {
            var executions = command?["execution"]?.AsArray() ?? new JsonArray();
            foreach (var device in command?["devices"]?.AsArray() ?? new JsonArray())
            {
                var id = device?["id"]?.GetValue<string>() ?? "";
                if (!macros.TryGetValue(id, out var macro)) { missing.Add(id); continue; }
                foreach (var execution in executions)
                {
                    var deactivate = execution?["params"]?["deactivate"]?.GetValue<bool>() ?? false;
                    if (deactivate) continue;
                    _monitorService.AddLogMessage($"[GoogleHome] Voice command: {macro.Name}");
                    // Google expects a fast answer; macros can sleep, so run them in the background.
                    _ = Task.Run(async () =>
                    {
                        try { await _macroService.ExecuteMacroAsync(macro); }
                        catch (Exception ex) { _logger.LogError(ex, "[GoogleHome] Macro {Name} failed", macro.Name); }
                    });
                }
                ok.Add(id);
            }
        }
        var commands = new JsonArray();
        if (ok.Count > 0) commands.Add(new JsonObject { ["ids"] = ok, ["status"] = "SUCCESS", ["states"] = new JsonObject { ["online"] = true } });
        if (missing.Count > 0) commands.Add(new JsonObject { ["ids"] = missing, ["status"] = "ERROR", ["errorCode"] = "deviceNotFound" });
        return new JsonObject { ["commands"] = commands };
    }

    private JsonObject HandleDisconnect()
    {
        Unlink();
        return new JsonObject();
    }

    // ---------------- Request Sync (optional, needs a service account key) ----------------

    private void OnSettingsChanged()
    {
        if (ComputeSceneSignature() == _lastSyncedSignature || !IsLinked) return;
        _syncDebounce?.Cancel();
        var cts = _syncDebounce = new CancellationTokenSource();
        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(5), cts.Token);
                await RequestSyncAsync();
            }
            catch (OperationCanceledException) { }
        });
    }

    /// <summary>Asks Google to re-fetch the device list. Without a service account key the user says "Hey Google, sync my devices".</summary>
    public async Task<bool> RequestSyncAsync()
    {
        if (!File.Exists(_serviceAccountPath))
        {
            _monitorService.AddLogMessage("[GoogleHome] Voice commands changed. Say \"Hey Google, sync my devices\" (no service account key for automatic sync).");
            return false;
        }
        try
        {
            var token = await GetServiceAccountTokenAsync();
            var body = new StringContent(JsonSerializer.Serialize(new { agentUserId = AgentUserId, async = true }), Encoding.UTF8, "application/json");
            using var req = new HttpRequestMessage(HttpMethod.Post, "https://homegraph.googleapis.com/v1/devices:requestSync") { Content = body };
            req.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
            using var resp = await _httpClient.SendAsync(req);
            var text = await resp.Content.ReadAsStringAsync();
            if (!resp.IsSuccessStatusCode)
            {
                _monitorService.AddLogMessage($"[GoogleHome] requestSync failed ({(int)resp.StatusCode}): {text}");
                return false;
            }
            _lastSyncedSignature = ComputeSceneSignature();
            _monitorService.AddLogMessage("[GoogleHome] Asked Google to re-sync voice commands.");
            return true;
        }
        catch (Exception ex)
        {
            _monitorService.AddLogMessage($"[GoogleHome] requestSync error: {ex.Message}");
            return false;
        }
    }

    private async Task<string> GetServiceAccountTokenAsync()
    {
        var key = JsonNode.Parse(await File.ReadAllTextAsync(_serviceAccountPath))!;
        var email = key["client_email"]!.GetValue<string>();
        var pem = key["private_key"]!.GetValue<string>();
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        string B64(byte[] b) => Convert.ToBase64String(b).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        var header = B64(JsonSerializer.SerializeToUtf8Bytes(new { alg = "RS256", typ = "JWT" }));
        var claims = B64(JsonSerializer.SerializeToUtf8Bytes(new
        {
            iss = email,
            scope = "https://www.googleapis.com/auth/homegraph",
            aud = "https://oauth2.googleapis.com/token",
            iat = now,
            exp = now + 3600
        }));
        using var rsa = RSA.Create();
        rsa.ImportFromPem(pem);
        var signature = B64(rsa.SignData(Encoding.ASCII.GetBytes($"{header}.{claims}"), HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));
        var form = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["grant_type"] = "urn:ietf:params:oauth:grant-type:jwt-bearer",
            ["assertion"] = $"{header}.{claims}.{signature}"
        });
        using var resp = await _httpClient.PostAsync("https://oauth2.googleapis.com/token", form);
        resp.EnsureSuccessStatusCode();
        var json = JsonNode.Parse(await resp.Content.ReadAsStringAsync())!;
        return json["access_token"]!.GetValue<string>();
    }

    // ---------------- State ----------------

    private GoogleHomeState LoadState()
    {
        GoogleHomeState? state = null;
        try
        {
            if (File.Exists(_statePath)) state = JsonSerializer.Deserialize<GoogleHomeState>(File.ReadAllText(_statePath));
        }
        catch (Exception ex) { _logger.LogWarning(ex, "[GoogleHome] Could not read {Path}; starting fresh", _statePath); }
        state ??= new GoogleHomeState();
        var changed = false;
        if (string.IsNullOrEmpty(state.ClientId)) { state.ClientId = "omnihub-" + RandomToken()[..10].ToLowerInvariant(); changed = true; }
        if (string.IsNullOrEmpty(state.ClientSecret)) { state.ClientSecret = RandomToken(); changed = true; }
        _state = state;
        if (changed) lock (_lock) SaveStateLocked();
        return state;
    }

    private void SaveStateLocked() =>
        File.WriteAllText(_statePath, JsonSerializer.Serialize(_state, new JsonSerializerOptions { WriteIndented = true }));

    private static string RandomToken() =>
        Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value)));

    private static bool FixedEquals(string? a, string? b) =>
        a != null && b != null && CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(a), Encoding.UTF8.GetBytes(b));

    private sealed class GoogleHomeState
    {
        public string ClientId { get; set; } = "";
        public string ClientSecret { get; set; } = "";
        public List<string> RefreshTokens { get; set; } = new();
        public Dictionary<string, DateTimeOffset> AccessTokens { get; set; } = new();
    }
}
