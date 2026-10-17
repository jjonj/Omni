using Microsoft.AspNetCore.Mvc;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Logic.Services.VoiceInbox;

namespace OmniSync.Hub.Presentation.Controllers;

/// <summary>Entry point for free-text commands from local sources (the Keep bridge, scripts, tests).</summary>
[ApiController]
[Route("api/voice-inbox")]
public sealed class VoiceInboxController : ControllerBase
{
    private const string ApiKeyHeader = "X-Omni-Api-Key";
    private readonly VoiceInboxService _voiceInbox;
    private readonly AuthService _authService;

    public VoiceInboxController(VoiceInboxService voiceInbox, AuthService authService)
    {
        _voiceInbox = voiceInbox;
        _authService = authService;
    }

    public sealed record SubmitRequest(
        string Text,
        string? Source,
        string? SourceId,
        bool IsBacklog = false,
        DateTimeOffset? OccurredAt = null,
        Guid? RequestId = null,
        string? ConversationId = null,
        bool DryRun = false);

    [HttpPost]
    public async Task<IActionResult> Submit([FromBody] SubmitRequest request, CancellationToken cancellationToken)
    {
        if (!_authService.Validate(Request.Headers[ApiKeyHeader].ToString())) return Unauthorized();
        if (string.IsNullOrWhiteSpace(request.Text)) return BadRequest(new { message = "Text is required." });

        var outcome = await _voiceInbox.SubmitAsync(
            new VoiceInboxItem(
                request.Text,
                request.Source ?? "api",
                request.SourceId,
                DateTimeOffset.Now,
                request.IsBacklog,
                request.OccurredAt,
                request.RequestId,
                request.ConversationId,
                request.DryRun),
            cancellationToken);
        return Ok(outcome);
    }
}
