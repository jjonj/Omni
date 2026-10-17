import os
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TEST_PROJECT = os.path.join(ROOT, "OmniSync.Hub", "src", "OmniSync.Hub.Tests", "OmniSync.Hub.Tests.csproj")

def require(path, token, present=True):
    text = open(path, "r", encoding="utf-8-sig").read()
    ok = (token in text) == present
    state = "present" if present else "absent"
    print(f"[{'PASS' if ok else 'FAIL'}] {token!r} is {state} in {os.path.basename(path)}")
    return ok

def main():
    result = subprocess.run(
        ["dotnet", "test", TEST_PROJECT, "--filter", "CalendarEventEndTests", "--nologo"],
        cwd=ROOT,
        text=True,
    )
    if result.returncode != 0:
        return result.returncode

    presentation = os.path.join(ROOT, "OmniSync.Hub", "src", "OmniSync.Hub", "Presentation")
    checks = [
        require(os.path.join(presentation, "OmniSweepWindow.xaml.cs"), "if (!_interaction) QueueClose();"),
        require(os.path.join(presentation, "OmniSweepWindow.xaml.cs"), "_ignoreDeactivateUntil", False),
        require(os.path.join(presentation, "OmniSweepViewModel.cs"), "OngoingEvents"),
        require(os.path.join(presentation, "OmniSweepViewModel.cs"), "e.Start.Date <= now.Date && e.End.Value.Date > now.Date"),
        require(os.path.join(presentation, "OmniSweepWindow.xaml"), 'ItemsSource="{Binding OngoingEvents}"'),
        require(os.path.join(presentation, "OmniSweepWindow.xaml"), 'CalendarEventEndConverter'),
    ]
    return 0 if all(checks) else 1

if __name__ == "__main__":
    sys.exit(main())
