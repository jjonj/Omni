using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using OmniSync.Hub.Logic.Services;

namespace OmniSync.Hub.Presentation.Controllers
{
    [Route("api/external")]
    [ApiController]
    public class ExternalCommandController : ControllerBase
    {
        private readonly CommandDispatcher _dispatcher;
        private readonly string _apiKey = "test_api_key"; // Match your appsettings.json

        public ExternalCommandController(CommandDispatcher dispatcher)
        {
            _dispatcher = dispatcher;
        }

        [HttpPost("command")]
        public IActionResult Execute([FromQuery] string key, [FromQuery] string cmd, [FromBody] JsonElement payload)
        {
            if (key != _apiKey) return Unauthorized();
            
            // This leverages your existing CommandDispatcher!
            _dispatcher.Dispatch(cmd.ToUpper(), payload);
            return Ok();
        }
    }
}