# Village Automation Tests

Tests related to village-based game automation and state persistence.

- `prepare_village_test_data.py`: Generates large binary files (`village_test_data.bin`) to simulate game states.
- `test_villages_from_data.js`: Validates that the Hub correctly parses and serves the binary village data.
- `restore_wartribes.py`: Automates the restoration of game backups from GDrive to local directories for testing.
