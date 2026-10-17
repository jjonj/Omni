# Google Home voice commands

OmniHub can be controlled by voice through Google Home / Nest speakers (Gemini for Home included).
Google shut down custom voice apps in 2023, so OmniHub instead pretends to be a smart home
device maker: Google sees OmniHub's commands as **scenes**, and saying
"Hey Google, activate &lt;scene&gt;" makes Google call OmniHub, which runs the matching macro.

## How it works

```
Speaker ──voice──▶ Google ──HTTPS──▶ Tailscale Funnel ──▶ OmniHub :5055 /google-home/* ──▶ macro
```

| Piece | Where | Notes |
|---|---|---|
| Google project | Google Home Developer Console, Cloud-to-cloud integration **OmniDevice** | Left in "Ready to test" forever. Only the developer's Google account can link it. |
| Public HTTPS | Tailscale Funnel: `https://desktop2024.tail8699ce.ts.net` → `http://127.0.0.1:5055` | Started with `tailscale funnel --bg 5055`; survives reboots. Check with `tailscale funnel status`. |
| Bridge port | OmniHub listens on **5055** | Only `/google-home/*` is served on this port, everything else returns 404, so the rest of OmniHub stays private. |
| Endpoints | `Presentation/Controllers/GoogleHomeController.cs` | `GET /google-home` (status), `/auth`, `/token` (OAuth), `/fulfillment` (SYNC / QUERY / EXECUTE / DISCONNECT). |
| Logic | `Logic/Services/GoogleHomeService.cs` | Builds the scene list, runs macros, issues tokens, optional HomeGraph request sync. |

### Account linking (OAuth)

Google insists on an OAuth account link. OmniHub implements a single-user fake:
`/auth` approves immediately and redirects back to Google with a code; `/token` only hands out
tokens to a caller with the right client secret (which only Google has). Tokens are stored hashed.

### Files

All in `%APPDATA%\OmniSync\`:

| File | Contents |
|---|---|
| `google_home.json` | Generated OAuth client ID + secret (entered in the Developer Console) and issued token hashes. Deleting it forces a new client ID/secret and a relink. |
| `google_home.log` | Every request on the bridge port, plus fulfillment request/response bodies. First place to look when something fails. |
| `google_home_service_account.json` | Optional. A Google service account key with HomeGraph access; enables automatic re-sync when commands change. |

OmniHub also writes `[GoogleHome] ...` lines to the activity log.

## Adding a voice command

A voice command is any **macro whose Category is `Voice`**. The macro name is the spoken scene name.

1. Create a macro (hub UI, phone, or the `SaveMacro` SignalR method) with Category `Voice`,
   a short name, and a macro script, e.g. `run "B:\GDrive\Tools\05 Automation\sleep.bat"`.
2. Tell Google about it: say **"Hey Google, sync my devices"**.
   With a service account key present OmniHub requests the sync itself a few seconds after the change.
3. Say **"Hey Google, activate &lt;name&gt;"**.

Naming tips:

- Keep names short. Some names conflict with Google's built-in phrases ("sleep", "alarm", "timer");
  if direct activation is intercepted, create a Google Home automation with an unambiguous phrase
  such as "put my PC to sleep" that triggers the **Sleep** scene.
- Don't repeat the verb: "activate Notepad", not "activate Open Notepad".

### Built-in fixed scenes

The Google Home **scene** path is a fixed, parameterless activation: Google sends only
the scene ID, so extra dictated words cannot become macro arguments. For
arbitrary text, use the separate Google Keep voice-inbox path below.

| Scene to activate | Action |
|---|---|
| **Morning** | Speak today's date/time, up to four calendar events, and up to three pending local reminders through PC text-to-speech. Weather is not yet connected. |
| **Idle** | Lock the PC and request monitor-off; does not suspend the PC. |
| **Music** | Resume an available Windows media session if paused; otherwise open the [MrSuicideSheep playlist](https://www.youtube.com/playlist?list=PLDfKAXSi6kUZnATwAUfN6tg1dULU-7XcD) at its first video and request autoplay (configurable MusicSceneLaunchTarget). The supplied URL's trailing `test` was invalid. Browser autoplay restrictions may still require a user gesture. |
| **Pause** | Pause the current Windows media session, only if playing. |
| **Sleep** | Run the existing guarded PC sleep automation. |
| **Shutdown** | Run the existing PC shutdown automation. |
| **Dictate** | Listen through the **PC microphone** for up to 12 seconds and type recognized text into the window that stayed focused. It is not free-text input to Google Home, and the microphone is on the PC, not the speaker. |

These are registered as Category = Voice macros on Hub settings initialization,
with scripts "scene morning", "scene idle", "scene music", "scene pause",
and "scene dictate"; Sleep and Shutdown retain their existing batch files.
Their behavior is implemented by VoiceSceneService and Automation/voice_*.ps1.
Use "python TestScripts/System/test_voice_scenes.py" for a focused
**non-destructive** build and tests. Voice activation requires the updated
Hub to run and Google devices to sync. Android's existing **Type** mic button
(Dashboard, Settings and remote control) uses Android speech recognition and
sends its transcript through the remote keyboard's `INPUT_TEXT` path. It now
deduplicates active speech prompts and reports offline failure explicitly.
This is separate from the Google Home Dictate scene using the PC microphone.

DisplayFusion scenes: **NORMAL** and **SOFA**. Say "Hey Google, activate NORMAL"
or "Hey Google, activate SOFA". Jarvis's voice inbox accepts the same phrases.
Both run the saved monitor profile and automatically accept DisplayFusion's
**Keep Changes** countdown dialog.

Install or verify these scenes against the running Hub using
`python TestScripts/System/configure_displayfusion_scenes.py` (`--verify` for
read-only checks; `--verify --exercise` switches to NORMAL, then SOFA through
the voice inbox). The installer copies the confirmation script into
`%APPDATA%\OmniSync\Automation` and registers two Voice macros through the Hub.
After adding scenes, say "Hey Google, sync my devices" to refresh Google's list.

### Natural phrasing (no "activate")

In the Google Home app create a household automation: starter **"When I say …"**
(e.g. "put my PC to sleep") → action: the Omni scene. Use the app's automation editor, not the
web Script editor; Script editor voice starters don't fire with Gemini.

## Troubleshooting

| Symptom | Check |
|---|---|
| Linking opens a blank page | The Authorization URL in the console must be exactly `https://desktop2024.tail8699ce.ts.net/google-home/auth`. `google_home.log` shows which path Google opened. |
| Linking ends on a spinner | Usually harmless; the scenes are already synced. Back out and try a voice command. |
| "Sorry, OmniDevice is unavailable" | OmniHub not running, or the funnel is down: open `https://desktop2024.tail8699ce.ts.net/google-home` from outside the LAN. |
| New command isn't recognised | Say "sync my devices". In test mode syncing can stop working after ~30 days; re-open the project's Test page in the console to refresh it. Existing commands keep working regardless. |
| Need to relink from scratch | Unlink OmniDevice in the Google Home app (OmniHub revokes its tokens on DISCONNECT), then link again. |

## Testing

`TestScripts/System/test_google_home_bridge.py` plays Google's side against a running hub:
OAuth flow, SYNC, QUERY, EXECUTE (unknown scene only, so no real macro runs), token refresh.

```
python TestScripts/System/test_google_home_bridge.py
```

`--disconnect` also tests DISCONNECT, which revokes **all** tokens including Google's live link,
so OmniDevice must be relinked in the Google Home app afterwards. It is off by default.

## Free text: the voice inbox (prototype)

Scenes can't carry free text ("add a Wartribes note that ..."), so free text goes through a
separate path built as a general command pipeline:

```
"Hey Google, add <text> to my Omni list"
  → Google Keep list "Omni"
  → keep_bridge.py (polls every 60 s)   ──POST /api/voice-inbox──▶  VoiceInboxService
  → item deleted from the list once the hub accepted it              → handlers, first match wins
```

| Piece | Where |
|---|---|
| Keep watcher | `OmniSync.Misc/KeepBridge/keep_bridge.py`, Python venv in `artifacts/keep-venv` (gitignored). Started and restarted by `KeepBridgeHostedService` once credentials exist. |
| Endpoint | `POST http://127.0.0.1:5000/api/voice-inbox` with header `X-Omni-Api-Key`, body `{"text": "...", "source": "...", "sourceId": "..."}`. Port 5000 only; not exposed through the funnel. Any local tool can use it. |
| Pipeline | `Logic/Services/VoiceInbox/VoiceInboxService.cs`. Every item, handled or not, is appended to `%APPDATA%\OmniSync\voice_inbox.jsonl`, written to the activity log, and broadcast to clients as the SignalR event `VoiceInboxItem`. |

On each Hub/Keep-bridge startup, items found on the first successful Keep poll are submitted with `isBacklog: true`. Commands decide through their queue policy whether such old items should execute or be skipped.

### Handlers (in order)

| Handler | Matches | Does |
|---|---|---|
| `MacroVoiceHandler` | the name of a Voice macro, optionally after "run" / "activate" / "start" / "do" | runs the macro (same commands as the scenes) |
| `DeterministicCommandVoiceHandler` | any registered deterministic assistant Command | strips generic modifiers such as `in one hour`, applies queue/staleness policy, then executes or schedules the command. |
| `NoteVoiceHandler` | "&lt;project&gt; note &lt;text&gt;", "add a &lt;project&gt; note that &lt;text&gt;", "note &lt;text&gt;" | appends `- [ ] <text>` to `Voice Inbox.md` in the Obsidian folder named like the project (vault root, then one level down). Unknown project → vault root `Voice Inbox.md` with the project as a label. |
| _(future)_ LLM/Jarvis fallback | everything else | interpret requests that the deterministic handlers cannot resolve. Until then unmatched items are logged as `unhandled`. |

Example: "Hey Google, add Wartribes note implement dagger attacks to my Omni list" →
`Wartribes/Voice Inbox.md` gets `- [ ] Implement dagger attacks`.

### Adding a handler

1. Implement `IVoiceTextHandler` (`Name`, `TryHandleAsync` returning `null` to pass the item on).
2. Register it in `Program.cs` next to the others. Registration order is pipeline order; keep the
   catch-all assistant handler last.

### One-time Keep setup

Keep has no official API for personal Gmail accounts, so the bridge uses the unofficial `gkeepapi`
library, which needs a Google "master token". Generate it yourself (it is your Google login):

```
artifacts\keep-venv\Scripts\python.exe OmniSync.Misc\KeepBridge\keep_bridge.py setup
```

It explains how to copy the `oauth_token` cookie from `https://accounts.google.com/EmbeddedSetup`
(F12 → Storage → Cookies → accounts.google.com → search for `oauth_token`),
exchanges it, and stores the master token in `%APPDATA%\OmniSync\google_keep.json`. Treat that file
like a password: it grants full access to the Google account. `keep_bridge.py show` prints the list.
The hub notices the file within a minute and starts the bridge.

Risks: the unofficial API can break when Google changes Keep, and Google may revoke the master token
(re-run `setup`). Both show up as `[KeepBridge]` lines in the activity log.

### Testing

- `TestScripts/System/test_voice_inbox.py`: endpoint auth, unhandled item, public port isolation (writes nothing).
- `OmniSync.Hub/tests/OmniSync.Hub.Tests/Logic/Services/NoteVoiceHandlerTests.cs`: note phrase parsing against a temporary vault.
