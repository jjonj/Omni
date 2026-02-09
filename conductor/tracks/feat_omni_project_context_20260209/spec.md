# Specification: `OmniProjectContext` (OPC)

## Overview
`OmniProjectContext` (OPC) is a C# (.NET 9) CLI tool (`opc.exe`) that serves as a high-performance context engine for the OmniSync ecosystem. It maps project structures, tracks Git-based "latent" knowledge, and automatically injects this context into Gemini CLI sessions via hooks.

## Functional Requirements
1.  **Storage & State:**
    -   Store all index and relationship data in: `[Project Root]/.omni/projectcontext/`.
2.  **Aggressive Folder Exclusion:**
    -   **Explicitly Ignored:** `conductor/`, `docs/`, `build/`, `bin/`, `obj/`, `Assets/`, `Resources/`.
    -   **Auto-Ignored (Noise & Dependencies):** `node_modules/`, `dist/`, `out/`, `target/`, `vendor/`, `venv/`, `.venv/`, `__pycache__/`, `.gradle/`, `.idea/`, `.vs/`, `.vscode/`, `debug/`, `release/`, `temp/`, `tmp/`, `logs/`, `test-results/`, `coverage/`, `publish/`, `screenshots/`, `videos/`, `archives/`, `packages/`, `extern/`.
    -   **General Rule:** Ignore any folder starting with `.` (except `.omni/`).
3.  **Gemini CLI Hook Commands:**
    -   `opc session`: (Hook: `SessionStart`) Returns JSON for the CLI banner.
    -   `opc context`: (Hook: `BeforeAgent`) Dumps the current file tree, git notes, and relevant code skeletons directly into the CLI's prompt buffer.
    -   `opc sync`: (Hook: `SessionEnd`) Incremental scan to update the local context map.
4.  **Deep Git Integration:**
    -   Summarize recent commit history to provide a "narrative" of recent work.

## Acceptance Criteria
-   `opc.exe` correctly initializes the `.omni/projectcontext/` directory.
-   Running `opc context` produces a plain-text context block.
-   Files inside `node_modules` or `Assets` never appear in the output.
-   The tool executes `opc context` fast enough to feel "automatic" during CLI use.
