# OMN-0019 — Generalized persistent agent scheduler (proposal only)

**Status:** Design proposal, 29 October 2026. **No implementation or recurring schedule authorized yet.**
**Decision needed:** Choose A, B, or C below; confirm the first recurring job's time and scope before enabling it.

## User request

Keep Awake is useful for short-term work, but the global Omni Coordinator should also be able to run **daily, indefinitely** and perform a defined set of actions. The user requests alternatives and direct contact before implementation.

## What exists / constraints

- Existing **OMCPT Tools** (Omnish Rust, :8773) owns a 20-second polling loop for a **temporary** Keep Awake monitor. Its durable JSON is `%LOCALAPPDATA%/OmniHub/temporary-keep-awake-v1.json`; it targets an exact T3 session and hands delivery to T3. A current monitor's *duration* starts a persistent *final-wake phase*, rather than automatically ending a subscription. It is not a once-per-day calendar scheduler. Code: `D:/SSDProjects/Tools/Omnish/main/src-omnihub-tauri/src/mcp_keep_awake_temp.rs`; live/documentation caveats: `docs/OMNIHUB_TEMP_KEEP_AWAKE_BOOTSTRAP.md`.
- Existing **OpenRig** daemon (:7433) has durable SQLite-backed queue/wake machinery and a watchdog job scheduler; the watchdog scheduler is not itself a user-facing recurring agent job runner. The T3 deferred-wake monitor can process pending canonical queue intents. Code: `D:/SSDProjects/Tools/OpenRig/packages/daemon/src/domain/watchdog-scheduler.ts`, `integrations/t3-deferred-wake-monitor.ts`.
- OpenRig's desired role is **canonical managed scheduling**; avoid building a second authoritative queue. As of this proposal, the existing OpenRig manager seats have reported startup-context / authenticated seat-inspection / T3 bridge connectivity blockers (OW-0037). **Do not assume daily coordinator execution is operational until an existing-seat test proves it.**
- **Never** use ChatGPT Scheduled Actions for this. Do not change live monitors, replay UNKNOWN sends, create duplicate coordinator seats, or send to an unverified T3 conversation. An internal timer tick must not depend on a model response.

## Three concrete options

| | A. OpenRig-native recurrence (recommended) | B. Windows Task Scheduler trigger | C. Extend existing OMCPT Keep Awake |
|---|---|---|---|
| Authoritative schedule | OpenRig SQLite `recurring_jobs` + immutable `job_runs` | Windows Task Scheduler calendar trigger + minimal durable dispatch receipt | OMCPT Tools JSON store, new schedule records, existing 20 s loop |
| Dispatch | Existing OpenRig queue tagged by job occurrence ID -> existing managed coordinator seat/T3 delivery | Trigger a small CLI/endpoint dispatcher which enqueues the *same* OpenRig queue obligation | Existing T3 delivery queue with stable message ID, targeting exact T3 session |
| Restart / offline | OpenRig reloads due jobs; one catch-up per missed window by default | Windows trigger after missed start if configured; dispatcher reconciles durable run ID | Tools service rehydrates persisted next due time; no immediate blind send |
| Pause/unpause | SQLite status + OpenRig API/CLI; later TaskBoard/OmniHub UI | Enable/disable Windows task; job-level config toggle in adapter | OMCPT `pause/resume` with persisted status and Tools MCP interface |
| Advantages | One owner for agent queue, seats, leases and run receipts; scales to many rules | Fastest proof for **one** daily job; reliable OS calendar trigger | Reuses currently working registration, polling and T3 guarded delivery |
| Costs | New recurrence schema/CLI/UI and due-to-queue adapter; existing OpenRig/T3 blockers must first be resolved | Multiple schedules become Windows-task sprawl; more difficult integrated queue/status UX | Entrenches explicitly temporary, competing scheduler and split ownership of seats/queues |

**Recommendation — A:** Add recurrence as a **small OpenRig queue producer**, not a new supervisor or separate manager process. Reuse the existing daemon timer/watchdog pattern, SQLite, managed seat identity, queue and T3 bridge, but store recurrence separately from watchdog health jobs. Provide an operator CLI/API first; postpone calendar UI and generalized cron expression parsing. This is the leanest *maintainable generalized* solution. **B** is a reasonable explicit stopgap if only a single daily coordinator task is wanted immediately after connectivity is repaired. **C** is the least source work right now but wrong as a permanent architecture because the temporary Keep Awake code is explicitly intended for retirement.

## Shared functional contract (applies to A/B/C)

**Two different lifecycles:**

- **Keep Awake** = explicitly initiated, **bounded** monitor/lease scoped to an already assigned session and work item. It helps resume or observe *that run* on an interval. User or agent can complete/cancel it. It never silently creates future daily campaigns.
- **Recurring Job** = explicitly approved and named durable **schedule definition**: owner, target canonical coordinator seat, authorized run instructions, timezone, cadence, overlap/missed-run policy, enabled flag, and optional end date (default `none` only after user approves *indefinite*). Every due occurrence creates exactly one logical `runId`; the recurring definition survives execution and reboots. A run may acquire a temporary Keep Awake monitor **after** successful start; a tick is not a wake/assistant message.

**State model:** `draft -> enabled <-> paused -> disabled/deleted` for the schedule; independent occurrence state `due -> reserved -> queued -> accepted/started -> completed|failed|needs_attention`. A failed occurrence doesn't silently disable the definition; policy decides next run. No simultaneous occurrence for one job by default: when previous run is active, record `skipped_overlap` and run at next due time; configurable "queue one" later, no unbounded backlog.

**Schedule format, minimum useful version:** `daily at HH:MM` with IANA timezone (e.g. `Europe/Copenhagen`), plus optional `every N hours/days`; compute from calendar local time rather than adding 24 hours to UTC, surviving DST transitions. For nonexistent spring-forward times choose the next valid local time; for ambiguous fall-back times **run once**. On system suspension/offline restart, coalesce missed occurrences to **one catch-up run** within a user-configurable window (suggest default 24 hours), otherwise mark skipped; never flood a week's worth of jobs.

**Dispatch and delivery:** Schedule tick records `runId` transactionally before enqueue. Put it on the existing authenticated OpenRig queue with a deterministic idempotency key (`jobId + scheduled-local-date-time + UTC-offset/fold resolution`) and exact canonical seat. The backend must reconcile **queued / attempted / delivered / assistant-observed** separately. On uncertain delivery, freeze/reconcile the same request ID, never send a duplicate until proven safe; this preserves user drafts, T3 UNKNOWN and conversation binding. If old coordinator ChatGPT conversation was rebooted, resolve the **current verified conversation of the same existing T3 slot/seat** and deliver only when validated, rather than using a stale conversation URL or launching a new manager. A scheduler status tick never needs the assistant to reply.

**Operator authority / scope:** Creating a disabled *draft* is distinct from user approval to **enable indefinitely**. Record who approved the job, the explicit task/run instruction and board/project scope, the canonical target seat, and any capacity limits. Only authorized operator/owner can enable, edit, pause, resume, cancel or delete; agent self-completion may finish an occurrence but not grant recurring authority or extend a finite campaign. No security/auth overhaul required: reuse current OpenRig operator/queue authentication and existing local access model. Record audit events for changes.

**Controls and observability:** `schedule create/list/show/pause/resume/run-once/disable`; include `nextDue`, `lastDue`, `lastRunId`, latest queue/agent receipt, failure/attention count, and source of pause. A `run-once` is explicitly manual, and must not change next calendar due. Expose schedule status in existing TaskBoard/OmniHub UX only after CLI flows work. Raise a **real** human Inbox/action notification for persistent delivery failures or decisions, not every tick. Existing temporary Keep Awake monitors continue untouched until a separate, verified migration/retirement decision.

## First approved use case — pending human definition

Suggested job: `global-coordinator-daily`, bound to the **existing OpenRig Omni Coordinator seat** and its verified current T3 browser session, `Europe/Copenhagen`, once daily, indefinite by explicit approval; action: “Review cross-project Priority / stale In Progress / blocked technical tasks, continue the approved Auto Work scope and send one concise report.” This is only an example; **do not authorize an endless unbounded Auto Work campaign by inference**. Ask the user to confirm the **run time**, **allowed work scope/budget**, **missed-run policy**, and **which option**.

## Minimal staged delivery if A is approved

1. Restore verified authenticated OpenRig existing-seat inspect and T3 bridge; do *not* launch a duplicate manager, restart T3 blindly, or replay UNKNOWN.
2. Create a persisted disabled schedule entry, `job_runs`/due producer with unique runId, simple daily local-time semantics and operator pause/resume. Wire only into existing canonical queue, no new dispatch transport.
3. With user-specified schedule and explicit enable consent, prove one disposable/manual run produces exactly one queued obligation, same coordinator seat, one observed assistant response; exercise service restart/downtime catch-up and pause/resume with focused checks.
4. Then turn on the real recurring job and add existing UI visibility; keep old temporary keep-awake registrations untouched until their independent replacement decision.

## Human decision requested on OMN-0019

**Which scheduler approach do you want?**

- **A — OpenRig-native recurrence (recommended):** permanent, one canonical agent queue, works for many recurring jobs; requires existing seat/bridge recovery first.
- **B — Windows Task Scheduler trigger:** smallest one-daily-job stopgap, with durable OpenRig dispatch receipts; more fragmented at scale.
- **C — Expand OMCPT Keep Awake:** quickest re-use of current timer, but retains the temporary competitor and needs eventual migration.
- **Do not build yet / revise the proposal.**

No source code, scheduled task, Keep Awake monitor, or live process was changed for OMN-0019.
