"""Run PC reminder presentation policy checks against the built Hub assembly."""
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[2]
result = subprocess.run(["dotnet", "run", "--project", str(root / "TestScripts/System/ReminderAttentionHarness/ReminderAttentionHarness.csproj")], cwd=root)
raise SystemExit(result.returncode)
