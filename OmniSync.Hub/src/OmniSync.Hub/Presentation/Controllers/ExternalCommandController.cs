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
        private readonly string _apiKey = "test_api_key"; // Match your appsettings.json

        public ExternalCommandController(CommandDispatcher dispatcher, HubMonitorService monitor)
        {
            _dispatcher = dispatcher;
            _monitor = monitor;
        }

        [HttpPost("command")]
        public IActionResult Execute([FromQuery] string key, [FromQuery] string cmd, [FromBody] JsonElement payload)
        {
            var ip = Request.HttpContext.Connection.RemoteIpAddress?.ToString();
            
            if (key != _apiKey) 
            {
                _monitor.AddLogMessage($"External Command UNAUTHORIZED: '{cmd}' from IP: {ip}");
                return Unauthorized();
            }
            
            _monitor.AddLogMessage($"External Command Received: '{cmd}' from IP: {ip}");
            
            try 
            {
                // This leverages your existing CommandDispatcher!
                _dispatcher.Dispatch(cmd.ToUpper(), payload);
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