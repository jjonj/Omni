# Android Phone Commands (Omni)

The **Processes → Command Execution** panel has **Remote · PC** (existing Windows commands/processes) and **Local · Phone** (Android executor and shared phone transcript). Local works offline; when connected, local and agent commands both appear in the Hub's command log.

## Remote API (Rust OmniHub, port 5000)

The existing `X-Omni-Api-Key` header is required for all endpoints. Do not embed the key in scripts or logs.

- `POST /api/phone/commands` with JSON `{"command":"volume show"}`: enqueue a command on the connected phone, receive its UUID.
- `GET /api/phone/commands/:id`: retrieve status, output and exitCode. Poll while queued/running.
- `GET /api/phone/commands`: latest shared commands, including those typed on the phone.

The Hub broadcasts `PhoneCommandRequested(requestId,command)` via the existing authenticated SignalR connection. The Android app executes through its app-owned `PhoneCommandService` and reports started/output/completed. This also works while the command screen is closed, provided the Android foreground service remains connected.

## Android commands

`help`, `info`, `apps`, `open <package>`, `url <url>`, `settings [wifi|sound|display|bluetooth|accessibility|apps|battery]`, `volume show|up|down|media N|ring N|alarm N`, `home`, `back`, `recents`, `tap X Y`, `swipe X1 Y1 X2 Y2 [ms]`.

Other input is executed through `/system/bin/sh -c` **as Omni's Android app UID**, not ADB/root. Each shell command has a 30-second timeout. Commands and output from agents and users share the same on-phone transcript (100 most recent entries). Hub history is bounded and in-memory.

App launching and settings pages may be restricted when Android considers the app backgrounded. Gestures/navigation need Omni Accessibility enabled. Some system settings cannot be changed by an ordinary app.

**Hardware volume keys:** PC volume is sent only while MainActivity is foregrounded **and** the current screen is Remote Control. All other times the keys go to Android normally.
