# Omni Extension

This extension integrates Omni Hub tools and provides user-facing commands for the Omni.Athena memory framework.

## Omni Hub Tools
- `open_resource(path, line_number?)`: Open a file, folder, or URL on the Windows PC.
- `list_cli_sessions()`: Lists active Gemini CLI sessions.
- `send_cli_message(pid, message)`: Injects a message into another session.
- `get_cli_history(pid, max_chars?)`: Retrieves history of a specific session.
- `take_screenshot()`: Takes a screenshot of the primary monitor.

## Omni.Athena User Commands
The following commands provide user-facing interaction with the Omni.Athena memory framework. These are command files defined in the `commands` directory.

### Commands
- `/omni:setup`: Initializes the Omni.Athena memory framework for the current project.
- `/omni:save <summary>`: Quicksaves a checkpoint to sovereign memory via Omni.Athena.

### Usage
- Use `/omni:setup` to ensure the memory framework is ready for the project.
- Use `/omni:save "Topic"` to persist important context or decisions.
