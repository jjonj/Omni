# Omni Extension

This extension integrates Omni Hub tools and provides user-facing commands for the Omni Project Context (OPC) framework.

## Omni Hub Tools
- `open_resource(path, line_number?)`: Open a file, folder, or URL on the Windows PC.
- `list_cli_sessions()`: Lists active Gemini CLI sessions.
- `send_cli_message(pid, message)`: Injects a message into another session.
- `get_cli_history(pid, max_chars?)`: Retrieves history of a specific session.
- `take_screenshot()`: Takes a screenshot of the primary monitor.

## Omni OPC User Commands
The following commands provide user-facing interaction with the Omni Project Context (OPC) framework.

### Commands
- `/omni:setup`: Initializes the Omni OPC workspace for the current project.
- `/omni:save <summary>`: Quicksaves a checkpoint to sovereign memory via OPC.

### Usage
- Use `/omni:setup` to ensure the OPC framework is ready for the project.
- Use `/omni:save "Topic"` to persist important context or decisions.
