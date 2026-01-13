# Browser Extension Tests

Tests for OmniSync's browser integration.

- `comprehensive_extension_test.py`: The primary integration test that opens tabs, injects scripts, and verifies tab synchronization.
- `test_browser_commands.py`: Verifies that SignalR commands (OPEN_URL, CLOSE_TAB) are correctly routed to the extensions.
- `test_browser_control_sequence.py`: Tests complex sequences of browser interactions.
- `diagnose_browser_control.py`: Debugging tool for connection issues between Hub and Extension.
