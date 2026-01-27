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

        public OmniHubApiController(CommandDispatcher dispatcher, HubMonitorService monitor, AuthService authService, AiCliService aiCliService)
        {
            _dispatcher = dispatcher;
            _monitor = monitor;
            _authService = authService;
            _aiCliService = aiCliService;
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

            var sessions = _aiCliService.GetActiveSessions();
            return Ok(sessions);
        }

        [HttpGet("cli/history")]
        public async Task<IActionResult> GetHistory([FromQuery] string key, [FromQuery] int pid, [FromQuery] int maxChars = 0)
        {
            if (!_authService.Validate(key)) return Unauthorized();

            // The history is broadcast via ResponseReceived event in AiCliService.
            // For a direct REST API call, we need a way to capture the specific response for this PID.
            // For now, we'll trigger the fetch. The client will have to listen to the socket or we'll need a better polling mechanism.
            // However, the spec suggests a unified tool.
            
            await _aiCliService.GetHistoryAsync(pid, maxChars);
            return Ok(new { message = "History request triggered. Results will be broadcast via socket." });
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
            
            _monitor.AddLogMessage($"External Command Received: '{cmd}' (Payload: {payload}) from IP: {ip}");
            
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