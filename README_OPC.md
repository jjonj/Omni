# OmniProjectContext (OPC)

OmniProjectContext is a high-performance C# CLI tool designed to provide dense, token-efficient codebase context to the Gemini CLI via lifecycle hooks. It functions as a specialized replacement for generic project indexing tools, optimized specifically for the Omni repository's scale and structure.

## Core Philosophy
The primary goal of OPC is to provide the AI with a "bird's-eye view" of the project without wasting tokens on repetitive paths, boilerplate code, or non-essential files. It prioritizes structure and narrative over specific implementation details.

## Features
- **High-Density Storage**: Uses a hierarchical tree format in `sync_state.txt` to avoid repeating long directory paths.
- **Structural Only**: Focused entirely on directory hierarchy and file presence. All code signatures (skeletons) and imports have been removed to minimize token footprint.
- **Incremental Sync**: Uses Unix millisecond-of-day timestamps as a lightweight hash for fast change detection.
- **Aggressive Filtering**:
    - Whitelists only high-value code and configuration files.
    - Restricts `.txt` files to the project root only.
    - Ignores noise directories like `bin`, `obj`, `node_modules`, `gradle`, and `.git`.
- **Gemini CLI Integration**: Combined banner and context injection via the `SessionStart` hook for zero-spam interaction.

## Architecture

### Services
- **FileSystemService**: Manages aggressive exclusion rules and high-value file whitelisting.
- **ContextEngine**: Traverses the project and generates the initial file tree.
- **GitHistoryService**: Provides a [NARRATIVE] section by extracting recent commit messages.
- **StateService**: Handles the hierarchical persistence and reconstruction of the `sync_state.txt` file.

### Storage Format (`sync_state.txt`)
The state is stored in an ultra-dense, pipe-delimited hierarchical format:
```text
timestamp_ms|↳\DirectoryName
timestamp_ms| ↳FileName
timestamp_ms|  ↳\SubDir
timestamp_ms|   ↳NestedFile
```

## Commands
- `opc sync`: Scans the codebase and updates the hierarchical index.
- `opc session`: Returns a JSON response containing both the CLI banner (`systemMessage`) and the injected project context (`additionalContext`) for the `SessionStart` hook.
- `opc context`: Dumps the reconstructed context for manual inspection or injection.

## Integration
OPC is integrated via `C:\Users\crovea\.gemini\settings.json`:
- **SessionStart**: Displays `✦ OPC Synced: Omni ✦` and injects the `<system-reminder>` block once per session.
- **BeforeAgent**: Disabled to avoid spamming the AI with context on every turn.
- **SessionEnd**: Triggers an incremental sync to keep the index fresh.

## Setup
To rebuild the tool and re-register the hooks:
```powershell
python OmniSync.Cli/OmniProjectContext/setup.py
```
