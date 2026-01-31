# Implementation Plan: AI Screen & MCP Screenshot Skill

This plan covers the UI improvements to the AI Chat screen and the addition of the screenshot skill to the Omni MCP server.

## Phase 1: AI Chat UI Improvements (Android)
- [x] Task: Remove dedicated "Enter" button from `QuickActionPanel`.
- [x] Task: Modify "Send" button logic to send the `enter` special key when the input field is empty.
- [x] Task: Add "Mon 2" button to `QuickActionPanel` to move the session window to the second monitor.

## Phase 2: Hub & MCP Skill Implementation
- [x] Task: Add `MoveSessionToMonitorAsync` to `AiCliService` using PowerShell window positioning.
- [x] Task: Expose `MoveAiSessionToMonitor` in `RpcApiHub` and `SignalRClient`.
- [x] Task: Implement `take_screenshot` tool in the Omni MCP server (`omni-server.cjs`).
- [x] Task: Register `SCREENSHOT` command in `CommandDispatcher` using `ScreenshotService`.
- [x] Task: Add `POST /api/external/screenshot` endpoint to `OmniHubApiController`.

## Phase 3: Documentation & Bugfixes
- [x] Task: Update `GEMINI.md` and conductor files with MCP server location and deployment guide.
- [x] Task: Fix Hub crash ("Pipe is broken") during session cleanup in `AiCliService.Dispose`.
- [x] Task: Create and run verification test scripts.

## Finalization
- [x] Task: Deploy updated MCP extension to `~/.gemini/extensions/omni`.
- [x] Task: Refresh MCP servers in all active sessions.
