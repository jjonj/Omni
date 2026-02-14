const http = require("http");
const { URL } = require("url");
const fs = require("fs");
const path = require("path");
const { spawn } = require("child_process");

const debugLogPath = path.join(__dirname, "..", "omni_extension_debug.log");

function log(msg) {
  const logLine = `[${new Date().toISOString()}] ${msg}\n`;
  try {
    fs.appendFileSync(debugLogPath, logLine);
  } catch (e) {}
}

function discoverProjectRoot() {
  // Simple discovery: look for .git or .omni or .athena_root starting from current __dirname
  let current = __dirname;
  while (current !== path.parse(current).root) {
    if (fs.existsSync(path.join(current, ".git")) || 
        fs.existsSync(path.join(current, ".omni")) || 
        fs.existsSync(path.join(current, ".athena_root"))) {
      return current;
    }
    current = path.dirname(current);
  }
  // Fallback to parent of omni-extension
  return path.resolve(__dirname, "..", "..");
}

const PROJECT_ROOT = discoverProjectRoot();
log(`Resolved Project Root: ${PROJECT_ROOT}`);

async function runPythonAthenaCommand(args, cwd = null) {
  const targetRoot = cwd || PROJECT_ROOT;
  log(`Requesting Hub to run Athena command: ${args.join(" ")} in ${targetRoot}`);
  try {
    const response = await postToHubApi("assistant/execute", {
      Command: args[0],
      Args: args.slice(1),
      ProjectRoot: targetRoot
    });
    
    if (response.success) {
      return response.output;
    } else {
      throw new Error(response.error || response.output || "Unknown Athena error");
    }
  } catch (error) {
    log(`Hub Athena Execution Error: ${error.message}`);
    throw error;
  }
}

function sendResponse(id, result) {
  if (id === undefined || id === null) return;
  const response = JSON.stringify({ jsonrpc: "2.0", id, result }) + "\n";
  process.stdout.write(response);
}

function sendError(id, code, message) {
  const response = JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }) + "\n";
  process.stdout.write(response);
}

function callHubApi(command, payload) {
  return new Promise((resolve, reject) => {
    let hubUrl = process.env.OMNI_HUB_URL || "http://localhost:5000";
    const apiKey = process.env.OMNI_HUB_KEY || "test_api_key";
    if (hubUrl.includes(":3333")) hubUrl = hubUrl.replace(":3333", ":5000");
    const url = new URL(`${hubUrl}/api/external/command`);
    url.searchParams.append("key", apiKey);
    url.searchParams.append("cmd", command);
    const body = JSON.stringify(payload);
    const options = { method: "POST", headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) } };
    const req = http.request(url, options, (res) => { if (res.statusCode >= 200 && res.statusCode < 300) resolve(); else reject(new Error(`Hub API returned status ${res.statusCode}`)); });
    req.on("error", (e) => reject(e));
    req.write(body);
    req.end();
  });
}

function postToHubApi(endpoint, payload) {
  return new Promise((resolve, reject) => {
    let hubUrl = process.env.OMNI_HUB_URL || "http://localhost:5000";
    const apiKey = process.env.OMNI_HUB_KEY || "test_api_key";
    if (hubUrl.includes(":3333")) hubUrl = hubUrl.replace(":3333", ":5000");
    const url = new URL(`${hubUrl}/api/external/${endpoint}`);
    url.searchParams.append("key", apiKey);
    const body = JSON.stringify(payload);
    const options = { method: "POST", headers: { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(body) } };
    const req = http.request(url, options, (res) => {
      let data = "";
      res.on("data", (chunk) => data += chunk);
      res.on("end", () => { if (res.statusCode >= 200 && res.statusCode < 300) { try { resolve(JSON.parse(data)); } catch (e) { resolve(data); } } else reject(new Error(`Hub API status ${res.statusCode}`)); });
    });
    req.on("error", (e) => reject(e));
    req.write(body);
    req.end();
  });
}

function fetchFromHubApi(endpoint, params = {}) {
  return new Promise((resolve, reject) => {
    let hubUrl = process.env.OMNI_HUB_URL || "http://localhost:5000";
    const apiKey = process.env.OMNI_HUB_KEY || "test_api_key";
    if (hubUrl.includes(":3333")) hubUrl = hubUrl.replace(":3333", ":5000");
    const url = new URL(`${hubUrl}/api/external/${endpoint}`);
    url.searchParams.append("key", apiKey);
    for (const [k, v] of Object.entries(params)) url.searchParams.append(k, v);
    const req = http.get(url, (res) => {
      let data = "";
      res.on("data", (chunk) => data += chunk);
      res.on("end", () => { if (res.statusCode >= 200 && res.statusCode < 300) { try { resolve(JSON.parse(data)); } catch (e) { resolve(data); } } else reject(new Error(`Hub API status ${res.statusCode}`)); });
    });
    req.on("error", (e) => reject(e));
  });
}

process.on("uncaughtException", (err) => {
  log(`Uncaught Exception: ${err.message}\n${err.stack}`);
  process.exit(1);
});

async function main() {
  log("Omni MCP Server starting...");

  process.stdin.setEncoding("utf8");
  const input = process.stdin;
  let buffer = "";

  input.on("data", async (chunk) => {
    buffer += chunk;
    await processBuffer();
  });

  input.on("end", () => {
    log("stdin closed, waiting 2s for async ops...");
    setTimeout(() => {
        log("Exiting now.");
        process.exit(0);
    }, 2000);
  });

  async function processBuffer() {
    let newlineIndex;
    while ((newlineIndex = buffer.indexOf("\n")) !== -1) {
      const line = buffer.slice(0, newlineIndex);
      buffer = buffer.slice(newlineIndex + 1);
      if (line.trim()) {
        try {
          const request = JSON.parse(line);
          await handleRequest(request);
        } catch (e) {
          log(`Failed to parse JSON: ${line} - Error: ${e.message}`);
        }
      }
    }
  }

  async function handleRequest(req) {
    try {
      if (req.method === "list_tools" || req.method === "tools/list") {
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
            },
            {
              name: "list_cli_sessions",
              description: "Lists active Gemini CLI sessions managed by the Hub",   
              inputSchema: { type: "object", properties: {} }
            },
            {
              name: "send_cli_message",
              description: "Injects a message into another active Gemini CLI session",
              inputSchema: {
                type: "object",
                properties: {
                  pid: { type: "integer", description: "Process ID of the target session" },
                  message: { type: "string", description: "The prompt to inject" }  
                },
                required: ["pid", "message"]
              }
            },
            {
              name: "get_cli_history",
              description: "Retrieves the history of a specific Gemini CLI session",
              inputSchema: {
                type: "object",
                properties: {
                  pid: { type: "integer", description: "Process ID of the target session" },
                  max_chars: { type: "integer", description: "Optional limit for historical context" }
                },
                required: ["pid"]
              }
            },
            {
              name: "take_screenshot",
              description: "Takes a screenshot of the primary monitor and returns the file path",
              inputSchema: { type: "object", properties: {} }
            },
            {
              name: "quicksave",
              description: "Save a checkpoint to the current session log via Athena",
              inputSchema: {
                type: "object",
                properties: {
                  summary: { type: "string", description: "Brief description of activity" },
                  bullets: { type: "array", items: { type: "string" }, description: "Optional detail bullets" }
                },
                required: ["summary"]
              }
            },
            {
              name: "smart_search",
              description: "Search Athena's sovereign memory and project context",
              inputSchema: {
                type: "object",
                properties: {
                  query: { type: "string", description: "The search query" },
                  limit: { type: "integer", description: "Max results", default: 10 }
                },
                required: ["query"]
              }
            },
            {
              name: "opc_sync",
              description: "Synchronize Omni Project Context (OPC) tracking",
              inputSchema: {
                type: "object",
                properties: {
                  project_root: { type: "string", description: "Optional project root override" }
                }
              }
            },
            {
              name: "omni_setup",
              description: "Initialize the Omni Assistant workspace (scaffold .omni and templates)",
              inputSchema: {
                type: "object",
                properties: {
                  project_root: { type: "string", description: "The project root to initialize" }
                },
                required: ["project_root"]
              }
            }
          ]
        });
      } else if (req.method === "call_tool" || req.method === "tools/call") {       
        const toolName = req.params.name;
        const args = req.params.arguments || {};

        if (toolName === "open_resource") {
          const { path: resourcePath, line_number } = args;
          try {
            await callHubApi("OPEN_RESOURCE", { Path: resourcePath, LineNumber: line_number });
            sendResponse(req.id, { content: [{ type: "text", text: `Successfully opened: ${resourcePath}` }] });
          } catch (error) {
            sendResponse(req.id, { isError: true, content: [{ type: "text", text: `Error: ${error.message}` }] });
          }
        } else if (toolName === "list_cli_sessions") {
          try {
            const sessions = await fetchFromHubApi("cli/sessions");
            sendResponse(req.id, { content: [{ type: "text", text: JSON.stringify(sessions, null, 2) }] });
          } catch (error) {
            sendResponse(req.id, { isError: true, content: [{ type: "text", text: `Error: ${error.message}` }] });
          }
        } else if (toolName === "send_cli_message") {
          const { pid, message } = args;
          try {
            await callHubApi("SEND_CLI_MESSAGE", { Pid: pid, Message: message });   
            sendResponse(req.id, { content: [{ type: "text", text: `Message sent to PID ${pid}` }] });
          } catch (error) {
            sendResponse(req.id, { isError: true, content: [{ type: "text", text: `Error: ${error.message}` }] });
          }
        } else if (toolName === "get_cli_history") {
          const { pid, max_chars } = args;
          try {
            const result = await fetchFromHubApi("cli/history", { pid, maxChars: max_chars || 0 });
            sendResponse(req.id, { content: [{ type: "text", text: result.message || JSON.stringify(result) }] });
          } catch (error) {
            sendResponse(req.id, { isError: true, content: [{ type: "text", text: `Error: ${error.message}` }] });
          }
        } else if (toolName === "take_screenshot") {
          try {
            const result = await postToHubApi("screenshot", {});
            sendResponse(req.id, { content: [{ type: "text", text: `Screenshot saved to: ${result.filePath}` }] });
          } catch (error) {
            sendResponse(req.id, { isError: true, content: [{ type: "text", text: `Error: ${error.message}` }] });
          }
        } else if (toolName === "quicksave") {
          const { summary, bullets, project_root } = args;
          try {
            const cmdArgs = ["save", summary];
            if (bullets && bullets.length > 0) {
              bullets.forEach(b => { cmdArgs.push("--bullets"); cmdArgs.push(b); });
            }
            const output = await runPythonAthenaCommand(cmdArgs, project_root);
            sendResponse(req.id, { content: [{ type: "text", text: output }] });
          } catch (error) {
            sendResponse(req.id, { isError: true, content: [{ type: "text", text: `Quicksave Error: ${error.message}` }] });
          }
        } else if (toolName === "smart_search") {
          const { query, limit } = args;
          try {
            const output = await runPythonAthenaCommand(["omni:search", query, "--limit", (limit || 10).toString(), "--json"]);
            sendResponse(req.id, { content: [{ type: "text", text: output }] });
          } catch (error) {
            sendResponse(req.id, { isError: true, content: [{ type: "text", text: `Omni Search Error: ${error.message}` }] });
          }
        } else if (toolName === "opc_sync") {
          try {
            const output = await runPythonAthenaCommand(["omni:sync"], args.project_root);
            sendResponse(req.id, { content: [{ type: "text", text: output }] });
          } catch (error) {
            sendResponse(req.id, { isError: true, content: [{ type: "text", text: `OPC Sync Error: ${error.message}` }] });
          }
        } else if (toolName === "omni_setup") {
          try {
            const output = await runPythonAthenaCommand(["init"], args.project_root);
            sendResponse(req.id, { content: [{ type: "text", text: output }] });
          } catch (error) {
            sendResponse(req.id, { isError: true, content: [{ type: "text", text: `Setup Error: ${error.message}` }] });
          }
        } else {
          sendError(req.id, -32601, `Tool not found: ${toolName}`);
        }
      } else if (req.method === "initialize") {
        sendResponse(req.id, {
          protocolVersion: "2024-11-05",
          capabilities: { tools: {} },
          serverInfo: { name: "omni", version: "1.0.0" }
        });
      } else if (req.method === "notifications/initialized") {
        log("Initialized notification received");
      } else {
        if (req.id !== undefined && req.id !== null) {
          sendError(req.id, -32601, `Method not found: ${req.method}`);
        }
      }
    } catch (e) {
      log(`Error handling request: ${e.message}`);
      if (req.id !== undefined && req.id !== null) {
        sendError(req.id, -32603, `Internal error: ${e.message}`);
      }
    }
  }
}

main();
