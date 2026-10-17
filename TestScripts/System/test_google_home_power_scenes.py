import json
import os
from pathlib import Path

settings_path = Path(os.environ["APPDATA"]) / "OmniSync" / "settings.json"
settings = json.loads(settings_path.read_text(encoding="utf-8"))

expected = {
    "sleep": r'run C:\Windows\explorer.exe "B:\GDrive\Tools\05 Automation\sleep.bat"',
    "shutdown": r'run C:\Windows\explorer.exe "B:\GDrive\Tools\05 Automation\shutdown.bat"',
}

voice_macros = {
    str(m.get("Name", "")).strip().lower(): m
    for m in settings.get("Macros", [])
    if str(m.get("Category", "")).strip().lower() == "voice"
}

failures = []
for name, script in expected.items():
    macro = voice_macros.get(name)
    if macro is None:
        failures.append(f"missing Voice scene macro: {name}")
        continue
    if macro.get("Script") != script:
        failures.append(
            f"{name} script mismatch: expected {script!r}, got {macro.get('Script')!r}"
        )

if failures:
    for failure in failures:
        print(f"FAIL {failure}")
    raise SystemExit(1)

print("PASS Sleep is a Voice scene macro with the requested explorer.exe command")
print("PASS Shutdown is a Voice scene macro with the requested explorer.exe command")
print("ALL PASSED")