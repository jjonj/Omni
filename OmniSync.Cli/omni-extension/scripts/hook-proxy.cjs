const http = require("http");
const { URL } = require("url");
const fs = require("fs");
const path = require("path");

function log(msg) {
  const debugLogPath = path.join(__dirname, "..", "hook_debug.log");
  const logLine = `[${new Date().toISOString()}] ${msg}
`;
  try {
    fs.appendFileSync(debugLogPath, logLine);
  } catch (e) {}
}

async function postToHubApi(endpoint, payload) {
  return new Promise((resolve, reject) => {
    let hubUrl = process.env.OMNI_HUB_URL || "http://localhost:5000";
    const apiKey = process.env.OMNI_HUB_KEY || "test_api_key";
    if (hubUrl.includes(":3333")) hubUrl = hubUrl.replace(":3333", ":5000");
    
    const url = new URL(`${hubUrl}/api/external/${endpoint}`);
    url.searchParams.append("key", apiKey);
    
    const body = JSON.stringify(payload);
    const options = {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Content-Length": Buffer.byteLength(body)
      }
    };

    const req = http.request(url, options, (res) => {
      let data = "";
      res.on("data", (chunk) => data += chunk);
      res.on("end", () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          try { resolve(JSON.parse(data)); } catch (e) { resolve(data); }
        } else {
          reject(new Error(`Hub API status ${res.statusCode}: ${data}`));
        }
      });
    });

    req.on("error", (e) => reject(e));
    req.write(body);
    req.end();
  });
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length === 0) {
    console.error("Usage: node hook-proxy.cjs <command> [args...]");
    process.exit(1);
  }

  const command = args[0];
  const commandArgs = args.slice(1);
  const projectRoot = process.cwd();

  log(`Executing Hook: ${command} ${commandArgs.join(" ")} in ${projectRoot}`);

  try {
    const response = await postToHubApi("assistant/execute", {
      Command: command,
      Args: commandArgs,
      ProjectRoot: projectRoot
    });

    if (response.success) {
      // Print output to stdout for Gemini CLI to capture
      process.stdout.write(response.output || "");
      process.exit(0);
    } else {
      console.error(response.error || response.output || "Unknown Assistant Error");
      process.exit(1);
    }
  } catch (error) {
    log(`Hook Error: ${error.message}`);
    console.error(`Hook Error: ${error.message}`);
    process.exit(1);
  }
}

main();
