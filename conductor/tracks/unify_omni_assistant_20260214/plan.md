# Implementation Plan: Omni Assistant Unification

## Phase 1: Hub Orchestration & Proxying [checkpoint: 33c03c9]
- [x] Task: Implement `AssistantService` in C# Hub to manage Python environment and execution. 33c03c9
    - [x] Write tests for `AssistantService` path resolution and command execution. 33c03c9
    - [x] Implement `AssistantService` with logic to discover `venv` and set `PYTHONPATH`. 33c03c9
    - [x] Create Hub API endpoint `/api/external/assistant/execute` in `ExternalApiController`. 33c03c9
- [x] Task: Refactor Gemini CLI Extension to proxy via Hub. 33c03c9
    - [x] Update `omni-server.cjs` to remove hardcoded paths. 33c03c9
    - [x] Implement `callHubAssistantApi` in `omni-server.cjs` to route commands through the Hub. 33c03c9
- [x] Task: Conductor - User Manual Verification 'Phase 1: Hub Orchestration' (Protocol in workflow.md) 33c03c9

## Phase 2: Metadata Standardization & Path Discovery [checkpoint: d4c2328]
- [x] Task: Standardize OPC State Format. d4c2328
    - [x] Update Python `StateService` to support compact text format (wrapped in JSON). d4c2328
    - [x] Update C# `StateService` to match the new unified format. d4c2328
- [x] Task: Update Athena Initialization logic. d4c2328
    - [x] Modify `athena init` to strictly scaffold into `.omni/athena` and `.omni/projectcontext`. d4c2328
    - [x] Update Hub discovery logic to identify `.omni/` as the project root marker. d4c2328
- [x] Task: Conductor - User Manual Verification 'Phase 2: Standardization' (Protocol in workflow.md) d4c2328

## Phase 3: Logic Unification & Rename [checkpoint: f5e6d08]
- [x] Task: Unify OPC and Athena logic in Python.
    - [x] Port remaining C# context logic (e.g., skeleton refinement) to Python `athena.opc`.
    - [x] Rename commands/tools to the new `omni:*` scheme in `mcp_server.py` and `__main__.py`.
- [x] Task: Implement Hybrid Brain Search.
    - [x] Update `smart_search.py` to query current project structure, session logs, and vector memory in parallel.
    - [x] Write integration tests for `omni:search` retrieving results from multiple sources.
- [x] Task: Conductor - User Manual Verification 'Phase 3: Logic Unification' (Protocol in workflow.md)

## Phase 4: Final Workflow Integration [checkpoint: 53a7fb5]
- [x] Task: Refactor Extension Slash Commands.
    - [x] Update `.toml` files in `omni-extension/commands` to use the new names.
    - [x] Ensure `/omni:start` triggers both the boot orchestrator and the initial context sync.
- [x] Task: Update System Hooks.
    - [x] Ensure `SessionStart` and `BeforeAgent` hooks correctly call the Hub's assistant execution.
- [x] Task: Conductor - User Manual Verification 'Phase 4: Final Integration' (Protocol in workflow.md)
