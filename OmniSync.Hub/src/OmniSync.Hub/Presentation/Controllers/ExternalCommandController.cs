using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using OmniSync.Hub.Logic.Services;
using OmniSync.Hub.Logic.Monitoring;

namespace OmniSync.Hub.Presentation.Controllers
{
    [Route("api/external")]
    [ApiController]
    public class ExternalCommandController : ControllerBase
    {
        private readonly CommandDispatcher _dispatcher;
        private readonly HubMonitorService _monitor;
        private readonly AuthService _authService;

        public ExternalCommandController(CommandDispatcher dispatcher, HubMonitorService monitor, AuthService authService)
        {
            _dispatcher = dispatcher;
            _monitor = monitor;
            _authService = authService;
        }

        [HttpGet("commands")]
        public IActionResult GetCommands([FromQuery] string key)
        {
            if (!_authService.Validate(key)) return Unauthorized();

            var commands = _dispatcher.GetRegisteredCommands();
            return Ok(commands);
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