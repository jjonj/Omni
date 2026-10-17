using System.IO;
using System.Net;
using Microsoft.AspNetCore.Mvc;
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Presentation.Controllers;

[ApiController]
[Route("api/ugoira")]
public sealed class UgoiraController : ControllerBase
{
    private readonly UgoiraService _service;

    public UgoiraController(UgoiraService service) => _service = service;

    private bool IsLocal(bool checkOrigin = false)
    {
        var ip = HttpContext.Connection.RemoteIpAddress;
        if (ip == null || !IPAddress.IsLoopback(ip)) return false;
        return !checkOrigin || Request.Headers.Origin.ToString() == $"{Request.Scheme}://{Request.Host}";
    }

    private ObjectResult Refused() => StatusCode(403, new
    {
        error = $"OmniHub only serves Ugoira to its own PC over loopback. This request came from {HttpContext.Connection.RemoteIpAddress}. Open http://127.0.0.1:3333/misc/ugoira/ugoira.html on the Hub PC.",
        remote = HttpContext.Connection.RemoteIpAddress?.ToString(),
        host = Request.Host.Value,
        origin = Request.Headers.Origin.ToString()
    });

    [HttpGet("/misc/ugoira/ugoira.html")]
    public IActionResult Page()
    {
        if (!IsLocal()) return Refused();
        _service.Log($"Page requested; host={Request.Host}; referer={Request.Headers.Referer}; userAgent={Request.Headers.UserAgent}");
        return PhysicalFile(Path.Combine(_service.ContentDirectory, "ugoira.html"), "text/html; charset=utf-8");
    }

    [HttpGet("status")]
    public IActionResult Status() => IsLocal() ? Ok(_service.Snapshot()) : Refused();

    [HttpGet("log")]
    public IActionResult Log() => IsLocal() ? Ok(new { entries = _service.RecentLog() }) : Refused();

    public sealed record BatchRequest(string Text, string Output);

    [HttpPost("start")]
    [RequestSizeLimit(2_000_000)]
    public IActionResult Start([FromBody] BatchRequest request)
    {
        if (!IsLocal(checkOrigin: true)) return Refused();
        try
        {
            _service.Log($"Start requested; host={Request.Host}; origin={Request.Headers.Origin}; characters={request.Text?.Length}; output={request.Output}");
            return _service.TryStart(request.Text ?? "", request.Output ?? "")
                ? Accepted(new { ok = true }) : Conflict(new { error = "A batch is already running." });
        }
        catch (Exception ex) when (ex is ArgumentException or IOException or UnauthorizedAccessException)
        {
            _service.Log($"Start rejected: {ex.Message}");
            return BadRequest(new { error = ex.Message });
        }
    }

    [HttpPost("folder")]
    public async Task<IActionResult> Folder()
    {
        if (!IsLocal(checkOrigin: true)) return Refused();
        _service.Log("Download folder picker requested.");
        var completion = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            try
            {
                using var owner = new System.Windows.Forms.Form
                {
                    FormBorderStyle = System.Windows.Forms.FormBorderStyle.None,
                    ShowInTaskbar = false,
                    StartPosition = System.Windows.Forms.FormStartPosition.Manual,
                    Location = System.Windows.Forms.Cursor.Position,
                    Size = new System.Drawing.Size(1, 1),
                    Opacity = 0.01,
                    TopMost = true
                };
                owner.Show();
                owner.Activate();
                using var dialog = new System.Windows.Forms.FolderBrowserDialog
                {
                    Description = "Choose where to save the ugoira MP4 videos",
                    UseDescriptionForTitle = true,
                    ShowNewFolderButton = true,
                    RootFolder = Environment.SpecialFolder.MyComputer
                };
                var previous = _service.LastOutput;
                if (previous != null && Directory.Exists(previous)) dialog.SelectedPath = previous;
                _service.Log($"Folder dialog opened on the Hub desktop; lastOutput={previous ?? "(none)"}");
                completion.SetResult(dialog.ShowDialog(owner) == System.Windows.Forms.DialogResult.OK ? dialog.SelectedPath : null);
            }
            catch (Exception ex) { completion.SetException(ex); }
        }) { IsBackground = true, Name = "UgoiraFolderPicker" };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        try
        {
            var path = await completion.Task;
            _service.Log(path == null ? "Folder selection cancelled." : $"Folder selected: {path}");
            return Ok(new { path });
        }
        catch (Exception ex)
        {
            _service.Log($"Folder picker ERROR: {ex}");
            return StatusCode(500, new { error = "Could not open the folder picker. Enter a download folder path on the page." });
        }
    }

    [HttpGet("videos/{fileName}")]
    public IActionResult Video(string fileName)
    {
        if (!IsLocal()) return StatusCode(403);
        var path = _service.VideoPath(fileName);
        if (path == null || !System.IO.File.Exists(path)) return NotFound();
        _service.Log($"Video requested: {path}; range={Request.Headers.Range}");
        return PhysicalFile(path, "video/mp4", enableRangeProcessing: true);
    }
}
