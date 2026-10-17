"""Read-only regression checks for retirement of the legacy C# Hub source.

No process control, network requests, desktop input, or secret values are emitted.
"""
import json
import os
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
RUST = Path(r"D:\SSDProjects\Tools\Omnish\main")
CONFIG = Path(os.environ.get("OMNIHUB_AUTH_CONFIG") or
    Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "OmniHub/config/appsettings.json")


class RetirementContractTests(unittest.TestCase):
    def test_legacy_project_is_absent(self):
        self.assertFalse((ROOT / "OmniSync.Hub/src/OmniSync.Hub/OmniSync.Hub.csproj").exists())
        self.assertFalse((ROOT / "OmniSync.Hub/tests/OmniSync.Hub.Tests/OmniSync.Hub.Tests.csproj").exists())

    def test_auth_config_migrated_without_exposing_secret(self):
        self.assertTrue(CONFIG.is_file(), "Private Rust auth settings must exist")
        value = json.loads(CONFIG.read_text(encoding="utf-8-sig"))
        self.assertTrue(value.get("AuthApiKey"))
        self.assertFalse(str(CONFIG).lower().startswith(str(ROOT).lower()))

    def test_client_transport_uses_rust_owned_port(self):
        chrome = (ROOT / "OmniSync.Chrome/background.js").read_text(encoding="utf-8")
        self.assertIn("5000/signalrhub", chrome)
        script = (ROOT / "OmniSync.Cli/omni_cli_script.py").read_text(encoding="utf-8")
        self.assertIn("OMNIHUB_AUTH_CONFIG", script)
        self.assertNotIn('API_KEY = "test_api_key"', script)

    def test_rust_launcher_uses_private_config(self):
        source = (RUST / "scripts/omnihub-launch-production.ps1").read_text(encoding="utf-8-sig")
        self.assertIn("OmniHub\\config\\appsettings.json", source)
        self.assertNotIn("OmniSync.Hub\\src\\OmniSync.Hub\\appsettings.json", source)

    def test_watchdog_independent_of_retired_source(self):
        self.assertTrue((ROOT / "scripts/services/omnimcp_lite_watchdog.py").is_file())
        test = (ROOT / "TestScripts/McpServer/test_lite_watchdog.py").read_text(encoding="utf-8")
        self.assertIn("scripts/services/omnimcp_lite_watchdog.py", test)


if __name__ == "__main__":
    unittest.main()
