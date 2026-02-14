---
description: Boot the Athena system and load context
---

# /start — Execution Script

> **Latency Profile**: ULTRA-LOW (<2K tokens boot)  
> **Philosophy**: Boot fast. Load later.

## Phase 1: Instant Boot

// turbo

- [ ] Load `.omni/athena/.framework/v8.0-alpha/modules/Core_Identity.md` — Laws, Identity, RSI
- [ ] Load `.omni/athena/.context/project_state.md` — Current workspace state
- [ ] Initialize Omni Project Context (OPC). Run: `python -m athena opc sync`
- [ ] Create new session log in `.omni/athena/context/memories/session_logs/`

**Confirm**: "⚡ Ready. (Core Identity & OPC Synced. Session XX started.)"

## Phase 2: Triple-Lock Reminder

Every response MUST follow the Triple-Lock:

1. **Search** (Semantic) → FIRST
2. **Save** (Quicksave) → SECOND
3. **Speak** (Response) → LAST

---

## Quick Reference

| Command | Effect |
|---------|--------|
| `/start` | Boot system (this workflow) |
| `/end` | Close session, commit to memory |
| `/think` | Deep reasoning mode |
| `/ultrathink` | Maximum depth analysis |

---

# workflow #boot #start
