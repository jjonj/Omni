# Specification: Omni Assistant Unification (Omni.Athena + OPC)

## 1. Overview
Unify the fragmented OmniProjectContext (C#) and Omni.Athena (Python) systems into a single, cohesive "Assistant" ecosystem. The C# Hub will act as the high-privilege host/orchestrator, while Python (Athena) will serve as the primary intelligence engine for memory, context, and workflows.

## 2. Functional Requirements

### 2.1 Unified Command Orchestration (The Hub Proxy)
- **Centralized Execution**: The Gemini CLI Extension will proxy all `omni:*` commands to the C# Hub.
- **Environment Management**: The C# Hub will manage the Python environment, `PYTHONPATH`, and execute Athena logic, removing hardcoded paths from the extension.
- **Discovery**: The Hub will dynamically resolve the project root and pass it to Athena via the `--root` argument.

### 2.2 Unified Project Context (OmniState)
- **Standardized Metadata**: Consolidate all metadata within `.omni/` (e.g., `.omni/athena/` and `.omni/projectcontext/`).
- **Efficient State Format**: Adopt the C# OPC text-based line format (for token efficiency) wrapped in a minimal JSON structure for cross-language compatibility.
- **Automatic Context**: The `omni:context` logic will be automatically triggered during session initialization (SessionStart hook) but can also be manually invoked via `/omni:start`.

### 2.3 Unified Naming Scheme
| Command Type | New Name | Original/Source | Logic |
| :--- | :--- | :--- | :--- |
| **Slash (User)** | `/omni:start` | `/start` + `opc sync` | Boot sequence + Project re-index. |
| **Slash (User)** | `/omni:save` | `save.md` workflow | Guided checkpointing with user input. |
| **Slash (User)** | `/omni:end` | `/end` workflow | Shutdown sequence (lineage, stats, cleanup). |
| **MCP (AI)** | `omni:search` | `smart_search` | Hybrid Brain Search (Code + Memory + Logs). |
| **MCP (AI)** | `omni:quicksave` | `quicksave` | Atomic summary append to active log. |
| **MCP (AI)** | `omni:sync` | `opc sync` | Refresh file tree and git narrative. |

### 2.4 Hybrid Brain Search
- **Unified Retrieval**: `omni:search` will query Vector Memory, Session Logs, and current Project Structure/Filenames in a single pass, returning the most relevant results regardless of source.

## 3. Non-Functional Requirements
- **Token Efficiency**: Project context structure must remain compact (avoiding JSON-per-line for file lists).
- **Latency**: Hub-to-Python execution should be optimized to minimize overhead for real-time AI tools.
- **Resilience**: The Hub must handle Python environment failures gracefully, providing clear diagnostics.

## 4. Acceptance Criteria
- [ ] Running `/omni:setup` scaffolds the unified `.omni/` structure.
- [ ] The Gemini CLI banner shows "✦ Omni Synced ✦" using the Python-generated context.
- [ ] The AI can successfully use `omni:search` to find code and historical session info.
- [ ] `/omni:end` correctly closes the session and updates the previous/next session lineage.
- [ ] No hardcoded paths remain in `omni-server.cjs`.
