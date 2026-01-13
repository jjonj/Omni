# System & Hub Test Scripts

Core integration tests for Windows system level features and Hub services.

## Core Tests
- `test_file_access.py`: Verifies SignalR requests for file reading and directory traversal.
- `test_write_file_content.py`: Tests remote file writing and deletion.
- `emulate_input.py`: Low-level validation of mouse moves and keypresses.
- `test_mouse_clicks.py`: Focuses specifically on click precision and focus handling.
- `test_shutdown_sound.py`: Verifies audio service triggers and system shutdown scheduling.
- `test_startup_exec.py`: Tests the "Run on Startup" registry integration.

## Utilities
- `check_paths.py`: Validates that the Hub's hardcoded paths exist on the local machine.
- `check_registry.py`: Diagnostics for OmniSync registry keys.
- `fix_settings.py/cs`: Scripts to repair or reset `appsettings.json` and local configurations.

## [Diagnostics](./Diagnostics/README.md)
Contains scripts for deep-diving into Windows internals like WMI process trees and Window Handles.
