using System.Net;

namespace OmniSync.Hub.Logic.Services.Mcp
{
    /// Resolved MCP listener settings, built once at startup from the "Mcp" config section.
    public sealed record McpOptions(bool Enabled, IPAddress Address, int Port, string Summary);
}
