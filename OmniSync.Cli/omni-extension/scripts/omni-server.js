const http = require("http");
const { URL } = require("url");
const fs = require("fs");
const path = require("path");

const debugLogPath = path.join(__dirname, "..", "debug.log");

function log(msg) {
  const logLine = `[${new Date().toISOString()}] ${msg}\n`;
  try {
    fs.appendFileSync(debugLogPath, logLine);
  } catch (e) {}
}

async function main() {
  log("Omni MCP Server starting...");
  const input = process.stdin;
  let buffer = "";

  input.on("data", (chunk) => {
    buffer += chunk.toString();
    processBuffer();
  });

  function processBuffer() {
    let newlineIndex;
    while ((newlineIndex = buffer.indexOf("\n")) !== -1) {
      const line = buffer.slice(0, newlineIndex);
      buffer = buffer.slice(newlineIndex + 1);
      if (line.trim()) {
        try {
          const request = JSON.parse(line);
          log(`Received message: ${request.method} (ID: ${request.id})`);
          handleRequest(request);
        } catch (e) {
          log(`Failed to parse JSON: ${line} - Error: ${e.message}`);
        }
      }
    }
  }

  async function handleRequest(req) {
    try {
      if (req.method === "initialize") {
        sendResponse(req.id, {
          protocolVersion: "2024-11-05",
          capabilities: { tools: {} },
          serverInfo: { name: "omni", version: "1.0.0" }
        });
      } else if (req.method === "list_tools" || req.method === "tools/list") {
        sendResponse(req.id, {
          tools: [
            {
              name: "open_resource",
              description: "Open a file, folder, or URL on the Windows PC",
              inputSchema: {
                type: "object",
                properties: {
                  path: { type: "string", description: "Absolute path or URL" },
                  line_number: { type: "integer", description: "Optional line number for Notepad++" }
                },
                required: ["path"]
              }
            }
          ]
        });
      } else if (req.method === "call_tool" || req.method === "tools/call") {
        // Support both variants just in case
        const params = req.params || {};
        const toolName = params.name || (params.arguments ? params.arguments.name : null);
        const args = params.arguments || {};

        if (toolName === "open_resource") {
          const { path: resourcePath, line_number } = args;
          log(`Calling open_resource for: ${resourcePath}`);
          try {
            await callHubApi(resourcePath, line_number);
            sendResponse(req.id, { content: [{ type: "text", text: `Successfully opened: ${resourcePath}` }] });
          } catch (error) {
            log(`Hub API error: ${error.message}`);
            sendResponse(req.id, {
              isError: true, 
              content: [{ type: "text", text: `Error: ${error.message}` }]
            });
          }
        } else {
          sendError(req.id, -32601, `Tool not found: ${toolName}`);
        }
      } else if (req.method === "notifications/initialized") {
        log("Initialized notification received");
      } else if (req.id !== undefined && req.id !== null) {
        log(`Unhandled method with ID: ${req.method}`);
        // Always respond to requests with IDs to avoid hanging the client
        sendResponse(req.id, {}); 
      }
    } catch (e) {
      log(`Error handling request: ${e.message}`);
      if (req.id !== undefined && req.id !== null) {
        sendError(req.id, -32603, `Internal error: ${e.message}`);
      }
    }
  }

  function sendResponse(id, result) {
    if (id === undefined || id === null) return;
    const response = JSON.stringify({ jsonrpc: "2.0", id, result }) + "\n";
    process.stdout.write(response);
    log(`Sent response for ID ${id}`);
  }

  function sendError(id, code, message) {
    if (id === undefined || id === null) return;
    const response = JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }) + "\n";
    process.stdout.write(response);
    log(`Sent error for ID ${id}: ${message}`);
  }

  function callHubApi(resourcePath, lineNumber) {
    return new Promise((resolve, reject) => {
      let hubUrl = process.env.OMNI_HUB_URL || "http://localhost:5000";
      const apiKey = process.env.OMNI_HUB_KEY || "test_api_key";

      if (hubUrl.includes(":3333")) {
        hubUrl = hubUrl.replace(":3333", ":5000");
      }

      const url = new URL(`${hubUrl}/api/external/command`);
      url.searchParams.append("key", apiKey);
      url.searchParams.append("cmd", "OPEN_RESOURCE");

      const body = JSON.stringify({
        Path: resourcePath,
        LineNumber: lineNumber
      });

      const options = {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(body)
        }
      };

      const req = http.request(url, options, (res) => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve();
        } else {
          reject(new Error(`Hub API returned status ${res.statusCode}`));
        }
      });

      req.on("error", (e) => reject(e));
      req.write(body);
      req.end();
    });
  }
}

main();
