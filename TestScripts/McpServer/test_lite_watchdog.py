"""Deterministic recovery tests; never restart real services."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/services/omnimcp_lite_watchdog.py"
spec = importlib.util.spec_from_file_location("watchdog", SCRIPT)
watchdog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watchdog)
real_tailscale_status = watchdog.tailscale_status


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = patch.object(watchdog, "STATE", Path(self.temp.name))
        self.state.start()
        self.addCleanup(self.state.stop)
        transport = patch.object(watchdog, "tailscale_status", return_value={
            "state": "Running", "online": True, "authRequired": False})
        transport.start()
        self.addCleanup(transport.stop)

    @patch.object(watchdog, "restart_lite")
    @patch.object(watchdog, "public_addresses", return_value=[])
    @patch.object(watchdog, "check_mcp", side_effect=OSError("offline"))
    def test_local_crash_retries_after_cooldown(self, probe, dns, restart):
        supervisor = watchdog.Supervisor()
        for now in (0, 5, 10, 15, 69):
            supervisor.step(now)
        self.assertEqual(restart.call_count, 1)
        supervisor.step(70)
        self.assertEqual(restart.call_count, 2)

    @patch.object(watchdog, "command")
    @patch.object(watchdog, "rpc")
    @patch.object(watchdog, "public_addresses", return_value=["192.0.2.1"])
    def test_public_failure_repairs_transport_only(self, dns, rpc, command):
        def probe(factory, path):
            if path == "/hello-mcp":
                raise OSError("TLS failed")
        with patch.object(watchdog, "check_mcp", side_effect=probe), patch.object(watchdog, "restart_lite") as restart:
            supervisor = watchdog.Supervisor()
            for now in (0, 60, 120, 180, 719):
                status = supervisor.step(now)
            self.assertTrue(status["localHealthy"])
            self.assertFalse(status["publicHealthy"])
            self.assertEqual(command.call_count, 1)
            supervisor.step(779)
            self.assertEqual(command.call_count, 2)
            restart.assert_not_called()

    @patch.object(watchdog, "command")
    @patch.object(watchdog, "public_addresses", side_effect=OSError("DNS offline"))
    @patch.object(watchdog, "check_mcp")
    def test_dns_outage_does_not_restart_transport(self, probe, dns, command):
        supervisor = watchdog.Supervisor()
        for now in (0, 60, 120, 180):
            status = supervisor.step(now)
        command.assert_not_called()
        self.assertFalse(status["publicHealthy"])

    @patch.object(watchdog, "restart_lite")
    @patch.object(watchdog, "public_addresses", return_value=[])
    @patch.object(watchdog, "check_mcp", side_effect=OSError("offline"))
    def test_pause_prevents_recovery(self, probe, dns, restart):
        (watchdog.STATE / "pause.txt").touch()
        supervisor = watchdog.Supervisor()
        for now in (0, 5, 10, 75):
            supervisor.step(now)
        restart.assert_not_called()

    def test_wrong_server_and_failed_tool_are_unhealthy(self):
        with patch.object(watchdog, "rpc", return_value={"serverInfo": {"name": "other"}}):
            with self.assertRaisesRegex(RuntimeError, "identity"):
                watchdog.check_mcp(lambda: None, "/mcp")

        with patch.object(watchdog, "rpc", side_effect=[
                {"serverInfo": {"name": "OmniMCP-Lite"}}, {"isError": True}]):
            with self.assertRaisesRegex(RuntimeError, "hello_world"):
                watchdog.check_mcp(lambda: None, "/mcp")

    @patch.object(watchdog, "command", return_value='{"BackendState":"NeedsLogin","AuthURL":"https://login.example/secret"}')
    def test_transport_detects_auth_without_persisting_login_url(self, command):
        value = real_tailscale_status()
        self.assertTrue(value["authRequired"])
        self.assertNotIn("secret", str(value))

    @patch.object(watchdog, "command", side_effect=RuntimeError("Sign in at https://login.example/secret"))
    def test_transport_error_does_not_persist_login_url(self, command):
        value = real_tailscale_status()
        self.assertEqual(value["state"], "Unknown")
        self.assertNotIn("secret", str(value))

    @patch.object(watchdog, "command")
    @patch.object(watchdog, "public_addresses", return_value=["192.0.2.1"])
    @patch.object(watchdog, "check_public", return_value=("192.0.2.1", "TLS failed"))
    @patch.object(watchdog, "check_mcp")
    def test_auth_required_does_not_cause_restart_loop(self, probe, public, dns, command):
        with patch.object(watchdog, "tailscale_status", return_value={"state": "NeedsLogin", "authRequired": True}):
            supervisor = watchdog.Supervisor()
            for now in (0, 60, 120, 180):
                supervisor.step(now)
        command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
