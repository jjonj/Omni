import os
import subprocess
import sys


ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HARNESS_PROJECT = os.path.join(
    ROOT,
    "TestScripts",
    "System",
    "ReminderFoundationHarness",
    "ReminderFoundationHarness.csproj",
)


def main() -> int:
    hub_dll = os.path.join(
        ROOT,
        "OmniSync.Hub",
        "src",
        "OmniSync.Hub",
        "bin",
        "Debug",
        "net9.0-windows",
        "OmniSync.Hub.dll",
    )
    if not os.path.exists(hub_dll):
        print("Hub build output is missing. Run build_run_omnihub.py first.")
        return 2

    command = [
        "dotnet",
        "run",
        "--project",
        HARNESS_PROJECT,
        "--configuration",
        "Debug",
    ]

    print("Running reminder foundation harness...")
    result = subprocess.run(command, cwd=ROOT, text=True)
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
