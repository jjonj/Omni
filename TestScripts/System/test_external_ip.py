#!/usr/bin/env python3
"""Validates that the Hub resolves its own public IP and pushes it to clients.

Checks, against a running Hub:
  1. a freshly connected client is sent UpdateExternalIp without authenticating
  2. GetHubStatus reports a plausible public IPv4 with the source it came from
  3. RefreshExternalIp re-resolves and broadcasts a new value
  4. a value that cannot be used as a public address is rejected (static check)

Run:  python TestScripts\\System\\test_external_ip.py
Needs the Hub running (python build_run_omnihub.py) and `pip install signalrcore`.
"""

import asyncio
import logging
import socket
import sys
import time

from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://127.0.0.1:5000/signalrhub"
HUB_PORT = 5000
API_KEY = "test_api_key"
# ---------------------

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger("TestExternalIp")


def is_plausible_public_ipv4(value):
    if not isinstance(value, str):
        return False
    parts = value.strip().split(".")
    if len(parts) != 4:
        return False
    octets = []
    for part in parts:
        if not part.isdigit() or len(part) > 3:
            return False
        octets.append(int(part))
        if octets[-1] > 255:
            return False
    first, second = octets[0], octets[1]
    if first in (0, 10, 127) or first >= 224:
        return False
    if first == 169 and second == 254:
        return False
    if first == 172 and 16 <= second <= 31:
        return False
    if first == 192 and second == 168:
        return False
    return True


def is_port_in_use(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(("127.0.0.1", port)) == 0


class ExternalIpTester:
    def __init__(self):
        self.connection_started = False
        self.hub = None
        self.loop = None
        self.pushed_ips = []
        self.status = None
        self.refresh_result = None

    def on_open(self):
        logger.info("Connection opened.")
        self.connection_started = True

    def on_close(self):
        logger.info("Connection closed.")

    def on_error(self, error):
        logger.error(f"Error: {error}")

    def on_external_ip(self, args):
        ip = args[0] if args else None
        updated_at = args[1] if len(args) > 1 else None
        logger.info(f"Received UpdateExternalIp: {ip} (updatedAt={updated_at})")
        if self.loop:
            self.loop.call_soon_threadsafe(self.pushed_ips.append, (ip, updated_at))

    def record_status(self, completion):
        result = getattr(completion, "result", None)
        error = getattr(completion, "error", None)
        if error:
            logger.error(f"GetHubStatus failed: {error}")
        if self.loop:
            self.loop.call_soon_threadsafe(self.set_status, result)

    def set_status(self, result):
        self.status = result

    def record_refresh(self, completion):
        result = getattr(completion, "result", None)
        error = getattr(completion, "error", None)
        if error:
            logger.error(f"RefreshExternalIp failed: {error}")
        if self.loop:
            self.loop.call_soon_threadsafe(self.set_refresh, result)

    def set_refresh(self, result):
        self.refresh_result = result

    async def run_test(self):
        self.loop = asyncio.get_running_loop()
        failures = []

        if not is_port_in_use(HUB_PORT):
            logger.error(f"FAIL: nothing is listening on port {HUB_PORT}. Start the Hub first.")
            return 1

        self.hub = (
            HubConnectionBuilder()
            .with_url(HUB_URL)
            .configure_logging(logging.WARNING)
            .build()
        )
        self.hub.on_open(self.on_open)
        self.hub.on_close(self.on_close)
        self.hub.on_error(self.on_error)
        self.hub.on("UpdateExternalIp", self.on_external_ip)
        self.hub.start()

        for _ in range(10):
            if self.connection_started:
                break
            await asyncio.sleep(1)

        if not self.connection_started:
            logger.error("FAIL: could not connect to the Hub.")
            return 1

        # 1. The Hub pushes the cached address on connect, before authentication.
        await asyncio.sleep(1.5)
        if self.pushed_ips:
            connect_ip = self.pushed_ips[0][0]
            if is_plausible_public_ipv4(connect_ip):
                logger.info(f"PASS: connected client was pushed {connect_ip} without authenticating.")
            else:
                logger.error(f"FAIL: connect push carried an unusable address: {connect_ip!r}")
                failures.append("connect push")
        else:
            logger.error("FAIL: no UpdateExternalIp arrived on connect.")
            failures.append("connect push")

        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)

        # 2. The status reports what the service currently believes.
        self.hub.send("GetHubStatus", [], on_invocation=self.record_status)
        for _ in range(10):
            if self.status is not None:
                break
            await asyncio.sleep(1)

        status = self.status or {}
        status_ip = status.get("ExternalIp") or status.get("externalIp")
        status_source = status.get("ExternalIpSource") or status.get("externalIpSource")
        status_updated = status.get("ExternalIpUpdatedAtUnix") or status.get("externalIpUpdatedAtUnix")
        if is_plausible_public_ipv4(status_ip):
            logger.info(f"PASS: GetHubStatus reports {status_ip} via {status_source} (checked {status_updated}).")
        else:
            logger.error(f"FAIL: GetHubStatus reported an unusable address: {status_ip!r}")
            failures.append("hub status")

        # 3. A manual refresh re-resolves and broadcasts.
        before = len(self.pushed_ips)
        self.hub.send("RefreshExternalIp", [], on_invocation=self.record_refresh)
        for _ in range(30):
            if self.refresh_result is not None:
                break
            await asyncio.sleep(1)

        refreshed = self.refresh_result
        if isinstance(refreshed, list):
            refreshed = refreshed[0] if refreshed else None
        if is_plausible_public_ipv4(refreshed):
            logger.info(f"PASS: RefreshExternalIp resolved {refreshed}.")
        else:
            logger.error(f"FAIL: RefreshExternalIp returned {refreshed!r}.")
            failures.append("manual refresh")

        for _ in range(5):
            if len(self.pushed_ips) > before:
                break
            await asyncio.sleep(1)
        if len(self.pushed_ips) > before:
            logger.info(f"PASS: the refresh was broadcast as {self.pushed_ips[-1][0]}.")
        else:
            logger.error("FAIL: the refresh was not broadcast to clients.")
            failures.append("refresh broadcast")

        self.hub.stop()

        # 4. The unusable value rules the Hub applies, checked without the network.
        for rejected in ("", "   ", "not an ip", "127.0.0.1", "0.0.0.0", "255.255.255.255", "169.254.1.1"):
            if is_plausible_public_ipv4(rejected):
                logger.error(f"FAIL: {rejected!r} should not be treated as a public address.")
                failures.append("validation")
        for accepted in ("85.80.233.70", "8.8.8.8", "1.1.1.1"):
            if not is_plausible_public_ipv4(accepted):
                logger.error(f"FAIL: {accepted!r} should be treated as a public address.")
                failures.append("validation")
        if "validation" not in failures:
            logger.info("PASS: unusable values are rejected and routable ones are accepted.")

        if failures:
            logger.error(f"Failed checks: {sorted(set(failures))}")
            return 1

        logger.info("All external IP checks passed.")
        return 0


async def main():
    tester = ExternalIpTester()
    exit_code = await tester.run_test()
    print("\nOVERALL STATUS: SUCCESS" if exit_code == 0 else "\nOVERALL STATUS: FAILED")
    sys.exit(exit_code)


if __name__ == "__main__":
    asyncio.run(main())
