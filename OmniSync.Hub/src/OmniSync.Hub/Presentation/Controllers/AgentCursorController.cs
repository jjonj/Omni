using Microsoft.AspNetCore.Mvc;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Presentation.Controllers;

[ApiController]
[Route("api/external/agent-cursor")]
public sealed class AgentCursorController(AgentCursorService cursor, AuthService auth) : ControllerBase
{
    public sealed record MoveRequest(int X, int Y);
    public sealed record ClickRequest(string Button = "left", int Count = 1, string Mode = "auto");

    [HttpGet]
    public Task<IActionResult> Status([FromQuery] string key, CancellationToken ct) =>
        Execute(key, () => cursor.StatusAsync(ct));

    [HttpPost("move")]
    public Task<IActionResult> Move([FromQuery] string key, [FromBody] MoveRequest request, CancellationToken ct) =>
        Execute(key, () => cursor.MoveAsync(request.X, request.Y, ct));

    [HttpPost("click")]
    public Task<IActionResult> Click([FromQuery] string key, [FromBody] ClickRequest request, CancellationToken ct) =>
        Execute(key, () => cursor.ClickAsync(request.Button, request.Count, request.Mode, ct));

    [HttpPost("hide")]
    public Task<IActionResult> Hide([FromQuery] string key, CancellationToken ct) =>
        Execute(key, () => cursor.HideAsync(ct));

    private async Task<IActionResult> Execute<T>(string key, Func<Task<T>> action)
    {
        if (!auth.Validate(key)) return Unauthorized();
        try { return Ok(await action()); }
        catch (ArgumentException ex) { return BadRequest(new { error = ex.Message }); }
        catch (InvalidOperationException ex) { return Conflict(new { error = ex.Message }); }
        catch (System.ComponentModel.Win32Exception ex) { return StatusCode(502, new { error = ex.Message }); }
    }
}
