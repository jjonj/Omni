using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Presentation.Controllers
{
    [Route("api/external")]
    [ApiController]
    public class OmniHubApiController : ControllerBase
    {
        private readonly CommandDispatcher _dispatcher;
        private readonly HubMonitorService _monitor;
        private readonly AuthService _authService;
        private readonly AiCliService _aiCliService;
        private readonly ScreenshotService _screenshotService;
        private readonly AssistantService _assistantService;

        public OmniHubApiController(CommandDispatcher dispatcher, HubMonitorService monitor, AuthService authService, AiCliService aiCliService, ScreenshotService screenshotService, AssistantService assistantService)
        {
            _dispatcher = dispatcher;
            _monitor = monitor;
            _authService = authService;
            _aiCliService = aiCliService;
            _screenshotService = screenshotService;
            _assistantService = assistantService;
        }

        [HttpGet("commands")]
        public IActionResult GetCommands([FromQuery] string key)
        {
            if (!_authService.Validate(key)) return Unauthorized();

            var commands = _dispatcher.GetRegisteredCommands();
            return Ok(commands);
        }

        [HttpGet("cli/sessions")]
        public IActionResult ListSessions([FromQuery] string key)
        {
            if (!_authService.Validate(key)) return Unauthorized();

            _monitor.AddLogMessage("[OmniHubAPI] Listing CLI sessions...");
            var sessions = _aiCliService.GetActiveSessions();
            return Ok(sessions);
        }

        [HttpGet("cli/history")]
        public async Task<IActionResult> GetHistory([FromQuery] string key, [FromQuery] int pid, [FromQuery] int maxChars = 0)
        {
            if (!_authService.Validate(key)) return Unauthorized();

            _monitor.AddLogMessage($"[OmniHubAPI] Getting CLI history for PID {pid} (maxChars: {maxChars})");
            
            // The history is broadcast via ResponseReceived event in AiCliService.
            // For a direct REST API call, we need a way to capture the specific response for this PID.
            // For now, we'll trigger the fetch. The client will have to listen to the socket or we'll need a better polling mechanism.
            // However, the spec suggests a unified tool.
            
            await _aiCliService.GetHistoryAsync(pid, maxChars);
            return Ok(new { message = "History request triggered. Results will be broadcast via socket." });
        }

        [HttpPost("screenshot")]
        public IActionResult TakeScreenshot([FromQuery] string key)
        {
            if (!_authService.Validate(key)) return Unauthorized();

            try
            {
                string timestamp = DateTime.Now.ToString("yyyyMMdd_HHmmss");
                string fileName = $"screenshot_{timestamp}.jpg";
                // As per user request: D:\SSDProjects\Omni\OmniSync.Hub\Screenshots
                string screenshotsDir = @"D:\SSDProjects\Omni\OmniSync.Hub\Screenshots";
                string filePath = System.IO.Path.Combine(screenshotsDir, fileName);

                _screenshotService.CapturePrimaryScreen(filePath);
                _monitor.AddLogMessage($"[OmniHubAPI] Screenshot captured: {fileName}");

                return Ok(new { filePath = filePath, fileName = fileName });
            }
            catch (Exception ex)
            {
                _monitor.AddLogMessage($"[OmniHubAPI] Screenshot ERROR: {ex.Message}");
                return StatusCode(500, ex.Message);
            }
        }

        public class AssistantRequest
        {
            public string Command { get; set; } = "";
            public List<string> Args { get; set; } = new();
            public string? ProjectRoot { get; set; }
        }

        [HttpPost("assistant/execute")]
        public async Task<IActionResult> ExecuteAssistant([FromQuery] string key, [FromBody] AssistantRequest request)
        {
            if (!_authService.Validate(key)) return Unauthorized();

            _monitor.AddLogMessage($"[OmniHubAPI] Executing Assistant Command: '{request.Command}' (Args: {string.Join(" ", request.Args)})");

            try
            {
                var result = await _assistantService.ExecuteAsync(request.Command, request.Args, request.ProjectRoot);
                if (result.Success)
                {
                    return Ok(result);
                }
                else
                {
                    _monitor.AddLogMessage($"[OmniHubAPI] Assistant ERROR: {result.Error}");
                    return StatusCode(500, result);
                }
            }
            catch (Exception ex)
            {
                _monitor.AddLogMessage($"[OmniHubAPI] Assistant Exception: {ex.Message}");
                return StatusCode(500, ex.Message);
            }
        }

        [HttpPost("command")]
        public IActionResult Execute([FromQuery] string key, [FromQuery] string cmd, [FromBody] JsonElement payload)
        {
            var ip = Request.HttpContext.Connection.RemoteIpAddress?.ToString();
            
            if (!_authService.Validate(key)) 
            {
                _monitor.AddLogMessage($"External Command UNAUTHORIZED: '{cmd}' (Payload: {payload}) from IP: {ip}");
                return Unauthorized();
            }
            
            _monitor.AddLogMessage($"[OmniHubAPI] External Command Received: '{cmd}' (Payload: {payload}) from IP: {ip}");
            
            try 
            {
                // This leverages your existing CommandDispatcher!
                _dispatcher.Dispatch(cmd.ToUpper(), payload);
                _monitor.OnExternalCommandReceived(cmd);
                return Ok();
            }
            catch (System.Exception ex)
            {
                _monitor.AddLogMessage($"External Command ERROR: '{cmd}' - {ex.Message}");
                return StatusCode(500, ex.Message);
            }
        }
    }
}
