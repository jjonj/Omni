# Implementation Plan: Omni Hub Resource Opener & Gemini CLI Extension

## Phase 1: Hub API Refactoring & Resource Logic
Implement the core logic in the Omni Hub to handle resource opening and rename the existing API controller.

- [x] Task: Rename `ExternalCommandController` to `OmniHubAPI` (keeping `Controller` suffix in class name: `OmniHubApiController`).
- [x] Task: Create `ResourceOpenerService` in Omni Hub.
    - [x] Implement detection logic for Files vs. Folders vs. URLs.
    - [x] Implement specialized Notepad++ logic with line number support (`-n` arg).
    - [x] Integrate with `HubSettingsService` to respect existing application mappings (e.g., "chrome").
- [x] Task: Update `CommandDispatcher` to include the `OPEN_RESOURCE` command.
- [x] Task: Verify `OmniHubApiController` can receive and route the `OPEN_RESOURCE` payload.
- [x] Task: Write unit tests for `ResourceOpenerService` (Red Phase).
- [x] Task: Implement `ResourceOpenerService` logic (Green Phase).
- [x] Task: Conductor - User Manual Verification 'Phase 1: Hub API Refactoring & Resource Logic' (Protocol in workflow.md)

## Phase 2: Gemini CLI Extension
Build the client-side extension that allows the AI to call the new Hub API.

- [x] Task: Scaffold the `omni` extension in `D:\SSDProjects\Tools\omni-omni-gemini-cli\extensions\omni\`.
- [x] Task: Implement the `open_resource` tool in the extension.
    - [x] Logic to read Hub URL and API Key from environment or config.
    - [x] Logic to send HTTP POST request to the renamed Hub API.
- [x] Task: Write integration tests for the `omni` extension (mocking the Hub API).
- [x] Task: Conductor - User Manual Verification 'Phase 2: Gemini CLI Extension' (Protocol in workflow.md)

## Phase 3: Enhanced Resource Opening [checkpoint: 76a974a]
Improve the logic for handling different file types and editors.

- [x] Task: Update `ResourceOpenerService` to use Notepad++ for text/code files even without a line number.
- [x] Task: Ensure `ResourceOpenerService` preserves "chrome" mapping for local HTML and remote URLs.
- [x] Task: Ensure `ResourceOpenerService` defaults to Windows Shell Execute for other file types (opening with default app).
- [x] Task: Update unit tests for `ResourceOpenerService` to cover these enhanced cases.
- [x] Task: Conductor - User Manual Verification 'Phase 3: Enhanced Resource Opening' (Protocol in workflow.md)

## Phase 4: CLI Session Interaction [checkpoint: 29bafd2]
Enable the AI to see and interact with other active Gemini CLI sessions.

- [x] Task: Update `CommandDispatcher` to handle `SEND_CLI_MESSAGE` and `GET_CLI_HISTORY`.
- [x] Task: Implement message injection and history retrieval in Hub by calling `AiCliService`.
- [x] Task: Update the `omni` extension to include `list_cli_sessions`, `send_cli_message`, and `get_cli_history` tools.
- [x] Task: Verify end-to-end: AI in Session A sends a message to Session B via the `omni` extension.
- [x] Task: Conductor - User Manual Verification 'Phase 4: CLI Session Interaction' (Protocol in workflow.md)

## Phase 5: Integration & Polish
Final end-to-end testing and refinement of the user experience.

- [x] Task: Test end-to-end flow: AI command -> Gemini CLI -> Hub REST API -> App Execution.
- [x] Task: Add activity logging in the Hub for all extension-triggered actions.
- [x] Task: Conductor - User Manual Verification 'Phase 5: Integration & Polish' (Protocol in workflow.md) [checkpoint: ff4729a]
