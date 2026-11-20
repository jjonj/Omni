# OMN-0141 — T3 ChatGPT sessions / Omni Android AI parity (2026-11-20)

Source comparison: T3 browser sidebar vs Android AI screen.

| Desktop behavior | Android audit finding | New behavior |
| --- | --- | --- |
| Personal red/orange/yellow/green/blue/purple/pink highlights | Phone had set action but ignored color on list | T3 publishes borderColor; Android draws highlight rings and filters by multiple colors |
| Maximum conversation length with purple ring; sending disabled | Phone had no indicator or send fence | T3 publishes conversationFull; Android displays purple FULL, explanatory text, prevents new sends, and blocks previously queued sends when authoritative desktop says full |
| Explicit ChatGPT temporary-limit loading failure | Phone received generic Errored or Unknown while Loading | T3 publishes bounded known error reason even for an awake guest whose navigation is still Loading; Android shows red LIMIT badge and full reason |
| Connection interrupted / delivery timed out | Phone rendered generic Errored | T3 publishes recognized reasons; Android distinguishes orange interruptions and red failures |
| Removed browser user-question marker | Phone still treated WaitingOnUser as generic browser attention | Browser ignores legacy WaitingOnUser; genuine GMA/Return and native T3 thread waiting status remain |
| Awaiting verification | Desktop had amber status; phone lacked it | T3 publishes verificationPending and Android displays VERIFY |
| Caffy / GMA / hidden / snoozed | Already implemented in previous parity passes | Existing behaviors preserved |

## Detection and transport

- T3 owns the browser/DOM detectors. Android never searches quoted chat content for status phrases.
- Temporary-limit status only comes from an awake resident guest. A real load failure can be reported before navigation reaches Success, without claiming the chat can receive messages.
- Provider schema additions are optional for older desktop providers.
- Phone offline outbox preflight rereads authoritative sessions.get before dispatch and retains a full-chat message pending with a blocking explanation.
- Actual automatic recovery remains T3 desktop's responsibility.

## Verification and rollout

- Android assembleDebug offline PASS. APK: OmniSync.Android/app/build/outputs/apk/debug/app-debug.apk.
- T3 TypeScript check could not start because installed pnpm TypeScript target was missing (MODULE_NOT_FOUND). Source diff and whitespace checked; do not claim TypeScript typecheck passed.
- Source only. No T3 restart, Hub restart, Android installation, or on-device screenshot verification.
- After coordinated deployment: check personal purple highlight sync; terminal FULL warning; actual temporary-limit/interrupted/delivery-timeout screens; no browser user-question marker but genuine GMA/native interactions; blocked full-chat outbox.
- Mouse-only desktop drag/drop and hover cards are replaced by phone long-press controls and detail sheets rather than replicated verbatim.
