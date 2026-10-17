# Omni MCP implementations (current)

| Server | Owner | Local MCP path | Scheduled task |
| --- | --- | --- | --- |
| Rust OmniHub MCP | `D:/SSDProjects/Tools/Omnish/main` | `http://127.0.0.1:8770/mcp` | `OmniHub Rust MCP` |
| Standalone OmniMCP-Lite | `D:/SSDProjects/Tools/McpHelloProbe` | `http://127.0.0.1:8771/mcp` | `OmniMCP-Lite` |

Both are standalone, independent servers. The production Rust Hub's main
API and Gateway listeners are 5000 and 3333, separately from the MCP sidecar.

The retired C# `OmniSync.Hub` MCP implementation was removed. Its former tool
catalog and Kestrel configuration are not authoritative for either server.
For tool names/schemas, consult the live MCP plugin and the source in the
respective owning repositories. Remote routing/authentication is managed
separately; never publish loopback tools unauthenticated by changing these docs.

The independent Lite supervisor is maintained here at
`scripts/services/omnimcp_lite_watchdog.py`. Live operational guide:
`docs/SERVICE_RECOVERY.md`.
