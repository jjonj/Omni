# Specification: Omni Hub Resource Opener & Gemini CLI Extension

## 1. Overview
This feature enables the Gemini CLI to interact with the Omni Hub to open files, folders, and web resources using specific application mappings. It extends the Hub's command infrastructure and introduces a new extension for the Gemini CLI. As part of this work, the existing `ExternalCommandController` is renamed to `OmniHubAPI` for better clarity. It also adds capabilities to interact with active Gemini CLI sessions managed by the Hub.

## 2. Functional Requirements

### 2.1. Omni Hub Enhancements
- **API Refactoring:** Rename `ExternalCommandController` to `OmniHubAPI` (keeping standard Controller suffix in class name: `OmniHubApiController`).
- **Command Dispatcher:** Extend the `CommandDispatcher` to handle:
    - `OPEN_RESOURCE`: Opens a file, folder, or URL.
    - `SEND_CLI_MESSAGE`: Injects a message into a specific Gemini CLI session.
    - `GET_CLI_HISTORY`: Retrieves the history of a specific Gemini CLI session.
- **Enhanced Resource Opening Logic:**
    - **Web/HTML:** Continue using "chrome" mapping for local/remote HTML (`.html`, `.htm`) and URLs (`http`, `https`).
    - **Code/Text:** Use Notepad++ for all code and text files (detected by extension). If a line number is provided, use the `-n` argument.
    - **Default:** Use Windows Shell Execute (`Process.Start` with `UseShellExecute = true`) for all other file types (Images, Videos, PDFs, etc.) to open with the system default application.
- **CLI Session Interaction:**
    - Leverage `AiCliService` to list active sessions.
    - Inject prompts into sessions via named pipes.
    - Fetch and return session history (JSON format).
- **REST API:** Leverage `OmniHubApiController` to accept the new commands.

### 2.2. Gemini CLI Extension (`omni`)
- **Location:** `D:\SSDProjects\Tools\omni-omni-gemini-cli\extensions\omni\`
- **Interface:** Provide a unified tool for the AI to interact with the Hub.
- **Tools:**
    - `open_resource(path, line_number?)`: Sends an authenticated POST request to the Hub to open the specified target.
    - `list_cli_sessions()`: Lists PIDs and names of active Gemini CLI sessions.
    - `send_cli_message(pid, message)`: Sends a message to a specific session.
    - `get_cli_history(pid, max_chars?)`: Gets history for a session.

## 3. Non-Functional Requirements
- **Latency:** The round-trip from CLI command to Hub execution should be < 500ms on a local network.
- **Robustness:** Gracefully handle missing files, invalid paths, and Hub connection failures.
- **Security:** Use the Hub's existing API Key authentication for the REST endpoint.

## 4. Acceptance Criteria
- [ ] Gemini CLI can successfully open a folder on Drive D.
- [ ] Gemini CLI can open a specific C# file in Notepad++ at a requested line number.
- [ ] Gemini CLI can open a local HTML file in the mapped browser (Chrome/Vivaldi).
- [ ] Gemini CLI can open an image file using the Windows default viewer.
- [ ] Gemini CLI can list other active CLI sessions.
- [ ] Gemini CLI can inject a message into another active CLI session.
- [ ] Hub activity log correctly reflects the commands received from the extension.

## 5. Out of Scope
- Implementation of a UI for editing application mappings (this already exists).
- Support for editors other than Notepad++ for the line-number feature in this phase.
