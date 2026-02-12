#!/usr/bin/env python3
import asyncio
import json
import logging
import os
import socket
import sys
import tempfile
import time
from signalrcore.hub_connection_builder import HubConnectionBuilder

HUB_URL = "http://127.0.0.1:5000/signalrhub"
API_KEY = "test_api_key"
HUB_PORT = 5000
DIALOG_TYPE = "tool:simulated-dialog-1"
DIALOG_PROMPT = "Simulated tool approval from fake gemini process"

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("HubDialogSimulationTest")


def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        return sock.connect_ex(("127.0.0.1", port)) == 0


class HubDialogSimulationTester:
    def __init__(self):
        self.loop = None
        self.hub = None
        self.connected = False
        self.fake_pid = None
        self.fake_pipe_connected = asyncio.Event()
        self.fake_dialog_response_line = None
        self.fake_dialog_response_event = asyncio.Event()
        self.dialog_received_event = asyncio.Event()
        self.session_seen_event = asyncio.Event()
        self.received_dialog = None
        self.session_pids = set()
        self.reader_tasks = []
        self.fake_proc = None
        self.fake_js_path = None

    def _on_open(self):
        logger.info("SignalR connected.")
        self.connected = True

    def _on_error(self, data):
        logger.error(f"SignalR error: {data}")

    def _on_ai_sessions(self, args):
        try:
            payload = args[0] if args else []
            pids = set()
            if isinstance(payload, list):
                for item in payload:
                    if isinstance(item, dict):
                        pid = item.get("pid", item.get("Pid"))
                        if isinstance(pid, int):
                            pids.add(pid)
                    elif isinstance(item, int):
                        pids.add(item)
            self.session_pids = pids
            if self.fake_pid in self.session_pids:
                logger.info(f"Fake PID {self.fake_pid} is visible in hub sessions.")
                if self.loop:
                    self.loop.call_soon_threadsafe(self.session_seen_event.set)
        except Exception as ex:
            logger.warning(f"Failed to parse ReceiveAiSessions payload: {ex}")

    def _on_ai_dialog(self, args):
        if len(args) < 3:
            return
        pid = args[0]
        dtype = args[1]
        prompt = args[2]
        logger.info(f"ReceiveAiDialog -> pid={pid}, type={dtype}, prompt={prompt}")
        if pid == self.fake_pid and dtype == DIALOG_TYPE:
            self.received_dialog = {"pid": pid, "type": dtype, "prompt": prompt}
            if self.loop:
                self.loop.call_soon_threadsafe(self.dialog_received_event.set)

    async def _read_fake_stdout(self):
        while self.fake_proc and self.fake_proc.stdout:
            line = await self.fake_proc.stdout.readline()
            if not line:
                break
            text = line.decode("utf-8", errors="replace").rstrip()
            logger.info(f"[fake-gemini] {text}")

            if text.startswith("FAKE_GEMINI_PID:"):
                try:
                    self.fake_pid = int(text.split(":", 1)[1].strip())
                except ValueError:
                    pass
            elif text.startswith("PIPE_CONNECTED"):
                self.fake_pipe_connected.set()
            elif text.startswith("DIALOG_RESPONSE_JSON:"):
                self.fake_dialog_response_line = text.split(":", 1)[1].strip()
                self.fake_dialog_response_event.set()

    async def _read_fake_stderr(self):
        while self.fake_proc and self.fake_proc.stderr:
            line = await self.fake_proc.stderr.readline()
            if not line:
                break
            text = line.decode("utf-8", errors="replace").rstrip()
            logger.warning(f"[fake-gemini:stderr] {text}")

    async def _start_fake_gemini(self):
        fake_js = f"""
const net = require('net');
const DIALOG_TYPE = {json.dumps(DIALOG_TYPE)};
const DIALOG_PROMPT = {json.dumps(DIALOG_PROMPT)};
const pid = process.pid;
const pipeName = `\\\\\\\\.\\\\pipe\\\\omni-gemini-cli-${{pid}}`;
console.log(`FAKE_GEMINI_PID:${{pid}}`);

function safeWrite(socket, payload) {{
  if (!socket.destroyed && socket.writable) {{
    try {{
      socket.write(payload + "\\n");
      return true;
    }} catch (_e) {{
      return false;
    }}
  }}
  return false;
}}

const server = net.createServer((socket) => {{
  console.log("PIPE_CONNECTED");

  socket.on("error", (err) => {{
    console.log("SOCKET_ERROR:" + err.code);
  }});
  socket.on("close", () => {{
    console.log("SOCKET_CLOSED");
  }});

  // Delay emission briefly to avoid writing to a connection the client immediately tears down.
  setTimeout(() => {{
    safeWrite(socket, JSON.stringify({{ type: "dialog", dialogType: "ready", prompt: "fake ready", options: [] }}));
  }}, 250);

  setTimeout(() => {{
    safeWrite(socket, JSON.stringify({{
      type: "dialog",
      dialogType: DIALOG_TYPE,
      prompt: DIALOG_PROMPT,
      options: ["yes", "no"]
    }}));
  }}, 1000);

  let buffer = "";
  socket.on("data", (chunk) => {{
    buffer += chunk.toString("utf8");
    const lines = buffer.split(/\\r?\\n/);
    buffer = lines.pop() || "";
    for (const line of lines) {{
      if (!line.trim()) continue;
      try {{
        const msg = JSON.parse(line);
        if (msg.command === "dialogResponse") {{
          console.log("DIALOG_RESPONSE_JSON:" + JSON.stringify(msg));
        }} else {{
          console.log("PIPE_RECV:" + line);
        }}
      }} catch (_e) {{
        console.log("PIPE_RECV_RAW:" + line);
      }}
    }}
  }});
}});

server.listen(pipeName, () => {{
  console.log(`PIPE_LISTENING:${{pipeName}}`);
}});

setInterval(() => {{}}, 1000);
"""
        fd, path = tempfile.mkstemp(prefix="simulated_gemini_", suffix=".js", text=True)
        os.close(fd)
        with open(path, "w", encoding="utf-8") as f:
            f.write(fake_js)
        self.fake_js_path = path

        self.fake_proc = await asyncio.create_subprocess_exec(
            "node",
            self.fake_js_path,
            "omni_gemini",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )

        self.reader_tasks = [
            asyncio.create_task(self._read_fake_stdout()),
            asyncio.create_task(self._read_fake_stderr()),
        ]

        start = time.time()
        while self.fake_pid is None and time.time() - start < 10:
            await asyncio.sleep(0.1)

        if self.fake_pid is None:
            raise RuntimeError("Failed to get fake gemini PID from node process.")

    async def _stop_fake_gemini(self):
        if self.fake_proc and self.fake_proc.returncode is None:
            self.fake_proc.terminate()
            try:
                await asyncio.wait_for(self.fake_proc.wait(), timeout=5)
            except asyncio.TimeoutError:
                self.fake_proc.kill()
                await self.fake_proc.wait()
        for task in self.reader_tasks:
            task.cancel()
            try:
                await task
            except Exception:
                pass
        if self.fake_js_path and os.path.exists(self.fake_js_path):
            try:
                os.remove(self.fake_js_path)
            except OSError:
                pass

    async def run(self):
        if not is_port_in_use(HUB_PORT):
            logger.error(f"Hub is not running on port {HUB_PORT}.")
            return 1

        self.loop = asyncio.get_running_loop()
        await self._start_fake_gemini()
        logger.info(f"Started fake gemini process PID={self.fake_pid}")

        self.hub = (
            HubConnectionBuilder()
            .with_url(HUB_URL)
            .configure_logging(logging.WARNING)
            .build()
        )
        self.hub.on("ReceiveAiSessions", self._on_ai_sessions)
        self.hub.on("ReceiveAiDialog", self._on_ai_dialog)
        self.hub.on_open(self._on_open)
        self.hub.on_error(self._on_error)
        self.hub.start()

        try:
            for _ in range(15):
                if self.connected:
                    break
                await asyncio.sleep(0.2)
            if not self.connected:
                logger.error("Failed to connect to SignalR hub.")
                return 1

            self.hub.send("Authenticate", [API_KEY])
            await asyncio.sleep(0.5)

            # Drive discovery until the fake PID appears (WMI cache can delay this).
            start = time.time()
            while time.time() - start < 90:
                if self.fake_pid in self.session_pids:
                    break
                self.hub.send("GetAiSessions", [])
                await asyncio.sleep(1)
            if self.fake_pid not in self.session_pids:
                logger.error("Fake PID never appeared in ReceiveAiSessions.")
                return 1

            # Wait until the hub reads our simulated dialog event.
            await asyncio.wait_for(self.dialog_received_event.wait(), timeout=20)

            logger.info(f"Sending SendAiDialogResponse('yes', {self.fake_pid})")
            self.hub.send("SendAiDialogResponse", ["yes", self.fake_pid])

            await asyncio.wait_for(self.fake_dialog_response_event.wait(), timeout=15)

            payload = json.loads(self.fake_dialog_response_line)
            logger.info(f"Captured outbound dialogResponse payload: {payload}")
            if payload.get("command") != "dialogResponse":
                logger.error("Payload command is not dialogResponse.")
                return 1
            if payload.get("response") != "yes":
                logger.error("Payload response is not 'yes'.")
                return 1
            if payload.get("dialogType") != DIALOG_TYPE:
                logger.error(
                    f"Payload dialogType mismatch. expected={DIALOG_TYPE}, actual={payload.get('dialogType')}"
                )
                return 1

            logger.info("PASS: Hub returned dialogResponse with preserved dialogType.")
            return 0
        except asyncio.TimeoutError as ex:
            logger.error(f"Timed out during test: {ex}")
            return 1
        finally:
            try:
                if self.hub:
                    self.hub.stop()
            except Exception:
                pass
            await self._stop_fake_gemini()


async def main():
    print("\n" + "=" * 72)
    print("  OMNIHUB DIALOG ROUTING TEST (SIMULATED GEMINI PIPE)")
    print("=" * 72 + "\n")

    tester = HubDialogSimulationTester()
    code = await tester.run()

    if code == 0:
        print("\nOVERALL STATUS: SUCCESS")
    else:
        print("\nOVERALL STATUS: FAILED")
    print("\n" + "=" * 72)
    sys.exit(code)


if __name__ == "__main__":
    asyncio.run(main())
