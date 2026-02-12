using System.Text.Json;
using OmniSync.Hub.Infrastructure.Services;
using Xunit;

namespace OmniSync.Hub.Tests.Services
{
    public class GeminiSessionPayloadTests
    {
        [Fact]
        public void BuildCommandPayload_IncludesDialogType_WhenProvided()
        {
            string payload = GeminiSession.BuildCommandPayload(
                "dialogResponse",
                "yes",
                "response",
                0,
                "tool:abc123");

            using var doc = JsonDocument.Parse(payload);
            var root = doc.RootElement;

            Assert.Equal("dialogResponse", root.GetProperty("command").GetString());
            Assert.Equal("yes", root.GetProperty("response").GetString());
            Assert.Equal("tool:abc123", root.GetProperty("dialogType").GetString());
        }

        [Fact]
        public void BuildCommandPayload_OmitsDialogType_WhenNotProvided()
        {
            string payload = GeminiSession.BuildCommandPayload(
                "dialogResponse",
                "yes",
                "response");

            using var doc = JsonDocument.Parse(payload);
            var root = doc.RootElement;

            Assert.Equal("dialogResponse", root.GetProperty("command").GetString());
            Assert.Equal("yes", root.GetProperty("response").GetString());
            Assert.False(root.TryGetProperty("dialogType", out _));
        }
    }
}
