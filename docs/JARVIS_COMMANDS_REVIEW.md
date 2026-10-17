# Review — Jarvis deterministic command foundation

**Reviewed:** commit `74523aa` ("Add deterministic assistant command foundation") plus the Jarvis-relevant uncommitted hunks in `Program.cs`, `RpcApiHub.cs`, `OmniSync.Hub.csproj`, `VoiceInbox/*`, `VoiceInboxController.cs`, `keep_bridge.py`, `SignalRClient.kt`, `AndroidManifest.xml`.
**Spec:** `OmniNotes/Inbox/Jarvis.md` §3.2, §4, §5.1, §7, §27, §30.
**Date:** 2026-10-06
**Not counted against this work:** the other agents' unrelated working-tree changes and the OmniAgentHook NativeAOT publish warning. Where a finding is in nearby code that this commit did not introduce, it says so.

---

## 0. Verdict

The vertical slice works and the main decisions are right:
- Deterministic matching runs before any LLM.
- Timing modifiers are parsed outside the commands.
- The request is resolved *before* it is scheduled, so a bad request fails at request time instead of an hour later.
- Android runs `wake.pc` locally.
- Unknown text stays `unhandled`, ready for a later resolver.
- Commands don't know about Jarvis or LLMs.

As an **architecture** it is still a demo-shaped slice rather than the foundation §5.1 describes. The main structural gaps:

1. **Generic modifiers and policy live inside one handler, not in the pipeline.** Macros (the de-facto Routines) and notes never see modifiers or policy, so "activate movie mode in an hour" cannot work.
2. **There is no request/run model.** Nothing has an ID linking request → resolution → schedule → execution → result. There is no result channel back to the device and no idempotency. Spec §4 and §27 depend on all three.
3. **Staleness is modelled by *arrival path* (`IsBacklog`), not by *intended time*.** That produces wrong answers in both directions (§2.5).
4. **Plugin isolation is not delivered.** A broken or duplicate module takes down the whole Hub. Overlapping triggers resolve in reflection order. Triggers, examples and tests are not metadata, so "add one file" does not bring its own validation or make the command discoverable to a resolver.
5. **"Host" is a label, not a mechanism.** There are two unrelated catalogues (C# classes, a Kotlin `when`). Neither side knows what the other can do. No host selection step exists, and there is no cross-host dispatch.
6. **Execution runs synchronously inside the ingress call** (HTTP request / SignalR invocation). This causes duplicate execution through Keep retries, a blocked phone connection, and orphaned PowerShell processes on disconnect.

None of these need a rewrite. Each is a bounded refactor of the code that already exists (§4).

---

## 1. Architecture review against the spec

### 1.1 Pipeline shape

Spec §5.1:
`Input adapter → generic modifier parser → command queue/scheduler → deterministic router → registry → execution-host selection → executor → execution/result log`

What was built (Hub):
```
Keep / SignalR / HTTP
  → VoiceInboxService (ordered handler chain, first non-null wins)
      → MacroVoiceHandler      (no modifiers, no policy, fire-and-forget)
      → DeterministicCommandVoiceHandler
            → ExecutionModifierParser → Registry.TryResolve (first match)
            → [Host==Android ⇒ pass] → [IsBacklog policy] → Scheduler | Execute inline
      → NoteVoiceHandler       (no modifiers, no policy)
  → voice_inbox.jsonl + HubMonitor log + broadcast "VoiceInboxItem"
```

Assessment:
- **Resolve-then-schedule is better than the spec's order.** The spec puts the queue before the router, which would schedule unparsed text and only discover an hour later that it means nothing. Keep this order and update the spec.
- **The modifier parser is a step inside one handler, not a pipeline stage.** Spec §5.1: "the same scheduling mechanism should therefore work for `wake.pc`, media commands, routines, and later capabilities." Today it does not apply to macros, notes, or a future resolver/Skill handler. Every future handler would have to call the parser again, which is exactly what "parsed once" was meant to prevent.
- **Two command systems sit side by side.** Voice macros (`MacroVoiceHandler`) are user-defined Routines in all but name, and `NoteVoiceHandler` is a deterministic command (`note.add(project, text)`). They sit beside the registry instead of inside it, so they get no descriptor, policy, scheduling, host or result semantics. Over time "a command" will mean three different things.
- **The chain is "first handler wins", with fixed registration order in `Program.cs`.** That is fine for 3 handlers, but it is the central-switch problem one level up. Ordering between command families has no stated rule.
- **There is no host-selection stage**, and no persistent execution/result log (§1.6).

### 1.2 Plugin / self-registration model

Goal (§5.1): *"An agent should be able to teach Omni a new deterministic command by adding one isolated command module and validating it, without modifying the assistant core … reject invalid modules without breaking the rest of the assistant."*

What works:
- One class implementing `IAssistantCommand` in the Hub assembly is discovered by reflection (`AssistantCommandRegistration.cs`).
- No central switch, and no `Program.cs` edit per command.
- The command can take DI dependencies.

What does not hold up:

| Requirement | Status |
|---|---|
| Isolated: one bad module does not break others | **No.** A duplicate ID throws in the `AssistantCommandRegistry` constructor (`AssistantCommandRegistry.cs:16`). The registry is a dependency of the hosted `AssistantCommandScheduler`, so host start fails and **the whole Hub does not start**. The same happens if any command's constructor or DI dependency throws. A `TryParse` that throws also kills resolution for *all* commands on every input: `TryResolve` has no per-command try/catch, and `VoiceInboxService` turns the exception into `failed`, which stops the chain before `Note` runs. |
| Deterministic, unambiguous routing | **No.** `TryResolve` returns the first match in `Assembly.GetTypes()` order. That order is metadata order: stable per build, but nobody chooses it. Once two commands have overlapping patterns ("play X" movie vs "play X" YouTube), which one wins depends on file/type order. There is no specificity score and no ambiguity detection. |
| Trigger patterns/aliases declared | **Only in code** (regex inside `TryParse`). They cannot be listed, cannot be shown in a help/"what can you do" view, cannot be given to the LLM resolver as examples, and cannot be checked for collisions. |
| Typed argument schema | **Decorative.** `Arguments` is declared, but parse results are `IReadOnlyDictionary<string,string>` and nothing validates them against the schema. Nothing binds or validates arguments for a non-text invocation either, which is the path the LLM resolver will need. |
| Executor type/entry point | Implicit (`ExecuteAsync`). Fine for C#. A PowerShell/Python/HTTP executor still needs a C# class, so "add a .ps1 command" is not a one-file change. That is acceptable, but should be stated. |
| Safety/risk policy | **Missing.** |
| Parsing/execution tests travel with the module | **No.** Tests are written by hand per command in a separate project. A new command can ship without any, and no generic test checks that it doesn't swallow other commands' phrases. |
| Dynamic (data-defined) commands | **Not possible.** Discovery is "concrete classes in this assembly". Voice macros, future authored Routines (§8: "save the sequence as a reusable named routine"), and Android-advertised commands are *data*, not classes. The registry needs a provider seam. |
| Discovery scope | Only the Hub assembly. Fine for now, but the docs should say "one class in this folder", not "one module". |

**Verdict:** adding a command is low-friction (one class) but **not isolated** (it can take down the Hub or silently capture other commands' phrases) and **not self-validating**. Fixes are in §4.2.

### 1.3 Command metadata

`AssistantCommandDescriptor(Id, Description, Host, Arguments, QueuePolicy)` is too thin for what later layers will ask of it:
- The **resolver/Skills** need description, typed and *described* args, required/optional, and example utterances.
- The **router** needs specificity/priority, and whether the command opts in to timing modifiers.
- **Policy** needs staleness relative to the intended time, risk level, whether confirmation is needed per source trust, and idempotency (is it safe to re-run after a crash?).
- The **executor** needs a timeout and a concurrency key (two Stremio automations must not run at once).
- **Results** need a structured outcome (`Succeeded / Failed / Skipped(reason) / NeedsClarification(candidates)`) instead of `Summary` text. Today the integration test asserts on English text (`"Skipped queued media.play_movie"`).

The proposed descriptor is in §4.2.

### 1.4 Execution-host semantics

`AssistantExecutionHost { Desktop, Android, Either }` is a static label, and nobody performs host *selection*:
- The Hub returns `null` for `Host == Android` (`DeterministicCommandVoiceHandler.cs:32`). No Android-hosted command is registered in the Hub, so this branch is dead code.
- Android's catalogue is a hard-coded `when ("wake.pc")` in `AndroidAssistantCommandExecutor`. The Hub cannot see it.
- `Either` has no meaning anywhere (who decides, and on what basis: availability, latency, user location?).

Concrete use cases that the current model cannot express:
- **"Turn on my PC tomorrow at 7"**, said to the PC (Keep, or PC voice) just before shutdown. The command is Android-hosted, but the request arrives on the Hub. The Hub should hand a *scheduled invocation* to the phone. Today it is `unhandled`.
- **"Play the Tetris movie"** spoken on the phone while the PC is asleep. It is Desktop-hosted, but the Hub is unreachable. Today it is **dropped with a log line** (`SignalRClient.kt:1687`). Spec §3.2 step 5 says "forwarded … when available, preserving the original text plus parsed metadata". That needs an outbox.
- The future **resolver/Jarvis** must list *all* capabilities across devices to plan with them (§5: "Jarvis can discover and invoke capabilities in a structured way").

Recommendation (§4.5): treat host as **capability placement**. There is one logical catalogue. Each device *advertises* the commands it can execute, and a `HostSelector` picks a reachable host at dispatch time. Undeliverable work goes to the origin device's outbox or the target host's schedule.

### 1.5 Queue / staleness model

The current model mixes two mechanisms that answer the same question ("is it still appropriate to run this now?"):
- `RunFromBacklog` is checked only on inline Keep/HTTP items, using a flag the *client* sets (`isBacklog = first_poll of the bridge process`).
- `MaxLateness` is checked only for items the scheduler pulls out.

Problems:
1. **False positive: a fresh request gets skipped.** Spec §30's flagship flow: the user says "turn on my PC" (Android WOL), the PC boots, the Hub starts, and the user adds "play the Tetris movie" to Keep. The bridge's *first poll* happens ≥15 s after Hub start (`KeepBridgeHostedService.StartupDelay`) plus login time. Anything added in that window, and anything added while the bridge is in a crash/backoff restart (10–600 s), is flagged backlog. `media.play_movie` is then skipped although it is seconds old.
2. **False negative: a stale request runs.** For a `RunFromBacklog=true` command with `MaxLateness`, the inline path ignores `MaxLateness`. If `runAt` (computed from `OccurredAt`) is already in the past, it executes immediately however late it is (`DeterministicCommandVoiceHandler.cs:43`).
3. **Backlog + future time gets skipped.** "Play the Tetris movie in one hour" added while the PC is off, PC on 10 minutes later: the item is backlog, so it is skipped, though its intended time is still 50 minutes away.
4. Scheduled items that are overdue at Hub start are judged by `MaxLateness` only, never by `RunFromBacklog`. The two flags interact in ways a command author cannot predict.

The fix is one policy evaluated against the **intended execution time**:

```
intendedAt = runAt ?? occurredAt ?? receivedAt
lateness   = now - intendedAt
run iff lateness <= policy.MaxDelay      (null = no limit)
```

`IsBacklog` becomes a diagnostic hint only. Verified: gkeepapi's `timestamps.created` is tz-aware UTC (`node.py:727`), so the Keep `occurredAt` is trustworthy for this.

### 1.6 Request envelope, run log, result channel

Spec §4: *"The transport layer should preserve task IDs, conversation IDs, timestamps, source device, and result state."* §27 defines requested/started/complete/failed states.

Current state:
- `VoiceInboxItem(Text, Source, SourceId, ReceivedAt, IsBacklog, OccurredAt)` has no request ID, device ID or conversation ID.
- The schedule stores `(Id, CommandId, Arguments, RunAt, CreatedAt)`. It does not store the original text, source or requester, so a scheduled execution cannot be traced back or reported to its origin.
- Results go to `HubMonitor` log lines and to `voice_inbox.jsonl`. The jsonl records "Scheduled …" but **never the scheduled execution's outcome**.
- No result goes to the phone. The Hub broadcasts `VoiceInboxItem` to `Clients.All`, but Android has no handler for it. `SendJarvisTranscript` returns nothing, and Android uses fire-and-forget `send`. A voice user gets no "OK, playing Tetris (2023)" and no "couldn't find that movie".
- Cancel/list/"what's scheduled?" is impossible because nothing can address a scheduled run.

This is the most important missing abstraction. It is cheap now and expensive after Routines/Skills are built on top. See §4.1.

### 1.7 Two catalogues and duplicated modifier parsing

The C# `ExecutionModifierParser` and Kotlin `AssistantCommandRouter.parseModifiers` are line-for-line twins today. Both already share the same gaps ("in an hour" is not handled, §2).

Is the duplication acceptable for now? **Yes**, on two conditions:
1. **A shared golden test-vector file** that both test suites load. It turns drift into a red build in either project. Code generation (a grammar → C# + Kotlin) is not worth it until absolute times or prefix forms exist, roughly 3+ modifier kinds.
2. **Android keeps only what must work offline.** Anything that can wait for the Hub should be parsed only on the Hub. The Android parser should never grow a second copy of the desktop catalogue.

The command *catalogue* duplication is the bigger architectural problem (§1.4). The fix there is advertisement, not shared code.

### 1.8 Coupling to future Jarvis / Skills

What is clean:
- The command contracts contain nothing LLM-shaped.
- `DeterministicCommandVoiceHandler` is a thin adapter.
- `unhandled` is a clean fall-through.
- `SendJarvisTranscript` / `VoiceMode.JARVIS` is only an ingress name, which is acceptable.

The risk runs the other way: the contract is shaped **only for regex-on-text invocation**. The resolver and Skills (§5.1 steps 3–4) need:
- `Invoke(commandId, typedArgs)` with schema validation, without going through `TryParse`. `TryGet` + `ExecuteAsync(dict)` is the seed, but has no validation.
- A dry-run/explain mode ("what would this do?") for the resolver's confidence check and for tests.
- Structured `NeedsClarification(candidates)` results, so a Skill can ask a follow-up instead of the command guessing.

Nothing here couples Commands *to* Jarvis prematurely. The only premature item is `AssistantArgumentType.Integer/Boolean`, which is unused and harmless.

### 1.9 Trust and security architecture

- `SendJarvisTranscript` checks `IsAuthenticated`, which is correct and consistent with `ExecuteMacro`.
- **`SendTypeTranscript` (same voice feature, same hunk) has no auth check.** Any client that reaches port 5000 can type arbitrary text into the focused window, which means code execution if a terminal has focus. Fix it before merging the glue. (`MouseMove` is also unauthenticated, but that predates this work.)
- The shared static API key defaults to `test_api_key` (`appsettings.json`, `keep_bridge.py`, the test script), and travels over plaintext HTTP on all interfaces. The phone now also connects via the pushed public address. Until now this protected remote-control features. It is about to gate an assistant that the spec says will get *broad PC access*. That needs an architectural answer before Skills land: per-device tokens and TLS, or at least a non-default key enforced at startup.
- **Ingress sources differ in trust.** Anyone who can edit the Keep "Omni" list can now run commands on the PC: shared lists, Google Assistant on a shared speaker, a household member. The Hub's port gate on 5055 is good (only `/google-home` is served there). Commands should declare a **risk level**, and sources should carry a **trust level** (authenticated device > LAN API > Keep/Google Home). Policy then requires confirmation or denies High-risk commands from low-trust sources. That is the "safety/risk policy" bullet of §5.1, and it is cheap to add now.
- The Stremio command passes `movie.Id` from an external HTTP API into a PowerShell argument and then into a `stremio://` URL built with string quotes (`'"' + $movieUrl + '"'`). `ArgumentList` makes the PowerShell call itself safe, but a crafted Cinemeta `id` containing `"` would inject Stremio command-line arguments. Validate `^tt\d{1,10}$` in C#.

---

## 2. Implementation findings

Severity: **H** = wrong behaviour in an expected scenario, **M** = edge case or robustness, **L** = polish.

### Hub — handler / pipeline
| # | Sev | Finding |
|---|---|---|
| H1 | **H** | **Duplicate execution via Keep retry.** `keep_bridge.forward` uses `timeout=90`. Movie worst case ≈ Cinemeta 8 s + `Add-Type` compile ~2 s + window 15 s + sources 30 s + transition 15 s + sleeps ≈ 70–75 s. That is close to 90 s, and a slow Stremio cold start pushes it past. On timeout, `socket.timeout` (an `OSError`) is treated as "Hub unreachable" and the item is not deleted. Meanwhile Kestrel fires `RequestAborted`, so `WaitForExitAsync` throws, **but the PowerShell process keeps running** (it is never killed). 15 s later the item is re-forwarded and a second automation starts while the first is still clicking. The same duplication happens if `keep.sync()` fails after a successful forward (the delete is not persisted). Root cause: execution is synchronous inside the ingress request, and there is no idempotency on `(source, sourceId)`. |
| H2 | **H** | **The SignalR invocation blocks the phone's connection.** `SendJarvisTranscript` awaits the whole pipeline. ASP.NET Core SignalR's default `MaximumParallelInvocationsPerClient = 1`, so while a movie automation runs (up to ~75 s), every other invocation from that phone queues: remote-control mouse moves, clipboard, agent messages. `Context.ConnectionAborted` is passed down too, so a phone network blip aborts the run (orphaned script, outcome `failed`), and the final `SendAsync(…, cancellationToken)` in `VoiceInboxService` throws. |
| H3 | **H** | **The backlog flag gives the wrong answer** for fresh items after Hub start or bridge restart, and for future-scheduled items (§1.5, cases 1 and 3). This breaks the "turn on PC, then play movie" flow. |
| H4 | M | Inline execution ignores `MaxLateness`. An overdue `runAt` executes immediately however late (§1.5, case 2). |
| H5 | M | `TryResolve` has no per-command exception isolation, and first-match ordering is by reflection (§1.2). |
| H6 | M | When the Hub returns `null` for `Host==Android`, the text falls through to `NoteVoiceHandler`. If the phrase happens to contain "note", an Android command becomes a vault note. Unreachable today, but a trap. |
| H7 | L | `VoiceHandlerResult` is free text, and the test script asserts on that English string. |

### Hub — scheduler (`AssistantCommandScheduler.cs`)
| # | Sev | Finding |
|---|---|---|
| S1 | M | **Delivery semantics are undocumented at-most-once, and items are lost silently.** `TakeDue` removes and persists *before* executing (lines 86–100). A Hub crash or shutdown mid-run loses the item. On shutdown, the `OperationCanceledException` is caught by the generic `catch` (line 132) and logged as a *failure*. No run record survives either way. |
| S2 | M | **A corrupt store is silently discarded and then overwritten.** On load failure, `LoadFromDisk` returns an empty list (line 157), and the next `Schedule()` persists over the corrupt file. Move it aside (`assistant_schedule.json.corrupt-<ts>`) and surface the error. |
| S3 | M | **A persist failure is invisible to the requester.** `PersistLocked` only logs a warning, yet `Schedule()` returns an ID and the user is told "Scheduled …". The schedule then won't survive a restart. `Schedule` should throw or return a failure so the request is reported as not durably scheduled. |
| S4 | M | **Due items run sequentially on the poll loop.** One 75 s automation delays every other due item, and there is no per-run timeout. Dispatch each run to an executor with a timeout and a concurrency key. |
| S5 | L | No list/cancel API, and no run outcome reaches the voice inbox history or the origin device (§1.6). |
| S6 | L | **PC sleep.** The Hub cannot wake the PC for its own scheduled items. "Play X in an hour" followed by the PC sleeping means the item runs late or is dropped by `MaxLateness`. Acceptable, but say so in the result or docs. A waitable timer could be a later option. |
| S7 | L | No argument schema version is persisted. A command whose argument names change will receive old dictionaries. Store `commandVersion` and reject on mismatch. |

### Hub — `media.play_movie` (`StremioMovieCommand.cs`, `play_stremio_movie.ps1`)
| # | Sev | Finding |
|---|---|---|
| M1 | **H** | **Silently plays the wrong movie.** With no exact name match, `candidates.FirstOrDefault()` (line 158) plays Cinemeta's top result whatever the similarity. Speech-to-text mishearing is the common case for voice, so "play the Teträs movie" plays whatever ranks first. Fix: score candidates (§4.7), and below a threshold return `NeedsClarification` with the top three (name + year) instead of playing. Even on a good match, include the year in the result ("Playing Tetris (2023)"), because remakes ("Dune", "Alien", "Total Recall") are the common ambiguity. |
| M2 | **H** | **F11 is a toggle.** If Stremio is already fullscreen (for example, the previous movie is still up), the script *leaves* fullscreen. Compare the window rect with the monitor rect (or `GetWindowLong` style) before sending F11. |
| M3 | **H** | **Stale page race.** The deep link navigates asynchronously, and polling starts 250 ms later. If Stremio is already on another movie's detail page, the first poll can find *that* page's 1080p source and click it. Before clicking, wait until the page shows the expected title (pass `-ExpectedTitle`), or until the previous source list has disappeared. |
| M4 | M | `$exe` is hard-coded to `C:\Users\crovea\AppData\Local\Programs\Stremio\…` (ps1 line 36). Use `$env:LOCALAPPDATA` or a parameter from Hub settings. |
| M5 | M | "1080p" matches any named UIA element: a description, a title like "1080p Remaster", or an addon label. Restrict the match to stream list items (ControlType `ListItem`/`Hyperlink`/`Button` inside the streams container). Try `InvokePattern` before the physical click, which also stops hijacking the user's cursor. |
| M6 | M | Only 1080p is accepted, with no fallback. Make it `-Quality 1080p,720p,2160p` (preference order) and report which one was chosen. |
| M7 | M | Cancellation and timeout do not kill the process (`WaitForExitAsync(ct)` at line 106, then `using` dispose). Add a `try/catch(OperationCanceledException) { process.Kill(entireProcessTree:true) }` and an overall timeout (~120 s) owned by the executor, not by the ingress token. |
| M8 | M | No concurrency guard. Two plays at once (H1, or two sources) run two scripts that fight over focus and cursor. Use concurrency key `"stremio"`. |
| M9 | M | Validate `movie.Id` (§1.9). |
| M10 | L | Title regexes accept stop-word titles. "open the movie" → title `the`. "watch out for the movie" → title `out for the`. Reject titles that are only stop-words or shorter than 2 letters. |
| M11 | L | Modifier/title collision: "play movie Gone in 60 Seconds" becomes `Gone` scheduled in 60 seconds. Rare, and fixed by command-level modifier opt-in plus trying both readings (§4.4). |
| M12 | L | Stdout is free text. Have the script emit a final JSON line (`{"ok":true,"source":"…","fullscreen":true}`) so the command returns structured evidence (spec §27, "verified complete" vs "apparently complete"). |

### Modifier parser (C# and Kotlin)
| # | Sev | Finding |
|---|---|---|
| P1 | **H** | **"in an hour" / "in a minute" are not parsed**, and neither is "in 1 hour and 30 minutes", "in twenty minutes", or "in an hour and a half". "Turn on my PC in an hour" is the most natural phrasing of the spec §30 acceptance case. With the suffix unparsed, the anchored `wake.pc` regex fails, Android forwards the text to the Hub, and the Hub has no `wake.pc`, so the request ends up `unhandled`, or **dropped** if the Hub is offline. Google speech-to-text emits digits for "one hour" but keeps "an hour" as words. |
| P2 | M | **Kotlin named groups on minSdk 24.** `match.groups["amount"]` (`AssistantCommandRouter.kt:61,63`) needs API 26. On API 24–25 it throws `UnsupportedOperationException`. JVM unit tests cannot catch this. Use positional groups. |
| P3 | L | Suffix-only: "in an hour, turn on my PC" is not supported. Fine for V1, but put it in the shared vectors as a known-unsupported case. |
| P4 | L | The redundant `\b` after `\s+`, and the `range.last == lastIndex` check that duplicates `$`, are harmless. |

### Android
| # | Sev | Finding |
|---|---|---|
| A1 | **H** | **A scheduled `wake.pc` is lost on phone reboot or app force-stop.** It exists only as an `AlarmManager` alarm, with no persisted record and no restore. The manifest already routes `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET` and `TIMEZONE_CHANGED` to `ReminderReceiver`, and that receiver starts the process, so `Application.onCreate` runs. The fix is a persisted store plus one `restore()` call next to `ReminderDelivery.restore(this)`. |
| A2 | **H** | **Desktop commands are dropped when the Hub is offline.** The phone only logs "Hub is offline; command was not handled locally" and loses the transcript. Spec §3.2 step 5 requires forwarding "when available". It needs an outbox with `occurredAt` (§4.6). A related race: during reconnect the connection can be `CONNECTED` but not yet `Authenticate`d, and the Hub throws `UnauthorizedAccessException`, which fire-and-forget `send` swallows. Flush the outbox only after authentication succeeds. |
| A3 | M | **No user feedback.** Immediate and scheduled `wake.pc` only write to the in-app log. A voice user needs a toast, a notification or TTS ("Waking your PC at 21:42"), and a notification when a scheduled wake fires or is skipped. |
| A4 | M | **Two WOL implementations.** The immediate path uses `MainViewModel.sendWakeOnLan`, and the scheduled path uses the new `WakeOnLanSender`. They differ subtly: `setBroadcast` timing, and the viewModel version catches a MAC parse error while `WakeOnLanSender.parseMac` throws. Make `sendWakeOnLan` delegate to `WakeOnLanSender`. |
| A5 | M | **"Sent" is not "woke".** `anySuccess` is true whenever a UDP send didn't throw, including a subnet broadcast sent over cellular to nobody. Spec §27 distinguishes these. After WOL, watch for the Hub connection for N minutes and report "PC is online" or "PC did not come online". |
| A6 | M | **No staleness policy on Android.** Once A1 is fixed, a phone that was off at 07:00 and boots at 10:00 will wake the PC 3 h late on restore. `wake.pc` needs `maxDelay` (~15 min), then skip and notify. |
| A7 | L | Exact alarms are fine. `USE_EXACT_ALARM` is install-time granted on 33+, so `canScheduleExactAlarms()` is true, and the inexact fallback only applies to SDK 31–32 with permission revoked. Log when the fallback is used. Verify network access in Doze on the device: `adb shell dumpsys deviceidle force-idle`, schedule `in 1 minute`, and confirm the packets with Wireshark or the PC waking. Allow-while-idle alarms grant a short temp allowlist, but OEM variants differ. |
| A8 | L | `requestCode` from `runAtMillis` makes two wakes at the same millisecond collide, and nothing can cancel a wake. Key by a run UUID stored in the persisted record, and add a `wake.pc.cancel` / "cancel the PC wake" command. |
| A9 | L | Aliases: "turn on the computer", "boot my PC", "start my PC". Add them to the vectors when needed. |

### Keep bridge (`keep_bridge.py`)
| # | Sev | Finding |
|---|---|---|
| K1 | **H** | `first_poll` as the backlog signal (H3, §1.5). |
| K2 | **H** | Retry duplicates (H1). The bridge cannot tell "Hub busy" from "Hub down". Once the Hub acknowledges after durable acceptance (§4.1), the bridge timeout can drop to ~10 s. |
| K3 | M | **Hub restart race.** The old bridge keeps forwarding until its next parent-PID check, possibly mid-`urlopen` for up to 90 s, and it holds the lock, so the new Hub's bridge exits as a duplicate and backs off. The old bridge has `first_poll=False`, so its items reach the *new* Hub as non-backlog. Age-based staleness (§1.5) removes the consequence. PID reuse on Windows is a theoretical gap. |
| K4 | L | `HTTPError` (401/500) is a subclass of `URLError`, so it is logged as "Hub not reachable" and retried forever. Distinguish 4xx: log it loudly, and don't retry auth failures every 15 s. |

### Integration glue
| # | Sev | Finding |
|---|---|---|
| G1 | **H** | `SendTypeTranscript` is unauthenticated (§1.9). |
| G2 | L | The `.csproj` `Automation\*.ps1` content item is correct for build and publish. Add a startup check (or registry validation) that each declared script exists, so a missing script shows up at boot rather than at the first voice command. |

---

## 3. Test gaps

What exists covers the happy path for parsing, one registry lookup, and the backlog skip. Missing, roughly in order of value:

1. **Generic contract tests over *all discovered* commands.** These come automatically with example metadata (§4.2):
   - every example parses to its expected args;
   - no example of command A matches command B (collision matrix);
   - descriptor validity (ID format, examples' args ⊆ schema);
   - the actual reflection discovery (`AddAssistantCommandFoundation` on a `ServiceCollection`). Today's "registry without central switch" test builds the registry by hand and does not exercise discovery.
2. **Registry isolation:** a throwing constructor, a duplicate ID, and a throwing `TryParse` all leave the other commands working.
3. **Policy:** a table test over `(occurredAt, runAt, now, isBacklog, maxDelay) → Run | Schedule | Skip(reason)`.
4. **Scheduler:**
   - persist/reload round-trip;
   - corrupt file moved aside;
   - an overdue item at startup goes through policy;
   - shutdown mid-run records `Interrupted`;
   - a persist failure surfaces.

   Inject `TimeProvider` (.NET 8) and a store path so the tests don't use `%APPDATA%` and `DateTimeOffset.Now`.
5. **Idempotency:** the same `(source, sourceId)` submitted twice executes once.
6. **Cinemeta title selection** as a pure function over recorded JSON fixtures: exact, normalized-exact ("the"/punctuation), remakes/year hint, weak match → clarification, empty `metas`, malformed JSON.
7. **Shared modifier vectors** (§4.4), run by both xUnit and JUnit, including "in an hour", "in 1 hour and 30 minutes", "Gone in 60 Seconds" (expected: not stripped for `media.play_movie`), and unsupported prefix forms.
8. **Android:**
   - schedule-store persist/restore;
   - restore with overdue items applies policy;
   - the receiver path with a fake sender (extract a `WakeOnLanTransport` interface);
   - outbox flush only after authentication.

   Robolectric is sufficient for `AlarmManager` (`ShadowAlarmManager`).
9. **System test (`TestScripts/System`):** add `dryRun: true` to the API so the script can assert the full routing and policy decision for `media.play_movie` (non-backlog, scheduled) without launching Stremio. Assert on structured `status/commandId/decision` fields, not on summary text. Read the API key from the same source the Hub uses instead of `test_api_key`.
10. **Manual or semi-automated Stremio checks** (this is UI automation, so record results in the doc): cold start, already on another movie, already fullscreen, no 1080p source, multi-monitor.

---

## 4. Improved design

The goal is to keep everything that works, move the cross-cutting parts out of `DeterministicCommandVoiceHandler` into the pipeline, and add the run model. Names below are suggestions.

### 4.1 Request envelope, run model, async accept

```csharp
public sealed record AssistantRequest(
    Guid RequestId,                 // client-generated where possible (Android outbox), else server
    string Text,
    AssistantSource Source,         // { Kind: AndroidVoice|Keep|Api|PcVoice|Resolver, DeviceId, Trust }
    string? SourceItemId,           // Keep item id → idempotency key (Source.Kind, SourceItemId)
    DateTimeOffset OccurredAt,      // when the user said/typed it (Keep created, phone capture time)
    DateTimeOffset ReceivedAt,
    string? ConversationId,         // reserved for Jarvis; carried, not interpreted
    bool DryRun = false);

public enum RunState { Accepted, Resolved, Scheduled, Running, Succeeded, Failed,
                       Skipped, NeedsClarification, Unhandled, Interrupted, Cancelled }

public sealed record CommandRun(
    Guid RunId, Guid RequestId, string? CommandId, int? CommandVersion,
    IReadOnlyDictionary<string, JsonElement>? Arguments,
    ExecutionModifiers Modifiers, string HostId,
    DateTimeOffset IntendedAt, RunState State, string? Reason,
    CommandOutcome? Outcome, DateTimeOffset UpdatedAt);
```

- An `AssistantRunStore` holds runs. Use SQLite (`Microsoft.Data.Sqlite`) or an append-only jsonl event log plus an in-memory index. It **replaces** `assistant_schedule.json`: scheduled runs are simply runs in state `Scheduled`. One store means no drift between the "schedule" and the "history".
- **Ingress acknowledges after durable acceptance, not after execution.** `SubmitAsync` resolves, applies policy, persists the run, and returns `{runId, state, commandId, decision}` within milliseconds. Execution happens on a `CommandDispatcher` (background), using the Hub's own `stoppingToken`, never `RequestAborted` or `ConnectionAborted`.
  - Keep: the 200 now means "accepted". The bridge deletes the item, and the 90 s timeout becomes ~10 s. H1 and K2 disappear.
  - SignalR: `SendJarvisTranscript` returns immediately, so H2 disappears.
- **Idempotency:** a unique `(Source.Kind, SourceItemId)` or `RequestId`. A resubmission returns the existing run.
- **Result fan-out:** on every state change, `AssistantRunEvents` publishes to:
  - the origin device (`Clients.Client(conn)` or by device ID: `"AssistantRunUpdated"`);
  - the PC home feed cards (spec §3.1);
  - `voice_inbox.jsonl`, which becomes a view over run events.

  Android shows a toast or notification and optionally speaks via TTS.

### 4.2 Command module contract v2

```csharp
public sealed record AssistantCommandDescriptor(
    string Id,                                  // "media.play_movie" — ^[a-z]+(\.[a-z_]+)+$
    int Version,                                // bump when argument shape changes (S7)
    string Description,
    IReadOnlyList<ArgumentSpec> Arguments,      // name, type, required, description, constraints
    IReadOnlyList<CommandExample> Examples,     // "play the Tetris movie" → { title: "Tetris" }
    HostPlacement Hosts,                        // Desktop | Android | Any(+preference)
    TimingSupport Timing,                       // AcceptsRelativeTime, AcceptsAbsoluteTime
    StalenessPolicy Staleness,                  // MaxDelay (null = unlimited), RunAfterHostRestart
    RiskPolicy Risk,                            // Low|Medium|High, MinTrustWithoutConfirmation
    ExecutionPolicy Execution);                 // Timeout, ConcurrencyKey, Idempotent (safe to re-run)

public sealed record ParseResult(
    IReadOnlyDictionary<string, JsonElement> Arguments,
    int Specificity);                           // e.g. count of literal tokens matched; router tie-break

public interface IAssistantCommand
{
    AssistantCommandDescriptor Descriptor { get; }
    ParseResult? TryParse(ParseInput input);    // input.Text, input.Culture; pure, no I/O
    Task<CommandOutcome> ExecuteAsync(CommandInvocation invocation, CancellationToken ct);
}

public abstract record CommandOutcome
{
    public sealed record Succeeded(string Summary, JsonElement? Evidence = null) : CommandOutcome;
    public sealed record Failed(string Summary, bool Retryable) : CommandOutcome;
    public sealed record NeedsClarification(string Question, IReadOnlyList<Choice> Choices) : CommandOutcome;
}
```

For the common regex case, a small `RegexCommandBase` lets a command declare `Patterns` and `Examples` as data and only implement `ExecuteAsync`. That keeps "add one file" short.

**Discovery and isolation.** `AssistantCommandCatalog` aggregates `IAssistantCommandProvider`s:
- `ReflectionCommandProvider`: today's assembly scan. It instantiates each type with `ActivatorUtilities.CreateInstance` **inside a try/catch per type**, rather than registering each type as a DI singleton.
- `MacroCommandProvider`: projects the user's voice macros as `routine.<slug>` commands. Macros get modifiers, policy and results for free, and `MacroVoiceHandler` goes away.
- `DeviceCommandProvider`: commands advertised by connected devices (§4.5).
- Later, `RoutineStoreProvider` for authored Routines (§8).

The catalogue validates every descriptor: ID format, examples ⊆ schema, examples parse to their own arguments, declared scripts exist. **Invalid or duplicate entries are quarantined, not fatal.** For a duplicate ID, the first one in stable provider order wins and the rest are rejected with a reason. The catalogue also exposes `GET /api/assistant/commands`, listing active and rejected commands with reasons. That endpoint is how an agent checks "did my new command register?" (§5.1 self-extension).

**Routing:**
```
candidates = catalog.Active
    .Select(c => (c, safe(() => c.TryParse(input))))   // per-command try/catch → quarantine on repeat throw
    .Where(x => x.result != null)
best = max by Specificity; if tie between different commands → Ambiguous(candidates) → Unhandled with candidates
```
An ambiguous result is useful input for the future resolver ("did you mean the movie or the YouTube video?").

**Generic tests.** One `[Theory]` iterates `catalog.Active × Examples` and the collision matrix. A new command with examples is tested on the next `dotnet test` with zero test code. That is the low-friction isolation the spec asks for. Command-specific tests (Cinemeta selection) stay hand-written.

### 4.3 Pipeline (Hub)

```
Ingress adapters (Keep HTTP, SignalR SendJarvisTranscript/SubmitAssistantRequest, PC voice, resolver)
  → AssistantRequestService.SubmitAsync(AssistantRequest)
      1. idempotency check (RunStore)
      2. ModifierExtractor  → tentative (strippedText, modifiers)
      3. Router             → try strippedText against commands whose Timing accepts the modifier;
                              else try fullText against all commands (modifier not applied)
      4. Bind & validate args against schema
      5. PolicyEvaluator    → staleness(intendedAt), risk×trust (confirm/deny), dryRun short-circuit
      6. HostSelector       → local | remote device | outbox/schedule on target
      7. RunStore.Persist(Run{Scheduled|Accepted})            ← ack returned here
  → CommandDispatcher (background)
      - timer wheel over Scheduled runs (wakes at next IntendedAt, not 1 s polling)
      - re-evaluates staleness at fire time
      - per-ConcurrencyKey SemaphoreSlim, per-command Timeout, Hub stoppingToken
      - Running persisted before execute; on startup Running → Interrupted
        (re-run only if Execution.Idempotent)
      - outcome → RunStore → AssistantRunEvents fan-out
  → Unhandled → (today) log + keep for review; (later) LlmResolverHandler maps onto catalog/Skills
```

`NoteVoiceHandler` becomes a `note.add` command with `Timing = None`, so "note call mum in an hour" stays a note (the modifier opt-in solves this). The handler chain shrinks to: deterministic catalogue → (later) resolver → (later) Skills.

### 4.4 Modifiers

- `ExecutionModifiers { RelativeDelay?, AbsoluteTime?, SourceSpan }`. These are resolved to `runAt` against **`OccurredAt`**, not against receipt time.
- Grammar for V1.1:
  - `in (a|an|one|<n>|<word n up to 59>) (second|minute|hour|day)s?`
  - `in half an hour`, `in an hour and a half`, `in 1 hour and 30 minutes`, `in 90 min`.
  - Then `at HH(:MM)?( am|pm)?` and `tomorrow (morning|at …)` once alarms need it.
- **Command opt-in plus a two-reading router** (§4.3 step 3). This fixes "Gone in 60 Seconds" and notes.
- **Shared golden vectors:** add `Shared/assistant/modifier_vectors.json`:

  ```json
  [{ "text": "turn on my PC in an hour", "command": "turn on my PC", "delaySeconds": 3600 },
   { "text": "in an hour, turn on my PC", "supported": false }]
  ```

  The xUnit test reads it via `$(SolutionDir)`-relative path (copy as test content). The Android JUnit test reads it via `File("../../Shared/…")` from the module directory. Both implementations stay hand-written, and drift fails CI. Revisit generation when absolute dates arrive.

### 4.5 Hosts and cross-device dispatch

- On authenticated connect, Android calls `AdvertiseAssistantCommands(deviceId, [descriptor…])` with `wake.pc` and, later, phone-local commands. The descriptors are a JSON projection of the same descriptor shape. The Hub's `DeviceCommandProvider` registers them as remote commands (`Hosts=Android(deviceId)`), with patterns, so the Hub router recognizes "turn on my PC in 8 hours" too.
- `HostSelector`:
  - Local host if the command can run here.
  - Otherwise, a connected device that advertises it, via `ExecuteAssistantCommand(runId, commandId, args)` or `ScheduleAssistantCommand(runId, commandId, args, runAt)`. Android persists it in its own schedule store and acks.
  - Otherwise `Failed("no host available")`, or keep it `Scheduled` and retry when the device connects, depending on `Staleness`.
- Android-originated requests: local-first as today. A non-local request goes to the Hub if the Hub is connected and authenticated, otherwise to the Android **outbox**.
- Kotlin side: replace the `when(commandId)` with a tiny `AndroidCommand` interface (`descriptor`, `parse`, `execute`) and a list. That is the same contract in miniature, and it is what gets advertised.

### 4.6 Android scheduled/offline design

```kotlin
data class ScheduledAssistantRun(val runId: String, val commandId: String, val args: Map<String,String>,
                                 val runAtMillis: Long, val createdAtMillis: Long, val origin: String)

object AssistantScheduleStore      // SharedPreferences JSON (same pattern as ReminderDelivery)
  fun add(run) { persist(); setAlarm(run) }          // persist FIRST, then alarm
  fun restore(ctx)                                    // from Application.onCreate (boot/update/time-set already start the process)
      overdue = runs.filter { it.runAtMillis <= now }
      overdue → policy: lateness <= maxDelay ? execute : skip + notification("Missed PC wake at 07:00")
      future  → re-setAlarm
  fun cancel(runId)

AssistantCommandReceiver.onReceive → goAsync → store.take(runId) → policy → execute → notify → finish
```

- `requestCode = runId.hashCode()`, data URI `omni://assistant/run/<runId>`.
- **WOL verification:** after sending, start a short watch (the existing foreground service, or a `WorkManager` one-off with a 3 min delay). It checks whether `signalRClient` reaches `CONNECTED` and authenticated with the Hub. If yes, notify "PC is online". If not, notify "PC did not respond to Wake-on-LAN". This turns "sent" into "verified" (§27).
- **Outbox:**

  ```kotlin
  AssistantOutbox  // persisted list of AssistantRequest(requestId, text, occurredAtMillis, source="android-voice")
    enqueue when hub not authenticated; flush after Authenticate() == true via invoke("SubmitAssistantRequest", json)
    remove only on ack {runId}; Hub dedupes by requestId; Hub applies staleness using occurredAt
  ```

  With per-command `maxDelay`, "play the Tetris movie" said while the PC sleeps runs if the PC comes up within 10 minutes (for example after a "turn on my PC" a moment earlier). Otherwise it is reported as skipped. This also gives the spec's compound example a deterministic path, without parsing "and": two utterances, the first local and the second queued.
- Fix P2 (positional groups) and A4 (single WOL implementation).

### 4.7 `media.play_movie`

**Title resolution (pure, unit-tested):**
```
normalize(s) = lower, strip diacritics/punctuation, drop leading "the/a/an", collapse spaces
yearHint     = trailing 4-digit year in the spoken title ("dune 2021")
score(c)     = 1.0 if normalize(c.name)==normalize(title) (+0.05 if year matches)
               else tokenSetRatio(normalize(c.name), normalize(title))  (0..1)
pick:
  exact matches ≥1  → if yearHint pick that year, else highest Cinemeta rank; report "Name (Year)"
  best ≥ 0.85       → play, report "closest match"
  else              → NeedsClarification(top 3 "Name (Year)")
```
Validate `id` against `^tt\d+$`. Cache the search for ~1 min so a clarification follow-up doesn't re-query.

**Script hardening:**
- Parameters: `-MovieId`, `-ExpectedTitle`, `-Quality "1080p,720p,2160p"`, `-StremioExe` (default `$env:LOCALAPPDATA\Programs\Stremio\stremio-shell-ng.exe`), `-TimeoutSeconds`.
- After the deep link, wait until a UIA element whose name contains `ExpectedTitle` is visible (M3).
- Match only stream list items. Use `InvokePattern` first and a physical click as fallback, restoring the cursor (M5).
- Only send F11 if the window is not already covering its monitor (M2).
- Emit a final `RESULT {json}` line. Exit codes: 2 = source not found, 3 = window/foreground failure, so C# can map them to `Failed(Retryable)`.
- C# owns the timeout and kills the process tree. Use `Execution.ConcurrencyKey = "stremio"`, `Timeout = 120 s`, `Staleness.MaxDelay = 10 min`, `Risk = Low`.

### 4.8 Staleness defaults

| Command | MaxDelay | Idempotent | Risk | Notes |
|---|---|---|---|---|
| `wake.pc` | 15 min | yes | Low | Android; verify by Hub reconnect |
| `media.play_movie` | 10 min | no | Low | concurrency `stremio` |
| `note.add` | unlimited | no (dedupe by request) | Low | no timing opt-in |
| `routine.*` (macros) | per macro, default 10 min | no | Medium | user macros can run anything |
| future `message.send` | 30 min | no | High | confirm unless trusted device |

### 4.9 Migration order (each step independently shippable)

1. **Security and correctness quick wins:**
   - G1 auth on `SendTypeTranscript`;
   - P1 "a/an" in both parsers, plus shared vectors;
   - P2 positional groups;
   - M2 F11 toggle check, M4 exe path, M9 ID validation;
   - M7 kill on cancel;
   - S2 corrupt-file move-aside.
2. **Unified staleness on `intendedAt`** (replaces `RunFromBacklog`; `IsBacklog` becomes a hint). Fixes H3, H4, K1.
3. **Run store plus async accept plus idempotency.** Fixes H1, H2, K2, S1, S3, and gives a result channel. Add `dryRun`.
4. **Catalogue v2:** providers, isolation, examples, generic tests, specificity routing. Fold macros and notes in.
5. **Android:** schedule store and restore, outbox, user feedback, WOL verification, single WOL implementation.
6. **Title scoring and `NeedsClarification`,** plus script hardening M3/M5/M6/M12.
7. **Device advertisement and `HostSelector`** (cross-host scheduling).
8. **Risk × trust policy**, before any Skill or resolver is allowed to invoke commands.

---

## 5. Answers to the specific questions

- **Will plugins be isolated and low-friction?** Low-friction yes, isolated no. A bad or duplicate module stops the Hub, overlapping patterns resolve by type order, and modules don't carry examples or tests. §4.2 fixes all three without changing the "one class" authoring model.
- **Are metadata, host semantics and queue policy sufficient?** Not yet. Metadata lacks examples, risk, timeout, concurrency, idempotency and versioning. Host is a label with no selection. Queue policy is split across two flags evaluated on different paths and keyed on arrival path instead of intended time (§1.3–1.5).
- **Scheduler persistence/error behaviour:** atomic write via temp+move is good. Delivery is undocumented at-most-once, items are lost silently on crash or shutdown, a corrupt file is silently overwritten, persist failure is not surfaced, runs block each other, and there is no run history (S1–S7).
- **Android offline/scheduled WOL:** works for the happy path with "in one hour" / "in 1 hour". It fails for "in an hour" (P1), is lost on reboot (A1), gives no feedback (A3), cannot be verified (A5), and drops non-local commands while offline (A2).
- **Duplicated C#/Kotlin parsing:** acceptable now *with* shared golden vectors. Don't generate code yet. Keep Android's set to offline-required commands only.
- **Exact-alarm and reboot:** the exact-alarm handling is fine (A7). Reboot and force-stop lose schedules. Add a persisted store plus restore in `Application.onCreate`, with a staleness check on restore (A1, A6).
- **Stremio ambiguity/robustness:** the silent first-candidate fallback, the F11 toggle and the stale-page race are real bugs. The hard-coded path and loose "1080p" match are fragile. Reusing the proven script is the right call; harden it as in §4.7 rather than replacing it.
- **Keep backlog semantics and races:** `first_poll` is the wrong signal (H3/K1). Synchronous execution under a 90 s client timeout causes duplicate execution (H1). The Hub restart race exists, but age-based policy neutralises it (K3).
- **Android → Hub auth:** `SendJarvisTranscript` is correct. `SendTypeTranscript` is open (G1). The default key and plaintext WAN path need attention before Skills. Commands need a risk × source-trust policy (§1.9).
- **Premature coupling to Jarvis/Skills:** none of concern. The gap is the opposite: no typed invoke, dry-run or clarification outcome for the resolver to use (§1.8).
- **Missing tests:** §3, especially generic catalogue tests, policy tables, scheduler persistence with `TimeProvider`, idempotency, Cinemeta fixtures, shared vectors, and Android store/outbox.
