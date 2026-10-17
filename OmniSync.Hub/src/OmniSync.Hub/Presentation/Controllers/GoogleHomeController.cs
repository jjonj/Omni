using System.IO;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Presentation.Controllers;

/// <summary>
/// Endpoints Google calls for the "OmniHub" cloud-to-cloud smart home integration.
/// Exposed publicly (HTTPS tunnel to port 5055); see GoogleHomeService for the model.
/// </summary>
[ApiController]
[Route("google-home")]
public sealed class GoogleHomeController : ControllerBase
{
    private readonly GoogleHomeService _googleHome;
    private readonly ILogger<GoogleHomeController> _logger;

    public GoogleHomeController(GoogleHomeService googleHome, ILogger<GoogleHomeController> logger)
    {
        _googleHome = googleHome;
        _logger = logger;
    }

    [HttpGet]
    public IActionResult Status() =>
        Content($"OmniHub Google Home bridge is up. Linked: {_googleHome.IsLinked}. Voice commands: {_googleHome.GetVoiceMacros().Count}.");

    /// <summary>OAuth authorization endpoint. Single user: approve immediately, the client secret protects the token exchange.</summary>
    [HttpGet("auth")]
    public IActionResult Authorize(
        [FromQuery(Name = "client_id")] string? clientId,
        [FromQuery(Name = "redirect_uri")] string? redirectUri,
        [FromQuery] string? state,
        [FromQuery(Name = "response_type")] string? responseType)
    {
        if (clientId != _googleHome.ClientId || !GoogleHomeService.IsAllowedRedirect(redirectUri) || (responseType ?? "code") != "code")
        {
            _logger.LogWarning("[GoogleHome] Rejected auth request client={ClientId} redirect={Redirect}", clientId, redirectUri);
            return BadRequest("Invalid client_id or redirect_uri.");
        }
        var code = _googleHome.IssueAuthCode();
        var separator = redirectUri!.Contains('?') ? "&" : "?";
        return Redirect($"{redirectUri}{separator}code={Uri.EscapeDataString(code)}&state={Uri.EscapeDataString(state ?? "")}");
    }

    [HttpPost("token")]
    [Consumes("application/x-www-form-urlencoded")]
    public IActionResult Token([FromForm] IFormCollection form)
    {
        string? clientId = form["client_id"], clientSecret = form["client_secret"];
        var basic = Request.Headers.Authorization.ToString();
        if (basic.StartsWith("Basic ", StringComparison.OrdinalIgnoreCase))
        {
            var decoded = Encoding.UTF8.GetString(Convert.FromBase64String(basic[6..].Trim())).Split(':', 2);
            if (decoded.Length == 2) { clientId = Uri.UnescapeDataString(decoded[0]); clientSecret = Uri.UnescapeDataString(decoded[1]); }
        }
        if (!_googleHome.ValidateClient(clientId, clientSecret))
            return BadRequest(new { error = "invalid_client" });

        switch (form["grant_type"].ToString())
        {
            case "authorization_code":
                var tokens = _googleHome.RedeemAuthCode(form["code"]);
                if (tokens is null) return BadRequest(new { error = "invalid_grant" });
                return Ok(new
                {
                    token_type = "Bearer",
                    access_token = tokens.Value.accessToken,
                    refresh_token = tokens.Value.refreshToken,
                    expires_in = _googleHome.AccessTokenLifetimeSeconds
                });
            case "refresh_token":
                var access = _googleHome.RefreshAccessToken(form["refresh_token"]);
                if (access is null) return BadRequest(new { error = "invalid_grant" });
                return Ok(new { token_type = "Bearer", access_token = access, expires_in = _googleHome.AccessTokenLifetimeSeconds });
            default:
                return BadRequest(new { error = "unsupported_grant_type" });
        }
    }

    [HttpPost("fulfillment")]
    public async Task<IActionResult> Fulfillment()
    {
        var auth = Request.Headers.Authorization.ToString();
        var token = auth.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) ? auth[7..].Trim() : null;
        if (!_googleHome.ValidateAccessToken(token)) return Unauthorized();

        using var reader = new StreamReader(Request.Body, Encoding.UTF8);
        var body = JsonNode.Parse(await reader.ReadToEndAsync());
        if (body is null) return BadRequest();
        var response = await _googleHome.HandleFulfillmentAsync(body);
        try
        {
            System.IO.File.AppendAllText(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OmniSync", "google_home.log"),
                $"  request:  {body.ToJsonString()}{Environment.NewLine}  response: {response.ToJsonString()}{Environment.NewLine}");
        }
        catch { }
        return Content(response.ToJsonString(), "application/json");
    }
}
