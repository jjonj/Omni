# OmniSync Test Suite Index

This directory contains the integration and unit tests for the OmniSync ecosystem, covering the Hub (Windows), Remote (Android), and AI (Gemini) components.

## Folder Structure

### 🤖 [AIFeature](./AIFeature/)
Tests for the Gemini CLI integration and AI-driven automation.
- **Session Management:** Launching, discovery, and lifecycle of AI sessions.
- **Roundtrip:** Verifying Hub-to-AI communication via Named Pipes.
- **Formatting:** Ensuring diffs and thoughts are rendered correctly.
- **Guide:** See [AI_TESTING_GUIDE.md](./AIFeature/AI_TESTING_GUIDE.md) for best practices.

### 🎮 [TFT](./TFT/)
Teamfight Tactics specific automation and logic validation.
- **Team Planner:** Unit selection and encoding/decoding of team codes.
- **Rune Solver:** Logic for optimal rune selection.
- **UI Debugging:** Scripts to verify unit counts and cost sorting in the web UI.

### 🌐 [Browser](./Browser/)
Integration tests for the OmniSync Chrome and Firefox extensions.
- **Extension Control:** Verifying tab management, URL opening, and cleanup patterns.
- **Communication:** Testing the SignalR bridge between the Hub and the Browser.

### 💻 [System](./System/)
Core Windows integration and Hub service tests.
- **Input Emulation:** Validating `user32.dll` hooks for mouse and keyboard control.
- **FileSystem:** Testing remote file access, directory listing, and write operations.
- **Diagnostics:** (Under `/Diagnostics`) Low-level tools for WMI, process hierarchy, and window handles.

### 🏘️ [Villages](./Villages/)
Tests for village-based game automation (e.g., Wartribes).
- **Data Prep:** Generating binary test data for village layouts.
- **Restoration:** Automated state restoration for testing persistence.

### 🏝️ [Islands](./Islands/)
Procedural generation tests for the Island Generator submodule.
- **Geometry:** Verifying shape generation and hydrology logic.

---

## Running Tests
Most Python tests require `signalrcore`. If missing, run:
```bash
pip install signalrcore
```
Ensure the **OmniSync Hub** is running before executing integration tests.
