---created: 2025-12-13
last_updated: 2026-01-30
---

---description: Mid-session checkpoint — save progress without full maintenance
created: 2025-12-13
last_updated: 2025-12-31
---

# /save — Checkpoint Script

> **Use Case**: Save progress mid-session without closing. Resume immediately after.

## 1. Quick Session Log Update

- [ ] Append current progress to session log in `.omni/athena/context/memories/session_logs/`
- [ ] Format: Checkpoint entry with timestamp and bullet summary

```markdown
### Checkpoint [HH:MM SGT]

- [Brief summary of what was discussed/accomplished since last save]
- [Any key decisions or insights]
```

## 2. Omni Project Context (OPC) Sync

- [ ] Synchronize project context tracking.
- [ ] Run: `python -m athena opc sync`
- [ ] Ensure `.omni/projectcontext/sync_state.txt` is updated.

## 3. Resume

- [ ] Confirm: "📍 Checkpoint saved & OPC Synced. Continuing session."
- [ ] Continue with user's next query

---

## What /save SKIPS (deferred to /end)

| Task | /save | /end |
|------|-------|------|
| Session log update | ✅ | ✅ |
| Maintenance scripts | ❌ | ✅ |
| Coherence check | ❌ | ✅ |
| Cross-reference audit | ❌ | ✅ |
| Git commit | ❌ | ✅ |
| Profile/protocol updates | ❌ | ✅ |

---

## When to Use

- Long sessions with natural break points
- Before switching topics (preserve context)
- Before risky experiments (rollback point)
- "Save my progress, I'll be back"

---

## References

- This workflow was created during the early development of Athena's session management system.

---

## Tagging

# workflow #automation #save
