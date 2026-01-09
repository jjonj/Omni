using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.StaticFiles;
using System.IO;
using OmniSync.Hub.Infrastructure.Services;
using Microsoft.Extensions.Logging;

namespace OmniSync.Hub.Presentation.Controllers
{
    [Route("api")]
    [ApiController]
    public class StreamController : ControllerBase
    {
        private readonly ScreenshotService _screenshotService;
        private readonly ILogger<StreamController> _logger;

        public StreamController(ScreenshotService screenshotService, ILogger<StreamController> logger)
        {
            _screenshotService = screenshotService;
            _logger = logger;
        }

        [HttpGet("stream")]
        public IActionResult GetVideo([FromQuery] string path)
        {
            if (string.IsNullOrEmpty(path) || !System.IO.File.Exists(path))
            {
                _logger.LogWarning("GetVideo: Path not found: {Path}", path);
                return NotFound();
            }

            // Security Check: Ensure the path is within allowed directories if necessary
            // For now, we are allowing full access as per Task 2 requirements for browsing
            
            var provider = new FileExtensionContentTypeProvider();
            if (!provider.TryGetContentType(path, out string contentType))
            {
                contentType = "application/octet-stream";
            }

            _logger.LogInformation("Streaming video: {Path}", path);
            // "enableRangeProcessing: true" allows ExoPlayer to seek (jump forward/backward)
            return PhysicalFile(path, contentType, enableRangeProcessing: true);
        }

        [HttpGet("screenshot")]
        public IActionResult GetScreenshot([FromQuery] double scale = 1.0, [FromQuery] long quality = 50L)
        {
            _logger.LogDebug("GetScreenshot request: scale={Scale}, quality={Quality}", scale, quality);
            var data = _screenshotService.CapturePrimaryScreenToMemory(scale, quality);
            if (data == null || data.Length == 0)
            {
                _logger.LogWarning("GetScreenshot: Failed to capture screen.");
                return NotFound();
            }
            return File(data, "image/jpeg");
        }
    }
}
