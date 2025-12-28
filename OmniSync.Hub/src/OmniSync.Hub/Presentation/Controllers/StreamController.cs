using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.StaticFiles;
using System.IO;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Presentation.Controllers
{
    [Route("api")]
    [ApiController]
    public class StreamController : ControllerBase
    {
        private readonly ScreenshotService _screenshotService;

        public StreamController(ScreenshotService screenshotService)
        {
            _screenshotService = screenshotService;
        }

        [HttpGet("stream")]
        public IActionResult GetVideo([FromQuery] string path)
        {
            if (string.IsNullOrEmpty(path) || !System.IO.File.Exists(path))
            {
                return NotFound();
            }

            // Security Check: Ensure the path is within allowed directories if necessary
            // For now, we are allowing full access as per Task 2 requirements for browsing
            
            var provider = new FileExtensionContentTypeProvider();
            if (!provider.TryGetContentType(path, out string contentType))
            {
                contentType = "application/octet-stream";
            }

            // "enableRangeProcessing: true" allows ExoPlayer to seek (jump forward/backward)
            return PhysicalFile(path, contentType, enableRangeProcessing: true);
        }

        [HttpGet("screenshot")]
        public IActionResult GetScreenshot()
        {
            var data = _screenshotService.CapturePrimaryScreenToMemory();
            if (data == null || data.Length == 0) return NotFound();
            return File(data, "image/jpeg");
        }
    }
}
