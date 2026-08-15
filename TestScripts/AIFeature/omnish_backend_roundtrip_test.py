#!/usr/bin/env python3
"""Fast local test for the Omnish backend I/O layer; no Hub required."""
import asyncio
import argparse
import json
import os
import re
import subprocess
import sys
import time

import websockets

if sys.platform == "win32":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

EXE = r"D:\SSDProjects\Tools\Omnish\main\target\release\omnish.exe"
received = bytearray()
connected = asyncio.Event()
injected_input = None
input_delay = 5
expected_output = "OMNI_BACKEND_IO_ROUNDTRIP_20260815"
expected_fragments = [expected_output]
command = f"Write-Output {expected_output}"
show_output = False


def printable_terminal_text(data):
    text = data.decode(errors="replace")
    text = re.sub(r"\x1b(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])", "", text)
    return "".join(char for char in text if char in "\r\n\t" or ord(char) >= 32)


async def bridge(socket):
    registration = json.loads(await socket.recv())
    if registration.get("token") != "local-test-token":
        await socket.close(1008, "Invalid token")
        return
    connected.set()
    if injected_input:
        await asyncio.sleep(input_delay)
        await socket.send(f"{injected_input}\r".encode())
    async for data in socket:
        if isinstance(data, bytes):
            received.extend(data)
            if show_output:
                text = printable_terminal_text(data)
                if text:
                    print(text, end="", flush=True)


async def main():
    async with websockets.serve(bridge, "127.0.0.1", 5101):
        env = os.environ.copy()
        env.update({
            "OMNI_REMOTE_TERMINAL_HUB_URL": "http://127.0.0.1:5101",
            "OMNI_REMOTE_TERMINAL_SESSION_ID": "local-backend-test",
            "OMNI_REMOTE_TERMINAL_TOKEN": "local-test-token",
            "OMNI_REMOTE_TERMINAL_COMMAND": command,
            "OMNI_REMOTE_TERMINAL_COLUMNS": "120",
            "OMNI_REMOTE_TERMINAL_ROWS": "40",
        })
        process = subprocess.Popen([EXE, r"D:\SSDProjects"], env=env)
        try:
            await asyncio.wait_for(connected.wait(), timeout=30)
            deadline = time.time() + (120 if injected_input else 30)
            while time.time() < deadline:
                rendered_text = printable_terminal_text(bytes(received))
                if all(fragment in rendered_text for fragment in expected_fragments):
                    print(f"SUCCESS: observed {' + '.join(expected_fragments)}")
                    if show_output:
                        print("\n--- normalized terminal tail ---")
                        print(rendered_text[-4000:])
                    return 0
                await asyncio.sleep(0.25)
            print(printable_terminal_text(bytes(received))[-4000:], file=sys.stderr)
            return 1
        finally:
            process.terminate()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--client", choices=("transport", "hello", "agy"), default="transport")
    args = parser.parse_args()
    if args.client == "hello":
        command = "echo hello"
        expected_output = "hello"
        expected_fragments = [expected_output]
    elif args.client == "agy":
        command = "agy"
        injected_input = "Reverse this exact text and reply with only the reversed text: omni_roundtrip_82651"
        input_delay = 15
        expected_output = "15628_pirtdnuor_inmo"
        expected_fragments = ["15628_pirtdn", "uor_inmo"]
    sys.exit(asyncio.run(main()))
