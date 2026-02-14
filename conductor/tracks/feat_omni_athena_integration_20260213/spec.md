# Specification: Integrate Athena into Omni (Omni.Athena & Unified OPC)

## Overview
This track focuses on the deep integration of the Athena sovereign memory system into the Omni ecosystem. This involves rebranding the submodule, migrating project metadata to the `.omni` directory, and merging the `OmniProjectContext` (OPC) functionality into the Athena framework under a unified Python-powered `opc` branding.

## Objectives
- Rebrand the `Omni-Athena` submodule to `Omni.Athena`.
- Migrate local `.athena` metadata to `.omni/athena/`.
- Merge C# OPC logic into the Python Athena codebase to create a single `opc` framework.
- Update the Gemini CLI extension to reflect the unified `opc` naming and functionality.
- Enable automated skill injection for Omni-specific tools.

## Functional Requirements
### 1. Rebranding & Structure
- Rename the submodule directory from `Omni-Athena` to `Omni.Athena`.
- Update any internal C# project files or namespaces within the submodule to reflect `Omni.Athena`.

### 2. Metadata Migration
- Move all contents from the root `.athena/` directory to `.omni/athena/`.
- Update Athena's path discovery logic to prioritize `.omni/athena/` as the project-specific metadata store.

### 3. Unified OPC Framework (Python-Powered)
- **Logic Migration:** Port the core logic from `OmniSync.Cli.OmniProjectContext` (C#) to Python within the `Omni.Athena` repository. This includes:
    - Skeleton/Signature extraction.
    - Git history analysis.
    - Project context state management.
- **Unified Entry Point:** Create a single Python entry point (`opc.py` or similar) that handles both codebase context indexing and sovereign memory operations (search, quicksave).

### 4. Gemini CLI Integration
- Update the extension at `C:\Users\crovea\.gemini\extensions\athena` (or create a new `opc` extension) to:
    - Change tool names from `athena_*` to `opc_*` (e.g., `opc_search`, `opc_quicksave`, `opc_sync`).
    - Point to the new unified Python logic.
- Ensure Gemini CLI instances are aware of the memory framework as `opc`.

### 5. Automated Skill Injection
- Implement a mechanism to automatically inject Omni-specific task tools (e.g., build scripts, ADB commands) into the Athena agent's available skill library.

## Non-Functional Requirements
- **Performance:** Context indexing should remain efficient.
- **Reliability:** Maintain backward compatibility with existing session logs during migration.
- **Portability:** Ensure the Python-based `opc` tool runs correctly across the Windows environment.

## Acceptance Criteria
- [ ] Submodule is renamed to `Omni.Athena`.
- [ ] `.athena/` folder is migrated to `.omni/athena/` and recognized by the system.
- [ ] `opc` command (Python) can successfully perform codebase indexing and Athena memory searches.
- [ ] Gemini CLI extension tools are updated to the `opc_` prefix.
- [ ] Successful end-to-end test: `opc_search` retrieves both project context and session history.

## Out of Scope
- Complete removal of C# OPC code (it will be deprecated but kept until migration is fully verified).
- Direct modification of core Gemini CLI binary logic.
