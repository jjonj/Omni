# Implementation Plan: Omni.Athena Integration & Unified OPC

## Phase 1: Submodule Rebranding & Local Metadata Migration
Goal: Establish the new naming convention and move local state to the `.omni` folder.

- [x] Task: Rename submodule directory to `OmniSync.Assistant/Omni.Athena` and update `.gitmodules`.
- [x] Task: Write Tests: Verify `Athena` path discovery logic for `.omni/athena/` location.
- [x] Task: Implement: Migrate root `.athena/` content to `.omni/athena/` and update `Omni.Athena` path constants.
- [x] Task: Write Tests: Ensure backward compatibility with legacy `.athena` if present.
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Submodule Rebranding & Local Metadata Migration' (Protocol in workflow.md)

## Phase 2: Python-Powered OPC Porting (Signature Extraction)
Goal: Migrate the core codebase indexing logic from C# to Python within the `Omni.Athena` submodule.

- [x] Task: Write Tests: Define expected signature/skeleton output for a sample C# project.
- [x] Task: Implement: Port `SkeletonExtractor.cs` logic to a new Python module in `Omni.Athena`.
- [x] Task: Write Tests: Verify Git history analysis logic in Python (parity with `GitHistoryService.cs`).
- [x] Task: Implement: Port `GitHistoryService.cs` logic to Python.
- [x] Task: Conductor - User Manual Verification 'Phase 2: Python-Powered OPC Porting (Signature Extraction)' (Protocol in workflow.md)

## Phase 3: Unified `opc` Entry Point & Memory Integration
Goal: Create the single `opc` command that merges codebase context and sovereign memory.

- [x] Task: Write Tests: Verify a unified search that queries both session logs and codebase signatures.
- [x] Task: Implement: Create `opc.py` entry point in `Omni.Athena` that orchestrates indexing and memory tools.
- [x] Task: Write Tests: Verify `opc_sync` functionality (updating both context and memory).
- [x] Task: Implement: Add `sync` command to the unified Python `opc` tool.
- [x] Task: Conductor - User Manual Verification 'Phase 3: Unified opc Entry Point & Memory Integration' (Protocol in workflow.md)

## Phase 4: Gemini CLI Extension Update
Goal: Update the user's Gemini CLI to use the new `opc_*` tools.

- [x] Task: Write Tests: Verify `opc_search` and `opc_quicksave` via a mock extension call.
- [x] Task: Implement: Update the Gemini CLI extension at `C:\Users\crovea\.gemini\extensions\athena` to use `opc` branding and tools.
- [x] Task: Implement: Deprecate `athena_*` tools in favor of `opc_*`.
- [x] Task: Conductor - User Manual Verification 'Phase 4: Gemini CLI Extension Update' (Protocol in workflow.md)

## Phase 5: Automated Skill Injection & Final Verification
Goal: Expose Omni tools to the agent and perform final end-to-end verification.

- [x] Task: Write Tests: Verify that `build_run_omnihub.py` is correctly identified as a "skill" by the agent.
- [x] Task: Implement: Build a skill discovery mechanism in `Omni.Athena` that scans the Omni root for common scripts.
- [x] Task: Implement: Final project-wide `opc sync` and memory audit.
- [x] Task: Conductor - User Manual Verification 'Phase 5: Automated Skill Injection & Final Verification' (Protocol in workflow.md)
