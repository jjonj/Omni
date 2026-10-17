"""Independent Lite + public Funnel supervisor (Task Scheduler, no Hub dependency).

Local MCP is checked every five seconds; public DNS ingress is checked every minute.
Public checks bypass MagicDNS but keep TLS certificate/SNI validation. State and
rotating logs live in %LOCALAPPDATA%/OmniSync/lite-watchdog. pause.txt pauses repair.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import datetime as dt
import http.client
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import socket
import ssl
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

TASK = "OmniMCP-Lite"
HOST = "desktop2024.tail8699ce.ts.net"
STATE = Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "OmniSync/lite-watchdog"
INIT = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
    "protocolVersion": "2025-06-18", "capabilities": {},
    "clientInfo": {"name": "independent-watchdog", "version": "1"}}}
HELLO = {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
         "params": {"name": "hello_world", "arguments": {}}}


class PublicConnection(http.client.HTTPSConnection):
    def __init__(self, address):
        super().__init__(HOST, timeout=4, context=ssl.create_default_context())
        self.address = address

    def connect(self):
        # Connecting to HOST here would use MagicDNS and silently skip Funnel.
        raw = socket.create_connection((self.address, 443), self.timeout)
        try:
            self.sock = self._context.wrap_socket(raw, server_hostname=HOST)
        except BaseException:
            raw.close()
            raise


def rpc(connection, path, request):
    try:
        connection.request("POST", path, json.dumps(request), {
            "Content-Type": "application/json", "Accept": "application/json, text/event-stream"})
        response = connection.getresponse()
        body = response.read(1024 * 1024)
        if response.status != 200:
            raise RuntimeError(f"MCP HTTP {response.status}")
        value = json.loads(body)
        if value.get("id") != request["id"] or "result" not in value or "error" in value:
            raise RuntimeError("Invalid MCP response")
        return value["result"]
    finally:
        connection.close()


def check_mcp(factory, path):
    info = rpc(factory(), path, INIT)
    if info.get("serverInfo", {}).get("name") != "OmniMCP-Lite":
        raise RuntimeError("Unexpected MCP server identity")
    reply = rpc(factory(), path, HELLO)
    if reply.get("isError") or not any(
            item.get("text") == "Hello world from the isolated MCP probe."
            for item in reply.get("content", [])):
        raise RuntimeError("MCP hello_world failed")


def public_addresses():
    # Public DoH is required: Windows DNS resolves this name to the tailnet IP.
    addresses = []
    for record_type in ("A", "AAAA"):
        with urllib.request.urlopen(f"https://dns.google/resolve?name={HOST}&type={record_type}", timeout=5) as response:
            answer = json.load(response)
        addresses.extend(r["data"] for r in answer.get("Answer", []) if r["type"] in (1, 28))
    if not addresses:
        raise RuntimeError("No public Funnel DNS addresses")
    return addresses


def check_public(address):
    try:
        check_mcp(lambda: PublicConnection(address), "/hello-mcp")
        rpc(PublicConnection(address), "/mcp", INIT)
        return address, "ok"
    except Exception as error:
        return address, str(error)


def command(*args, timeout=30):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout,
                            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed ({result.returncode}): {result.stderr[-500:]} {result.stdout[-500:]}")
    return result.stdout


def tailscale_status():
    try:
        value = json.loads(command("tailscale.exe", "status", "--json", timeout=3))
        return {"state": value.get("BackendState", "Unknown"),
                "online": value.get("Self", {}).get("Online", False),
                "authRequired": bool(value.get("AuthURL")) or value.get("BackendState") in
                ("NeedsLogin", "NeedsMachineAuth"), "health": value.get("Health", [])}
    except Exception as error:
        # CLI failure output can include a one-time login link. Never persist it.
        return {"state": "Unknown", "online": False,
                "error": f"Tailscale status probe failed ({type(error).__name__})"}


def restart_lite():
    # Task action remains authoritative; never hardcode a versioned executable.
    xml = command("schtasks.exe", "/Query", "/TN", TASK, "/XML")
    root = ET.fromstring(xml)
    ns = {"t": "http://schemas.microsoft.com/windows/2004/02/mit/task"}
    actions = root.findall("t:Actions/t:Exec", ns)
    if len(actions) != 1:
        raise RuntimeError("Lite task must have one executable action")
    executable = os.path.expandvars(actions[0].findtext("t:Command", "", ns)).strip('"')
    if Path(executable).name.lower() != "omnimcp-lite.exe" or not Path(executable).is_file():
        raise RuntimeError("Lite task executable missing or unexpected")
    # /End may report 'not running' after a crash, which is safe to ignore.
    try:
        command("schtasks.exe", "/End", "/TN", TASK)
    except RuntimeError:
        pass
    time.sleep(1)
    command("schtasks.exe", "/Run", "/TN", TASK)


class Supervisor:
    def __init__(self, repair=True):
        self.repair = repair
        self.local_failures = 0
        self.public_failures = 0
        self.last_local_repair = float("-inf")
        self.last_public_repair = float("-inf")
        self.next_public = 0
        self.public = {}
        self.public_error = None
        self.public_checked_at = None
        self.last_state = None

    def step(self, now):
        paused = (STATE / "pause.txt").exists() or not self.repair
        transport = tailscale_status()
        local_error = None
        try:
            check_mcp(lambda: http.client.HTTPConnection("127.0.0.1", 8771, timeout=2), "/mcp")
            self.local_failures = 0
        except Exception as error:
            local_error = str(error)
            self.local_failures += 1
        if self.local_failures >= 3 and now - self.last_local_repair >= 60 and not paused:
            self.last_local_repair = now  # rate limit even failed repairs
            logging.warning("Recovering Lite after MCP failures: %s", local_error)
            try:
                restart_lite()
            except Exception:
                logging.exception("Lite recovery failed; will retry")
        if now >= self.next_public:
            self.next_public = now + 60
            try:
                addresses = public_addresses()
                # Separate connections, bounded concurrently so dead ingress servers
                # cannot delay local crash recovery by one timeout per DNS record.
                with ThreadPoolExecutor(max_workers=6) as pool:
                    self.public = dict(pool.map(check_public, addresses))
                self.public_checked_at = dt.datetime.now(dt.timezone.utc).isoformat()
                self.public_error = None
                failed = any(value != "ok" for value in self.public.values())
                self.public_failures = self.public_failures + 1 if failed else 0
                if (not local_error and not transport.get("authRequired") and self.public_failures >= 3 and
                        now - self.last_public_repair >= 600 and not paused):
                    self.last_public_repair = now
                    logging.warning("Recovering Tailscale after public MCP failures: %s", self.public)
                    command("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                            "-Command", "Restart-Service -Name Tailscale -ErrorAction Stop")
            except Exception as error:
                # DNS/internet failure alone must never restart Tailscale or Lite.
                self.public_error = str(error)
                self.public_failures = 0
                logging.error("Public check/recovery failed: %s", error)
        status = {"checkedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "watchdogPid": os.getpid(),
                  "localHealthy": local_error is None, "localError": local_error,
                  "publicHealthy": bool(self.public) and self.public_error is None and
                  all(value == "ok" for value in self.public.values()),
                  "publicIngress": self.public, "publicError": self.public_error,
                  "publicCheckedAt": self.public_checked_at,
                  "localFailures": self.local_failures, "publicFailures": self.public_failures,
                  "tailscale": transport,
                  "paused": paused}
        state = (local_error, tuple(self.public.items()), self.public_error, paused)
        if state != self.last_state:
            logging.warning("Health changed: %s", json.dumps(status))
            self.last_state = state
        temp = STATE / f"status.{os.getpid()}.tmp"
        temp.write_text(json.dumps(status, indent=2), encoding="utf-8")
        temp.replace(STATE / "status.json")
        return status


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    STATE.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(level=logging.INFO, handlers=[RotatingFileHandler(
        STATE / "watchdog.log", maxBytes=2 * 1024 * 1024, backupCount=2, encoding="utf-8")],
        format="%(asctime)s %(levelname)s %(message)s")
    # Single instance even if launched manually alongside Task Scheduler.
    import ctypes
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.CreateMutexW.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_wchar_p]
    kernel.CreateMutexW.restype = ctypes.c_void_p
    handle = kernel.CreateMutexW(None, False, "Local\\OmniMcpLiteIndependentWatchdog")
    if not handle:
        raise ctypes.WinError(ctypes.get_last_error())
    if ctypes.get_last_error() == 183:
        return
    supervisor = Supervisor(repair=not args.dry_run)
    logging.info("START independent MCP/Funnel supervisor")
    try:
        while True:
            try:
                status = supervisor.step(time.monotonic())
                if args.once:
                    print(json.dumps(status, indent=2))
            except Exception:
                logging.exception("Supervisor step failed; continuing")
            if args.once:
                break
            time.sleep(5)
    finally:
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel.CloseHandle(handle)


if __name__ == "__main__":
    main()
