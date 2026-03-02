#!/usr/bin/env python3
import os
import subprocess
import sys
import shutil


ROOT = r"D:\SSDProjects"
HUB_TEST_PROJECT = os.path.join(
    ROOT, "Omni", "OmniSync.Hub", "src", "OmniSync.Hub.Tests", "OmniSync.Hub.Tests.csproj"
)
CLI_ROOT = os.path.join(ROOT, "Tools", "omni-gemini-cli", "main")


def run_step(name, cmd, cwd):
    print(f"\n=== {name} ===")
    print(f"CWD: {cwd}")
    print("CMD:", " ".join(cmd))
    effective_cmd = list(cmd)
    if os.name == "nt" and effective_cmd and effective_cmd[0].lower() == "npm":
        npm_cmd = shutil.which("npm.cmd") or shutil.which("npm")
        if npm_cmd:
            effective_cmd[0] = npm_cmd
    result = subprocess.run(effective_cmd, cwd=cwd)
    if result.returncode != 0:
        print(f"[FAIL] {name} (exit {result.returncode})")
        return False
    print(f"[PASS] {name}")
    return True


def main():
    ok = True

    ok &= run_step(
        "Hub contract tests",
        [
            "dotnet",
            "test",
            HUB_TEST_PROJECT,
            "--filter",
            "AiBridgeSignalRContractTests|GeminiSessionMockPipeContractTests|GeminiSessionPayloadTests",
            "-p:BaseOutputPath=D:\\SSDProjects\\Omni\\OmniSync.Hub\\temp_test_out\\",
        ],
        os.path.dirname(HUB_TEST_PROJECT),
    )

    ok &= run_step(
        "CLI pipe contract test",
        ["npm", "test", "-w", "@google/gemini-cli", "--", "src/omni/remoteControl.contract.test.ts"],
        CLI_ROOT,
    )

    ok &= run_step(
        "CLI listener-gap repro test",
        ["npm", "test", "-w", "@google/gemini-cli", "--", "src/omni/remoteControl.listenerGap.repro.test.ts"],
        CLI_ROOT,
    )

    if ok:
        print("\nAPI/ISOLATION TESTS: SUCCESS")
        return 0

    print("\nAPI/ISOLATION TESTS: FAILED")
    return 1


if __name__ == "__main__":
    sys.exit(main())
