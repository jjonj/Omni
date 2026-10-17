# Agent Screen — Development Plan (rev. 2)

Revision 2 (2026-10-01) replaces the first draft. The draft's direction is kept; changes:

- **The attention state model is now the spine of the plan.** The inbox, notifications and capability gating all derive from one Hub-computed `State`, so it is specified first (below) instead of being an inbox detail in Phase 5.
- **Phase 0 no longer blocks all UI work.** The UI renders strictly from Hub-reported `Capabilities`, so it can ship before the provider capability matrix is complete; anything not demonstrated is reported as unsupported and stays hidden. Phase 0 still gates *enabling* capabilities.
- **Library extraction is moved after the UI milestone.** Extracting `AgentSessionService` into a library before the state model has stabilised would freeze the wrong API. The boundary rule (provider knowledge never reaches Android or RpcApiHub) is enforced now; the physical project split happens once the model has settled.
- **Origin ("Discovered" vs "Omni-started") is gone from the UI.** What matters is whether *another client is driving the session right now* (`LiveElsewhere`), because that decides whether sending is safe.
- **App-level state stays on Android for now** (archive, "seen", per-session notification level). It is per-user presentation state; it can move to the Hub when there is a second client (desktop/Tauri).
- Visual reference is now `artifacts/mockups/omni_attention_inbox_v4.html` (supersedes the v2/v3 downloads).

---

## Principles

1. **The provider session is the durable thing.** Omni is one client of it. A session is never read-only merely because Omni did not create it.
2. **Render from capabilities, not provider names.** Android never branches on "Codex"/"Claude" for behaviour (only for a label/letter).
3. **Provider knowledge lives below RpcApiHub.** CLI flags, transcript formats, resume commands, terminal commands and capability notes come from `AgentSessionService` (later the library).
4. **No fake controls.** "Stop" exists only when Omni owns the running process. Archive is UI removal, never termination.
5. **Recoverable.** Turn state lives in the Hub; Android can disconnect, come back and reconstruct everything from `RequestAgentSessions` + `RequestAgentSessionDetail`.

---

## Attention state model (Hub-derived)

Every summary/detail carries:

| Field | Meaning |
| --- | --- |
| `State` | `working` · `question` · `permission` · `error` · `limited` · `idle` · `starting` |
| `StateSince` | ISO time the state began (drives "waiting 6m" on rows) |
| `Activity` | One short line: current tool while working, last answer while idle, error text on error |
| `LiveElsewhere` | Transcript is advancing but Omni is not running the turn (PC terminal / IDE) |
| `OmniTurn` | Omni's own process is running the turn (enables Stop) |
| `Question` | `{Text, Options[]}` when `State == question` |
| `Error` | Message when `State == error` |
| `Capabilities` | See below |
| `TerminalCommand` | Interactive resume command for the raw terminal escape hatch |

Derivation order (first match wins):

1. Omni-run turn in flight → `working`, `OmniTurn = true`.
2. Omni-run turn failed and transcript has not moved since → `error`.
3. Structured pending question in the transcript (Claude `AskUserQuestion` without result) → `question`.
4. Last provider message reports a usage/rate limit → `limited`.
5. Transcript advancing (Codex: `task_started` without `task_complete`; others: last event is a tool/user event and file touched recently) → `working`, `LiveElsewhere = true`.
6. Otherwise → `idle`.

`permission` is reserved: no provider exposes pending approvals through transcripts or the current headless backends. It lights up when a structured backend (Codex app-server, Claude stream-json with permission prompts) is added.

Android derives the presentation sections from `State` plus local state:

- **Needs you** — `question`, `permission`, `error`; oldest first.
- **Working** — `working`/`starting`.
- **Finished** — `idle` and modified after the user last opened it (local "seen").
- **Usage limited** — `limited`.
- **Idle · last 24h** — remaining sessions modified in the last 24h (auto-filled with every discovered session; collapsed after 3).
- **Ended today / History** — archived sessions and everything older than 24h.

## Capabilities

`CanSend`, `CanQueue`, `CanSendParallel`, `CanInterrupt`, `CanAnswerQuestions`, `CanApproveTools`, `CanChangeModel`, `CanObserveLiveEvents`, `CanOpenTerminal`, plus `Backend` and `Note` (human-readable description of the control backend and its limits).

### Turn mode and busy sessions (decided 2026-10-01)

- Turns started from Omni run with **full permissions and automatic approval**: Codex `--dangerously-bypass-approvals-and-sandbox` with the user's own config, Claude `--dangerously-skip-permissions`, Antigravity `--dangerously-skip-permissions --print-timeout 0`. Approval prompts therefore never block a phone turn, so the `permission` state stays rare.
- Messages sent while a turn is running are **queued in the Hub** (`SendAgentMessage(id, text, "auto")`) and dispatched in order when the session is idle again (a usage-limited session is retried every 15 minutes). Queued messages are visible on the session and can be cancelled. The queue is in memory: a Hub restart drops it.
- While *another client* is mid-turn, the sender can choose **Send now** (`mode = "now"`), which starts a parallel headless turn on the same session. Whether that is safe per provider is still an M2 question; the UI labels it as a parallel turn.
- **Delivery into sessions open on the PC** (no fork), via `OmniAgentHook.exe` (`OmniSync.Hub/src/OmniSync.AgentHook`, NativeAOT, published by `build_run_omnihub.py` to `%LOCALAPPDATA%\Omni\AgentHook`, installed by the Hub into `~/.claude/settings.json`; backup `settings.json.before-omni-hooks`):
  - The Claude Code hook (SessionStart/End, UserPromptSubmit, PostToolUse, Stop) writes `~/.omni/agent-live/<session>.<pid>.json` (client `app`/`terminal`/`omni`, busy flag) and delivers `~/.omni/agent-inbox/<session>.jsonl` as additional context after a tool call, as a Stop-hook continuation at turn end, or with the next prompt. ~13 ms per tool call when idle.
  - Terminal clients: the Hub has the helper type the message into the agent's console (`inject <pid> <base64>`), idle or mid-turn.
  - The Hub exposes `OpenIn` and `SendOptions` (`type`, `inbox`, `queue`, `now`; first = default); the phone renders them as chips.
  - Validated 2026-10-02: Hub → terminal Claude session (typed, answered in the same session) and Hub → a Claude desktop-app session mid-turn (delivered by PostToolUse).
  - Gaps: an idle desktop-app session only receives the message on its next activity (no wake-up); Codex/Antigravity terminals and the Codex desktop app are not detected yet.

---

## Milestones

### M1 — Real control + new inbox (this revision; implemented)

Hub (`AgentSessionService`, `RpcApiHub`):
- [x] State model above on summaries and detail; listing parses sessions active in the last 24h, light scan for older ones (now including `Cwd`).
- [x] Turn tracking per session: start time, owning process, failure + failed message.
- [x] `SendAgentMessage(id, text, mode)` tracked, with a per-session queue; `CancelQueuedAgentMessage`; `InterruptAgentTurn`; `RetryAgentTurn`.
- [x] `CreateAgentSession(provider, cwd, message)` with a synthetic `starting` entry until the new transcript is discovered, then matched by provider + cwd + creation time.
- [x] `AgentSessionsChanged` broadcast on every turn transition.
- [x] Capabilities, backend note and terminal command from the service.
- [x] Validation script `TestScripts/AIFeature/agent_inbox_state_test.py`.

Android:
- [x] Inbox per mockup v4: sections, project chips, inline answer/retry/handover, row menu (long-press or ⋯), archive with undo, history with search, offline banner.
- [x] Detail: status strip with Stop, collapsible tool groups with diff/output, question card, capability-aware composer with mode hints, slash command menu, ⋮ menu (notifications, handover, raw terminal, capabilities, archive).
- [x] New session sheet; handover sheet with editable summary (create in the target provider).
- [x] Raw terminal escape hatch through the existing remote terminal (`TerminalCommand` in the session's cwd).
- [x] Notifications on transitions to needs-you / finished / limited with per-session level; tap opens the session.

### M2 — Provider control envelope (Phase 0 of draft)

Turn `artifacts/agent-reader/verify_*.cjs` into durable integration tests and produce a measured capability matrix. Questions to answer per provider:

- Does session identity alone allow continuation for sessions created in a terminal / desktop app / IDE, long ago?
- What survives resume: cwd, project instructions, model, effort, permission mode, MCP, skills, compaction?
- Concurrency: what happens when a headless turn runs while an interactive client is mid-turn (reject / queue / fork / corrupt)? → decides `CanSend` under `LiveElsewhere`.
- Interrupt of turns Omni did not start.
- Structured questions/approvals: can they be observed *and answered*?
- Settings mutation (model, effort, permissions) and scope (turn / session / fork).
- Failure behaviour: auth expiry, rate limit, crash, truncated transcript, schema change. A failed Omni operation must never damage the provider session.

### M3 — Turn mode (done)

Decided: full permissions with automatic approval (see "Turn mode and busy sessions"). Busy sessions queue messages, with an optional parallel turn while another client is mid-turn. Validated live for Claude and Codex, including an agent writing a file without any prompt.

### M4 — Structured backends

Add richer control backends while keeping transcript+resume as fallback:
- Codex app-server (threads, turns, approvals, interrupt, token usage, streaming).
- Claude stream-json with permission prompts.
- Antigravity long-lived stream-json.
- OpenCode production adapter (discovery + continue; POC exists).

These enable `permission` state, Allow/Deny, live streaming, model change, interrupting external turns.

### M5 — Library extraction

Move discovery, parsing, normalization, resume/create, capability probing into `AgentSessions.Core` + per-provider projects with their own tests. OmniHub keeps SignalR, attention state, notifications policy, orchestration. Event model grows toward `AgentSession → AgentTurn[] → AgentEvent[]` with sequence numbers so Android can request "events after N".

### M6 — Parity and retire `AI_CHAT`

Inventory the old screen (terminal launcher, presets, voice input, file-browser launch, quick actions, screenshots) and classify each: move / replace / keep as terminal fallback / obsolete. Remove `AI_CHAT` only once daily use no longer needs it. Keep the remote terminal as the deliberate escape hatch.

---

## Definition of done (unchanged in spirit)

1. Externally-created sessions are discovered and continued from Android without Omni having created them.
2. Working / finished / error / needs-you state is visible and notified.
3. Questions and approvals work where providers support them; unsupported controls are hidden.
4. Android reconnects and reconstructs state.
5. New sessions use the same abstraction.
6. Raw terminal remains available.
7. Provider-specific code lives below OmniHub's application layer.

## Code anchors

- Android: `ui/screen/AgentInboxScreen.kt`, `ui/screen/AgentSessionDetailScreen.kt`, `ui/screen/AgentUi.kt`, `utils/AgentInboxStore.kt`, `data/repository/SignalRClient.kt` (agent section).
- Hub: `Logic/Services/AgentSessionService.cs`, `Models/AgentSessionModels.cs`, `Presentation/Hubs/RpcApiHub.cs` (agent section).
- Mockup: `artifacts/mockups/omni_attention_inbox_v4.html`.
- POCs: `artifacts/agent-reader/`.
