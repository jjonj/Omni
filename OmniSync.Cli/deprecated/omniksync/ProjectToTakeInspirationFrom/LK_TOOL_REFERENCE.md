# LK (Latent Knowledge): The Definitive Technical Reference

## 1. Overview & Vision
`lk` (Latent Knowledge) is an industrial-grade context synchronization engine designed for the AI-first development era. Its primary mission is to solve the "Context Paradox": AI models need comprehensive information to be accurate, but excessive or disorganized context leads to high latency, increased costs (tokens), and reasoning "hallucinations."

`lk` acts as an intelligent intermediary that maintains a live, high-fidelity map of your codebase's architecture, dependencies, and "latent" relationships, serving this information to AI agents just-in-time.

---

## 2. Core Architecture

### 2.1 The Knowledge Graph
Unlike simple file-tree tools, `lk` performs lightweight static analysis to build a directed graph of your project.
- **Nodes:** Files, classes, functions, and modules.
- **Edges:** Imports, calls, inheritances, and usage patterns.
This graph allows `lk` to answer complex questions like: *"If I change this utility function, which 15 files in the other subsystem might break?"*

### 2.2 The Data Layer (`.lk/`)
The engine persists its state in a hidden `.lk` directory at the project root.
- **Metadata Index:** Stores hash-based change detection for incremental syncing.
- **Relationship Cache:** A serialized version of the dependency graph.
- **Session State:** Tracks active AI sessions to provide consistent context across multiple turns.

### 2.3 Just-in-Time Expansion
The "Magic" of `lk` happens during the `expand` phase. When an AI agent is about to process a prompt, `lk` analyzes the files the user is currently looking at and injects:
1.  **Context Tags:** Semantic markers that help the model navigate.
2.  **Implicit Dependencies:** Code snippets from related files that the user didn't explicitly mention but are required for understanding.
3.  **Project Health:** Metadata about the size and scope of the workspace.

---

## 3. Deep-Dive: Gemini CLI Integration

The integration between `lk` and the Gemini CLI is built on a high-performance hook system that allows `lk` to act as a "Co-Processor" for the AI agent.

### 3.1 The Lifecycle Hooks

#### Phase 1: `SessionStart` (Initialization)
- **Goal:** Establish a handshake between the CLI and the local project context.
- **Command:** `lk session-info --json`
- **Mechanism:** When the Gemini CLI starts, it executes this hook. `lk` checks for the existence of an `.lk` folder and validates the license.
- **Protocol:** `lk` returns a JSON object containing a `systemMessage`.
  - **Example Output:** `{"systemMessage": "✦ Context Synced: 452 files indexed ✦"}`
  - **UI Impact:** The Gemini CLI parses this JSON and displays the `systemMessage` as an informational banner at the top of the session.

#### Phase 2: `BeforeAgent` (Prompt Augmentation)
- **Goal:** Provide the AI with "Latent Knowledge" just before it processes the user's request.
- **Command:** `lk expand --tokens --limit 8000`
- **Mechanism:** Every time you send a message to the Gemini CLI, it runs `lk expand` *before* hitting the AI model.
- **Data Flow:**
  1. The CLI passes the current workspace state to the hook.
  2. `lk` analyzes the files currently "in view" or mentioned.
  3. `lk` generates a structured context block including a file tree and relationship map.
- **Result:** The AI knows about dependencies it hasn't seen yet because `lk` injected them into the hidden context window.

#### Phase 3: `SessionEnd` (Persistence)
- **Goal:** Ensure the context graph remains current after the user makes changes.
- **Command:** `lk sync --quiet`
- **Mechanism:** When the user exits the CLI or closes the session, this hook runs in the background. It re-scans the filesystem for any new imports or modified files.

---

## 4. Feature Set & Capabilities

### 4.1 Context-Aware File Reading (`read_file`)
Instead of reading raw file content, this tool combines the file with project context:
- **Exports**: Public functions and classes the file exposes.
- **Imported by**: Which files depend on this file.
- **Imports**: Which files this file depends on.
- **Notes**: Semantic annotations about the file's purpose.
- **`//usedby:` annotations**: Each exported function shows which files call it.

For large files (200+ lines), `lk` automatically generates a **Skeleton View** with function signatures and line ranges, allowing the AI to request specific sections.

### 4.2 Language Support Matrix
`lk` features broad support with static analysis for:
- **Web**: `js`, `mjs`, `cjs`, `ts`, `tsx`, `jsx`.
- **Python**: `py`.
- **Go**: `go`.
- **Rust**: `rs`.
- **Java/Kotlin**: `java`, `kt`, `kts`.
- **PHP**: `php`.
- **Ruby**: `rb`.
- **C#**: `cs`.
- **C/C++**: `c`, `cpp`, `h`, `hpp`.

Features supported across all: Extracting Imports, Exports, Skeletons, Signatures, Function Bodies, and Comment Stripping.

### 4.3 MCP Integration (Model Context Protocol)
`lk` provides an MCP server for agents like Claude Code:
- `get_project_context`: Get relevant file paths and project structure.
- `read_file`: The context-aware reader described above.
- `update_relation`: Add notes or manual relations between files.
- `review`: Scans for outdated relations or missing architectural notes.

---

## 5. Command & Mode Summary

### 5.1 Core Context Operations
| Command | Mode / Option | Description |
| :--- | :--- | :--- |
| **`sync`** | *Default* | Incremental sync of the project context graph. |
| | `--all` (-a) | **Full Rebuild Mode**: Deletes the existing index and re-scans everything. |
| | `--quiet` (-q) | Silent execution for background automation. |
| **`status`** | *Default* | Displays context health, file counts, and configuration status. |
| **`ignore`** | *Default* | Shows a summary of active ignore patterns. |
| | `--list` (-l) | **List Mode**: Displays every individual file currently ignored. |
| **`dead-code`**| *Default* | Scans for orphan files and unused exports. |
| | `--entry <f>` | **Trace Mode**: Defines root files to start the dependency crawl. |

### 5.2 AI Integration (Hooks & MCP)
| Command | Mode / Option | Description |
| :--- | :--- | :--- |
| **`expand`** | *Default* | Generates the context-augmented prompt for AI agents. |
| | `--limit <n>` | Caps output size to fit specific model context windows. |
| | `--no-relations`| **Flat Mode**: Hides the file relationship tree from the output. |
| | `--tokens` (-t) | Displays character/token count for budget monitoring. |
| | `--debug` | **Developer Mode**: Prints internal logic to stderr. |
| **`session-info`**| *Default* | Prints metadata for AI session initialization. |
| | `--json` | **Gemini Mode**: Outputs structured JSON for CLI banners. |
| **`mcp`** | `on` / `off` | Enable/Disable the Model Context Protocol server. |
| | `serve` | **Active Mode**: Starts the MCP server process for agents. |
| | `status` | Checks if the MCP server is available. |

### 5.3 Installation & Lifecycle
| Command | Mode / Option | Description |
| :--- | :--- | :--- |
| **`activate`** | *Default* | Registers your license key to enable premium features. |
| **`enable`** | *Default* | Patches hooks into all detected AI CLIs. |
| | `--target <t>` | Targets specific CLI: `claude` or `gemini`. |
| **`disable`** | *Default* | Safely removes all `lk` hooks from CLI configurations. |
| **`update`** | *Default* | Downloads and installs the latest version of `lk.exe`. |
| **`clean`** | `--context` | **Project Reset**: Removes the local `.lk/` folder. |
| | `--license` | **Auth Reset**: Clears license and user credentials. |
| | `--logs` | Purges the `lk` debug log history. |
| | `--all` (-a) | **Total Purge**: Removes all data, logs, and licenses. |
| | `--yes` (-y) | Auto-confirms all destructive actions. |

---

## 6. Advanced Hook Configuration (PowerShell)

Gemini CLI on Windows executes hooks via PowerShell.

### Chaining & Failure Handling
In Bash, you might see `command || true`. In Windows/PowerShell, this is handled via semicolons or specific error handling:
- **The Semicolon (`;`):** Ensures the command sequence continues regardless of previous command exit codes.
- **Path Quoting:** Always escape backslashes in JSON: `"C:\Users\...\lk.exe"`.

### Observed `.gemini/settings.json` Configuration:
```json
  "hooks": {
    "SessionStart": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "C:\\Users\\crovea\\AppData\\Local\\Programs\\lk\\lk.exe session-info --json;"     
          }
        ]
      }
    ],
    "BeforeAgent": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "C:\\Users\\crovea\\AppData\\Local\\Programs\\lk\\lk.exe expand;"
          }
        ]
      }
    ],
    "SessionEnd": [
      {
        "matcher": "",
        "hooks": [
          {
            "type": "command",
            "command": "C:\\Users\\crovea\\AppData\\Local\\Programs\\lk\\lk.exe sync --quiet"
          }
        ]
      }
    ]
  }
```

---

## 7. Proprietary Nature & Speculative Analysis

### 7.1 The Parsing Engine
**Speculation:** `lk` likely utilizes **Tree-sitter** for incremental parsing. This allows it to generate ASTs for skeletons and signatures across many languages without needing full compiler toolchains.

### 7.2 Relevance Heuristics
**Speculation:** `lk expand` likely uses a **Reranking Algorithm** weighing:
1. **Graph Proximity:** Files within 1-2 degrees of current files.
2. **Semantic Similarity:** Possible use of local vector embeddings.
3. **Temporal Locality:** Files modified in recent Git history.

### 7.3 Data Persistence
**Speculation:** The `.lk/` graph is likely stored in a high-performance binary format or key-value store like **RocksDB**, optimized for rapid dependency traversal.

---

## 8. Common Command Snippets & Pro-Tips

- **`lk status`**: Run this to check context health.
- **`lk sync --all`**: If you feel the AI is "lost," force a full rebuild.
- **Context Coverage**: `lk` works best when it has a clear view of your entry points.
- **Token Management**: If you hit context limits, reduce the `--limit` in your `BeforeAgent` hook.

---

## 9. Workflow Scenarios

### Scenario A: Starting a New Feature
1. Run `lk sync` to index the latest state.
2. Open Gemini CLI. `lk session-info` alerts you that context is 100% healthy.
3. Ask the AI: *"Implement a new endpoint for user profile updates."*
4. **Effect**: `lk expand` injects the `User` model, the DB connection utility, and existing validation logic into the prompt before the AI even starts "thinking."

### Scenario B: Refactoring a Shared Library
1. Ask the AI: *"Rename the 'validate' method to 'validateInput' everywhere."*
2. **Effect**: The AI uses `lk`'s relationship map to instantly identify every file that imports that specific library, avoiding a "search and replace" that misses dynamic imports.

### Scenario C: Identifying Technical Debt
1. Run `lk dead-code --entry src/main.ts`.
2. Review the list of orphan files and unused exports.
3. Ask the AI: *"Review these orphan files and suggest which ones are safe to delete or if they should be integrated into the main flow."*

---

## 10. Privacy & Data Safety

Based on the lack of outbound network traffic during `sync` and `expand` (post-activation), it is inferred that **no code content leaves the machine.**

### 10.1 Local-First Architecture
- **Indexing:** All parsing and AST generation occurs on the local CPU.
- **Storage:** The `.lk` directory is entirely local and should typically be added to your `.gitignore`.
- **Licensing:** The `lk activate` command performs a one-time HTTPS handshake with `latentk.org`. Subsequent usage is offline-capable.
- **Telemetry:** Speculatively, only anonymized metadata (performance metrics, command success rates) is shared to improve the tool's relevance heuristics.
