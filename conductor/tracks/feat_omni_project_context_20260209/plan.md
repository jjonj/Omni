# Implementation Plan: OmniProjectContext (OPC)

## Phase 1: Project Scaffolding & Core Infrastructure [checkpoint: 416e4c4]
- [x] Task: Initialize OPC CLI project (C# .NET 9) in `OmniSync.Cli/OmniProjectContext` c9c0c3e
- [x] Task: Implement Command Line Parser for `session`, `context`, and `sync` commands 4b936a9
- [x] Task: Implement `FileSystemService` with aggressive exclusion logic (Conductor, Assets, etc.) cbb7747
- [x] Task: Implement `StateService` for managing storage in `.omni/projectcontext/` 09183f4
- [x] Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md) a31cef3

## Phase 2: Git Integration & Analysis
- [ ] Task: Implement `GitService` to retrieve `git notes` using shell execution (`git notes show`)
- [ ] Task: Implement `GitHistoryService` to extract recent commit narrative (last 5 commits)
- [ ] Task: Write tests for Git integration using a temporary mock repository
- [ ] Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)

## Phase 3: Context Generation & Expansion Logic
- [ ] Task: Implement `ContextEngine` to generate the file tree (respecting exclusions)
- [ ] Task: Implement "Skeleton" extractor for C# and Python (signatures only for large files)
- [ ] Task: Implement the `opc context` command output formatter
- [ ] Task: Verify token/size management to keep context within reasonable limits
- [ ] Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md)

## Phase 4: Gemini CLI Integration & Verification
- [ ] Task: Create `opc session` JSON response for `SessionStart` hook
- [ ] Task: Implement `opc sync` incremental scanning logic
- [ ] Task: Final verification of `opc.exe` when called via Gemini CLI hooks in `.gemini/settings.json`
- [ ] Task: Conductor - User Manual Verification 'Phase 4' (Protocol in workflow.md)
