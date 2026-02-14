---
type: Case_Study
id: CS-458
created: 2026-02-03
last_updated: 2026-02-03
tags: [agentic, patterns, claude-code, ccasp, orchestration, hooks, automation]
graphrag_extracted: false
---

# CS-458: CCASP Pattern Theft (Claude CLI Advanced Starter Pack)

> **Source**: [evan043/claude-cli-advanced-starter-pack](https://github.com/evan043/claude-cli-advanced-starter-pack)  
> **Status**: v2.2.4 (Production Ready)

## Executive Summary

CCASP is a "one-command" setup for Claude Code CLI that deploys a complete `.claude/` folder with 56 slash commands, 42 enforcement hooks, stack-specific agents, and MCP integrations. The key insight is **stack-aware automation** — detecting your project's tech stack and generating context-tailored configurations.

---

## 🎯 Stealable Patterns

### 1. **Stack Detection → Agent Generation Pipeline**

**Concept**: Auto-detect project tech stack by parsing `package.json`, config files, and directory structure (no AI calls). Then generate framework-specific agent templates.

**CCASP Implementation**:

```
ccasp detect-stack  # Reads 55+ frameworks
→ Generates react-specialist.md, fastapi-specialist.md, etc.
```

**Athena Adaptation**: We already have Protocol 133 (JIT Routing) for query-level routing. This pattern extends it to **project-level** routing — detecting the workspace's tech stack once at boot and injecting relevant protocols/skills automatically.

**Status**: Partially implemented via `boot.py` hot-file prefetch. Could enhance with `pyproject.toml`, `package.json`, and `.env` parsing.

---

### 2. **Ralph Loop (Test-Fix Cycle)**

**Concept**: Continuous test → parse failures → fix → repeat until green (max 10 iterations, stops on 3x same failure).

**Innovation**: Every 3rd failed attempt triggers a **web search agent** to find best-practice solutions.

**Athena Adaptation**: Create Protocol 420: "Ralph Loop" for automated test-fix cycles. Integrate with existing `pytest` runner. Add "escalation to web search" after repeated failures.

**Command Pattern**:

```
/ralph --watch  # Continuous mode
/ralph --max 5  # Limit iterations
```

---

### 3. **L1/L2/L3 Agent Hierarchy**

**Concept**: Hierarchical agent delegation with clear separation of concerns.

| Level | Role | Model | Example |
|-------|------|-------|---------|
| L1 | Orchestrator | User/Main | Coordinates L2s |
| L2 | Specialist | Sonnet | `frontend-specialist`, `backend-specialist` |
| L3 | Worker | Haiku | `component-search-worker`, `style-analyzer-worker` |

**Key Insight**: L3 workers are **cheap, disposable scouts**. L2 specialists integrate their findings. L1 orchestrates the full picture.

**Athena Analogy**: Our COS (Committee of Seats) is similar but flat. This pattern adds **vertical hierarchy** for task decomposition.

**Potential Protocol**: Protocol 421: "Hierarchical Agent Delegation" — define when to spawn L2/L3 sub-agents and how to aggregate their outputs.

---

### 4. **Enforcement Hooks (42 Hooks)**

**Concept**: Hooks that run automatically during specific events (pre-commit, post-response, on-error). Categories:

| Category | Examples |
|----------|----------|
| Token & Session | `token-usage-monitor`, `session-id-generator`, `context-guardian` |
| Deployment | `branch-merge-checker`, `deployment-orchestrator` |
| Refactoring | `ralph-loop-enforcer`, `refactor-verify`, `refactor-transaction` |
| Agent Orchestration | `hierarchy-validator`, `progress-tracker`, `l2-completion-reporter` |

**Athena Adaptation**: We already have `pre_response_hooks.py` and `quicksave.py`. This pattern formalizes the hook taxonomy and suggests new hooks like:

- `token-usage-monitor` (we have Lambda scoring but not token tracking)
- `context-guardian` (prevent context overflow)
- `refactor-transaction` (rollback on test failure)

---

### 5. **PROGRESS.json State Tracking**

**Concept**: A JSON file that tracks phased development progress across multiple sessions.

```json
{
  "phase": 2,
  "total_phases": 5,
  "current_task": "Implement auth middleware",
  "completed": ["Phase 1: Setup", "Phase 1.5: DB Schema"],
  "blockers": ["Waiting for API key"],
  "last_updated": "2026-02-03T00:20:00Z"
}
```

**Athena Adaptation**: We have `project_state.md` but it's prose-based. A structured `PROGRESS.json` enables:

- Machine-readable state resumption
- Cross-session continuity
- Dashboard integration

**Potential Protocol**: Protocol 422: "Phased Development Tracker" — JSON-based state machine for multi-phase projects.

---

### 6. **Vision Driver Bot (VDB)**

**Concept**: Autonomous development workflow with automatic lint fixing.

```
VDB State (.claude/vdb/state.json)
├── Current task tracking
├── Lint error queue
├── Fix history
└── Session metrics

Workflow: Detect lint errors → Queue fixes → Apply → Verify
```

**Athena Adaptation**: We already have `semantic_audit.py`. This pattern suggests:

- Queue-based lint error processing
- Automatic fix application with rollback
- Session metrics for observability

---

### 7. **PR Merge Workflow (9-Phase)**

**Concept**: Safe PR merge with automatic stash, conflict resolution, and rollback.

**Phases**:

1. Identify PR
2. Checkpoint (stash uncommitted changes)
3. Detect blockers (CI failures, pending reviews)
4. Resolve blockers
5. Message contributors
6. Select merge method
7. Execute merge
8. Cleanup (unstash)
9. Summary

**Athena Adaptation**: Protocol 413 (Multi-Agent Safety) already covers some of this. Could formalize into Protocol 423: "Safe PR Workflow" with explicit phases and rollback.

---

### 8. **GitHub Epic System**

**Concept**: Multi-issue epic workflows with parent-child issue linking.

```
/create-github-epic "User Authentication System"
→ Creates parent epic issue
→ Links child issues automatically
→ Tracks completion across issues
→ Syncs with Project Board
```

**Athena Adaptation**: Could integrate with our existing GitHub workflow in `git_commit.py`. Create `/epic` command for multi-issue tracking.

---

### 9. **Happy.engineering Mobile UI**

**Concept**: Mobile-first formatting with 40-character max width, card-based layouts.

```
┌────────────────────────────────────┐
│ [1] Issue #42                      │
│ Add JWT authentication             │
├────────────────────────────────────┤
│ Status: Ready                      │
│ Priority: High                     │
│ @johndoe • 2h ago                  │
└────────────────────────────────────┘
```

**Athena Adaptation**: Not immediately relevant (we're desktop-first), but the pattern of **environment-adaptive formatting** is valuable. Could detect terminal width and adjust output accordingly.

---

## 📊 Priority Matrix

| Pattern | Athena Relevance | Effort | Priority |
|---------|------------------|--------|----------|
| Stack Detection | HIGH | Medium | P1 |
| Ralph Loop | HIGH | Low | P1 |
| L1/L2/L3 Hierarchy | MEDIUM | High | P2 |
| Enforcement Hooks | HIGH | Medium | P1 |
| PROGRESS.json | HIGH | Low | P1 |
| VDB (Lint Queue) | MEDIUM | Medium | P2 |
| PR Workflow | MEDIUM | Medium | P3 |
| Epic System | LOW | High | P4 |
| Mobile UI | LOW | Low | P4 |

---

## 🚀 Recommended Immediate Actions

1. **Create Protocol 420: Ralph Loop** — Automated test-fix cycle with web search escalation.
2. **Create Protocol 422: PROGRESS.json** — Structured state tracking for phased development.
3. **Enhance boot.py** — Add stack detection for Python (`pyproject.toml`), Node (`package.json`), and framework configs.
4. **Formalize Hook Taxonomy** — Document existing hooks and create template for new ones.

---

## References

- [CCASP GitHub](https://github.com/evan043/claude-cli-advanced-starter-pack)
- [Session 2026-02-03-11](./.context/memories/session_logs/2026-02-02-session-11.md)
