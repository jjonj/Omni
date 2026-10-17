"""Validate setup persistence, snapshot merging, shortcuts and command execution.

Run build_run_omnihub.py first. --desktop also opens disposable fixture windows
to verify capture, restore/reuse and the click-through snapshot/skip flow.
"""
import argparse
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument("--desktop", action="store_true")
args = parser.parse_args()
command = ["dotnet", "run", "--project", str(root / "TestScripts/System/SetupsHarness/SetupsHarness.csproj")]
if args.desktop:
    command += ["--", "--desktop"]
raise SystemExit(subprocess.call(command, cwd=root))
