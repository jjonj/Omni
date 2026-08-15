#!/usr/bin/env python3
"""Exercises the provider-agnostic Hub -> Omnish -> PTY byte relay using agy."""
import asyncio
import base64
import logging
import socket
import sys
import time

from signalrcore.hub_connection_builder import HubConnectionBuilder

HUB_URL = "http://127.0.0.1:5000/remoteterminalhub"
API_KEY = "test_api_key"
WORKSPACE = r"D:\SSDProjects"
COMMAND = "agy"
PROMPT_NONCE = "omni_hub_roundtrip_93571"
EXPECTED_FRAGMENTS = ("17539_pirtdnuor", "_buh_inmo")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("RemoteTerminalRoundtrip")


class Tester:
    def __init__(self):
        self.opened = False
        self.session_id = None
        self.ready = False
        self.output = bytearray()

    def on_output(self, args):
        if len(args) < 2 or args[0] != self.session_id:
            return
        self.output.extend(base64.b64decode(args[1]))

    def on_started(self, args):
        self.session_id = args[0]
        logger.info("Started remote terminal %s", self.session_id)

    def on_status(self, args):
        if len(args) >= 2 and args[0] == self.session_id:
            logger.info("Terminal status: %s", args[1])
            self.ready = args[1] == "ready"

    async def run(self):
        hub = HubConnectionBuilder().with_url(HUB_URL).configure_logging(logging.WARNING).build()
        hub.on_open(lambda: setattr(self, "opened", True))
        hub.on("RemoteTerminalStarted", self.on_started)
        hub.on("RemoteTerminalStatus", self.on_status)
        hub.on("RemoteTerminalOutput", self.on_output)
        hub.start()
        for _ in range(20):
            if self.opened:
                break
            await asyncio.sleep(0.5)
        if not self.opened:
            raise RuntimeError("Could not connect to RemoteTerminalHub")

        hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(0.5)
        hub.send("StartRemoteTerminal", [COMMAND, WORKSPACE, 120, 40])
        for _ in range(80):
            if self.ready:
                break
            await asyncio.sleep(0.5)
        if not self.ready:
            raise RuntimeError("Omnish did not attach to the remote terminal session")

        await asyncio.sleep(15)
        prompt = f"Reverse this exact text and reply with only the reversed text: {PROMPT_NONCE}\r"
        hub.send("SendRemoteTerminalInput", [self.session_id, base64.b64encode(prompt.encode()).decode()])
        deadline = time.time() + 120
        while time.time() < deadline:
            if all(fragment.encode() in self.output for fragment in EXPECTED_FRAGMENTS):
                logger.info("Transformed round-trip fragments observed")
                hub.send("StopRemoteTerminal", [self.session_id])
                hub.stop()
                return 0
            await asyncio.sleep(1)
        hub.send("StopRemoteTerminal", [self.session_id])
        hub.stop()
        logger.error("Marker was not observed. Captured output:\n%s", self.output.decode(errors="replace")[-4000:])
        return 1


def main():
    if socket.create_connection(("127.0.0.1", 5000), timeout=2):
        pass
    exit_code = asyncio.run(Tester().run())
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
