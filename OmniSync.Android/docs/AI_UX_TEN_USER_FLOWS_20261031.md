# Omni Android AI — ten end-to-end user journeys (31 Oct 2026)

Scope: the production Android AI screen, `OmniAiModesScreen`, and its provider-neutral
`GenericAgentClient`. Visual direction: T3 Android's restrained tonal surfaces,
compact toolbar, prominent conversation list, readable conversation timeline and
always-accessible message composer. Preserve the Hub's provider-authoritative
history, source identities and delivery receipts.

The scenarios below are deliberately ordered as real journeys. "Covered" denotes
a code path now implemented and APK compiled, not proof that a connected phone
completed the action; actual device-side verification must not be inferred.

1. **Open AI when connected, disconnected, or reconnecting.**
   - Expected: immediately recognize the screen, never see raw provider/session-list
     diagnostics in the header, see a small connectivity state, and retain cached
     conversations while the Hub is unavailable.
   - Path: header now displays simply "AI" and connection dot; backend warnings
     moved to Providers; cached session snapshot and retry available even if offline.
   - Failure policy: an unavailable provider must not erase cached sessions.
2. **Find a conversation among many sessions.**
   - Expected: type a title or ID, filter Active/Attention, read each row's meaningful
     activity state, and choose the correct thread without a multi-step picker.
   - Path: filtered list with Needs You, Working, Conversations sections; paginated
     Hub sessions.list (bounded 4x400) rather than silently stopping at the first
     page; matches and unread indicators.
   - Limitation: Hub itself may mark upstream T3 discovery partial. This is not
     claimed exhaustive; diagnostics are in Providers.
3. **Open a thread, return to sessions, reopen it.**
   - Expected: same source session and conversation binding, visible loading state,
     Android Back returns to list, no leftover composer text from another session.
   - Path: selection/watch via exact namespaced session key; BackHandler; per-session
     persisted private draft; history-loading indicator.
4. **Read an active conversation while the agent works.**
   - Expected: new activity appears without repeated refresh taps, previous history
     remains intact, user reading older messages is not yanked to the bottom.
   - Path: Hub session.changed updates rows and prompts throttled selected-history
     refresh; eight-second fallback read; dedupe by stable history item ID/revision;
     follow-latest only while at bottom; explicit Latest action; older-cursor pagination.
   - Limitation: providers can return partial transcript/history; surface notice
     rather than inventing continuity.
5. **Send to idle or busy session.**
   - Expected: one tap persists a single durable intent before network submission,
     clears composer only after local save, identifies Queue vs WhenIdle, shows pending
     intent in the conversation; repeated taps must not duplicate a send.
   - Path: GenericAgentClient persistent outbox/idempotent request IDs, recipient
     conversation pinning, local receipt strip and native vs idle delivery labels.
   - Limitation: the provider controls when acceptance becomes delivery; an accepted
     receipt is not proof an agent completed a turn.
6. **Compose offline, switch sessions, return, reconnect.**
   - Expected: separate drafts per conversation, survive process restart, no
     accidental cross-recipient transmission, persist in outbox while offline,
     send only after the original provider/binding checks succeed.
   - Path: app-private per-recipient drafts; target identity gate; stored outbox;
     canDeliver preflight; wake on authenticated reconnect; no unconfirmed replay.
7. **Message a managed contact rather than the current session.**
   - Expected: explicitly switch recipient mode, enter address, queue safely,
     verify stable recipient, send only after confirmed identity; changed identity
     cannot silently redirect the message.
   - Path: distinct contact composer and draft; address resolution preview after
     enqueue; verification confirmation; pinned stableRecipient in outbox;
     verification available from Outbox after reconnect.
8. **Inspect a pending/failed/uncertain send and native provider queue.**
   - Expected: inspect exact target, state and error; copy text; verify blocked
     recipients; cancel native queued item where supported; NEVER auto-retry
     ambiguous operation.
   - Path: Outbox sheet, inline pending count, Copy text and Verify actions,
     explicit Unknown warning; 30-second Accepted/Queued reconciliation even if
     there are no new Pending items; separate native queue controls.
9. **Create/rename/stop/delete a session.**
   - Expected: only show available capabilities, confirm irreversible deletion,
     disable double-submit, report actual receipt state, find a newly created
     session, reconcile uncertain T3 creation rather than retry blindly.
   - Path: provider-gated UI, creation write-ahead dedupe ledger, result captured
     after refresh, created-session navigation, receipt-aware operation actions,
     disable repeated action while pending and explicit delete confirmation.
   - Limitation: creation can be unconfirmed, and should say so; no guessed session.
10. **Change provider, inspect archive, reach human inbox.**
    - Expected: provider controls tucked into a sheet, switching cannot silently
      move an already-queued intent, offline archive opt-in searchable,
      human inbox one tap away with a reliable back path.
    - Path: active provider sheet and confirmation, binding stays provider-specific,
      archive search with session navigation, human inbox from header/menu.

## Verification scope
- Kotlin compile and full debug APK assembly performed after the implementation
  (see build log / git commit).
- Source contract cross-checked against Rust Hub `agents/api.rs`,
  `agents/hub.rs` and `core/model.rs`: sessions.list paging, history cursors,
  queue edit, markSeen, action receipts and address confirmation.
- Phone deployment is performed via `build_and_deploy.py --hub-deploy`.
- Headless emulator visual inspection attempted separately; it does not replace
  a successful real-phone screenshot or a networked end-to-end message test.
- Do not send test messages to real agents or create destructive real sessions
  solely to mark the flows tested.

## Visual / interaction QA results (isolated Android emulator)
- Created an isolated Android 36.1 x86_64 emulator; debug APK installed and
  AI screen launched from Android intent without involving any real session.
- Seeded **five synthetic session records and sample messages** into the emulator
  app's private cache. Confirmed Needs You / Working / Conversations grouping,
  selected-row highlight, filtering, offline header, conversation navigation,
  selectable Markdown and collapsed tool output visually.
- First screenshot exposed unreadable light-theme field placeholders because
  the global disconnected Material theme expected light text. Fixed explicit
  field colors **and** scoped the entire AI Material palette. Re-screenshotted:
  the Search conversations prompt became legible.
- First screenshot also exposed a burgundy bottom bar beneath the light AI view.
  Scoped the AI tab's bottom navigation colors; subsequent emulator screenshot
  showed coherent white navigation and blue selected tab. Non-AI tab defaults
  remain the original Material NavigationBar defaults.
- Confirmed a typed emulator draft is persisted under the exact
  `session:t3:qa-001` app-private key. Navigating to another synthetic session
  clears it. A transient frame could display the previous session's text during
  initial switch; constrained rendered composer text and onChange to the
  currently loaded draft key.
- No live/real recipient messages sent. Hub's native Queue/WhenIdle acceptance,
  production contact-address identity verification, provider switching,
  session deletion, and real-provider streaming remain unverified on this
  isolated offline emulator; implementation paths were reviewed against
  Rust Hub contracts, not asserted exercised.
- During the first Android emulator boot, app startup was delayed and the OS
  briefly showed an ANR while first-run permission UI was involved. After
  emulator setup/permissions settled, the test client rendered consistently;
  no conclusion about production cold-start responsiveness from that incident.

## Second pass — overlooked edge cases (31 Oct 2026)

Focused on switching/reconnecting mid-read, offline outbox cleanup, duplicate
creation, recipient confusion and archive chronology.

- **History lost by manual Refresh:** `select(reload=false)` replaced cached
  history with a single latest page, discarding previously loaded older pages.
  Both manual and background reads now merge stable IDs/revisions against the
  current cache snapshot, rather than replacing the cache. Concurrent older
  and newer responses merge under a per-cache lock.
- **Infinite older-page loop:** after reaching the oldest message, refresh used
  to restore the previously exhausted cursor. Null now remains exhausted; an
  unchanged server cursor is also treated as exhausted.
- **Duplicate initial reads:** opening a conversation already requests history;
  the background watcher issued another full request simultaneously. New screen
  openings use the explicit selection read; the watcher performs subsequent
  reloads. Reconnection still triggers the full selection read.
- **Incorrect send affordance:** connected sessions without advertised Submit
  + Queue/WhenIdle capabilities, or belonging to a now-inactive provider, used
  to expose an enabled Send button. These are now disabled with an explanation.
  Offline recipients may still save a queued intent for later capability checks.
- **Misleading offline composer hint:** a draft is *not* a queued message. The
  composer now explicitly distinguishes saved draft from tapping Send to queue.
- **Reuse of identical new-session requests:** a successful T3 creation with the
  same first instruction could cause an intentional second Create to reuse the
  old worker. Only unresolved Unknown requests reuse their durable incident ID;
  a confirmed prior creation permits a new explicit Create.
- **Misleading pending count:** Unknown receipts were included in "pending".
  They are now a distinct "unconfirmed" count; no automatic retry is possible.
- **No cancellation/reconciliation cleanup:** users could not cancel an
  offline Pending/Blocked message or dismiss a Failed/Unknown receipt after
  manual inspection. Added confirmation dialogs with explicit states, persistence
  and a non-retransmitting removal path.
- **Archive ordering:** observed entries could appear in arbitrary hash-ID
  order because archive replay sorted ties lexically. Schema v2 stores observed
  positions and replays in batch source order, preserving old records during
  migration. Historic v1 entries acquire ordered positions when re-observed.
- **Archive search race:** outdated search results could overwrite newer queries
  when typing rapidly. Results now commit only for the current query.
- **Recipient leakage between conversations:** changing selected session now
  exits contact-address mode to avoid sending to a contact left over from a
  different conversation. Each session's own draft remains separate.
- **Outbox readability:** concise horizontal Copy / Cancel / Remove actions,
  clearer uncertain receipt instructions and a corrected outbox subtitle.

**Focused emulator evidence:** using an isolated Android 36.1 emulator with
synthetic offline session and outbox records, the Outbox sheet rendered a Pending
and an Unknown message distinctly. Confirmed the Pending cancel dialog and
verified its removal from the app-private JSON ledger: only the Unknown receipt
remained. These actions did not contact a real Hub or send a real message.

**Limit:** Real provider-side acceptance, network loss during InFlight, T3
creation, historical pagination and production-phone delivery still require
a connected-device observation. A successful APK build or synthetic emulator
run is not evidence of provider-side completion.
