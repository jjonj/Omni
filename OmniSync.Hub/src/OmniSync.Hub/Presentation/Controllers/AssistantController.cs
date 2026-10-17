using Microsoft.AspNetCore.Mvc;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Logic.Services.AssistantCommands;

namespace OmniSync.Hub.Presentation.Controllers;

[ApiController]
[Route("api/assistant")]
public sealed class AssistantController : ControllerBase
{
    private const string ApiKeyHeader = "X-Omni-Api-Key";
    private readonly AuthService _auth;
    private readonly AssistantCommandCatalog _catalog;
    private readonly AssistantRunStore _runs;
    private readonly AssistantRunEventSink _events;
    private readonly TimeProvider _timeProvider;

    public AssistantController(
        AuthService auth,
        AssistantCommandCatalog catalog,
        AssistantRunStore runs,
        AssistantRunEventSink events,
        TimeProvider timeProvider)
    {
        _auth = auth;
        _catalog = catalog;
        _runs = runs;
        _events = events;
        _timeProvider = timeProvider;
    }

    [HttpGet("commands")]
    public IActionResult GetCommands()
    {
        if (!Authorized()) return Unauthorized();
        return Ok(new
        {
            active = _catalog.Descriptors,
            rejected = _catalog.Rejected
        });
    }

    [HttpGet("runs")]
    public IActionResult GetRuns()
    {
        if (!Authorized()) return Unauthorized();
        return Ok(_runs.GetAll());
    }

    [HttpPost("runs/{runId:guid}/cancel")]
    public async Task<IActionResult> Cancel(Guid runId)
    {
        if (!Authorized()) return Unauthorized();

        if (!_runs.TryCancel(
                runId,
                _timeProvider.GetUtcNow(),
                out var updated))
        {
            return updated is null
                ? NotFound()
                : Conflict(new { message = $"Run is already {updated.State}." });
        }

        await _events.PublishAsync(updated!);
        return Ok(updated);
    }

    private bool Authorized()
        => _auth.Validate(Request.Headers[ApiKeyHeader].ToString());
}
