"""Exercise OmniHub's independent cursor against real native and WPF desktop windows.

Requires the running rebuilt Hub. Does not use the user's pointer for test clicks.
Usage: python TestScripts/AgentCursor/test_agent_cursor.py [--demo] [--url URL]
"""
import argparse
import pathlib
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:8770/mcp")
parser.add_argument("--demo", action="store_true", help="Show an interactive demo with two test windows and an independent AI click.")
args = parser.parse_args()
project = pathlib.Path(__file__).with_name("AgentCursorHarness.csproj")
command = ["dotnet", "run", "--project", str(project), "--", args.url]
if args.demo:
    command.append("--demo")
sys.exit(subprocess.call(command))
