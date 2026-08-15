# Local AI Client Remote Terminal Plan

## Status

This document is the implementation plan for replacing the dead Gemini CLI integration with a provider-agnostic remote terminal path.

The first milestone is deliberately narrow:

1. Android or an automated test asks the Hub to start a command such as `agy` or `codex` in a workspace.
2. The Hub launches a visible Omnish window.
3. Omnish opens a real PTY-backed terminal and types the requested command into it.
4. Input from Android is written to that same terminal.
5. Raw terminal output is shown locally in Omnish and forwarded through the Hub to Android.
6. An automated round-trip test proves that a prompt sent remotely produces recognizable output from the local AI client.

## Implementation progress

- [x] Defined the provider-agnostic boundary: raw terminal bytes only above the Omnish PTY.
- [x] Added a separate Hub `RemoteTerminalHub` and loopback-only Omnish WebSocket bridge. The legacy Gemini and redirected-stream terminal paths are untouched.
- [x] Added session-scoped launch credentials passed only through the Omnish process environment.
- [x] Added Omnish-side bridge hooks that open the first PTY, type the opaque command, and forward raw bytes. These live under `src/Omni/remote-terminal`; upstream files have only the tap and bootstrap hooks.
- [x] Made the PTY tap retain and replay the first active PTY when the backend bridge initializes. This removes the startup race where the terminal opened before the bridge subscribed.
- [x] Added `TestScripts/AIFeature/omnish_backend_roundtrip_test.py`: a local WebSocket backend that sends `echo <marker>` and verifies Omnish returns the resulting PTY output, with no Hub process involved.
- [x] Launch-mode behavior now starts the opaque terminal command as soon as the PTY is available. Backend connectivity no longer gates local command launch; this keeps the visible terminal usable during backend reconnects.
- [x] Fixed Omnish CSP to permit loopback WebSocket connections and added launch-chain tracing to the native Omnish log. The prior standalone test proved PTY creation but timed out before bridge registration because `ws://127.0.0.1:*` was not permitted.
- [x] Added a bounded 1 MiB pre-connection output buffer. Initial shell and command bytes are retained while the local backend socket connects, then flushed after registration instead of being lost.
- [x] Standalone Omnish backend round-trip passes: the local harness observed `OMNI_BACKEND_IO_ROUNDTRIP_20260815` from a command executed inside the visible Omnish PTY in 2.3 seconds, without Omni Hub.
- [x] Ran the same standalone transport with `agy` and required a reversed nonce absent from the submitted prompt, preventing a false pass from terminal input echo.
- [x] Reduced the launch proof to `echo hello`: a single configured Omnish instance executed it in the PTY and the standalone backend observed `hello` in 1.2 seconds.
- [x] Manual `agy` launch was confirmed healthy. Automated `agy` reached its initial cursor-position query but stalled because launch injection preceded a fully bound xterm renderer.
- [x] Gated automatic command injection on both PTY creation and renderer binding; `echo hello` still passes.
- [x] Diagnosed the remaining automated `agy` stall as a shell-initialization race: `Start-Sleep -Seconds 2; agy` launches successfully through the same backend path.
- [x] A JavaScript-side delay was rejected after testing: PowerShell remained blocked in its startup cursor query until input arrived.
- [x] Applied a provider-agnostic shell-level settle wrapper (`Start-Sleep ...; <opaque command>` on Windows) and verified that exact configured command `agy` completes a transformed-nonce round-trip.
- [x] Exact `agy` now visibly launches and receives backend input. Its account-verification startup phase outlasted the harness's original five-second input delay.
- [x] Printed sanitized PTY output during the Agy test and delayed automated input for 15 seconds so startup/account verification could finish before the prompt was sent.
- [x] Visually confirmed the complete standalone flow: Omnish launched exact command `agy`, backend input reached Agy, and Agy rendered the correctly reversed nonce.
- [x] Made the automated assertion search normalized terminal text rather than the raw ANSI byte stream.
- [x] Found why the instrumented backend missed a visibly successful Agy response: Windows CP1252 could not print Agy's Unicode logo/spinner and crashed the WebSocket handler. The harness now forces UTF-8 output.
- [x] Reran the exact `agy` transformed-nonce test with the corrected UTF-8 backend harness.
- [x] Captured the full Agy PTY stream, including its `Signing in...` account phase, submitted prompt, generation state, and correct reversed result `15628_pirtdnuor_inmo`.
- [x] Corrected the test oracle, which had transposed the reversed nonce digits (`15268` instead of `15628`).
- [x] Confirmed why a contiguous-text assertion still missed the visible answer: Agy paints `15628_pirtdn` and `uor_inmo` in separate cursor-addressed screen updates.
- [x] Recorded a clean standalone Agy pass in 18.5 seconds by requiring both transformed fragments, neither of which appears in the submitted prompt.
- [x] Ran the equivalent transformed-nonce test through Omni Hub successfully in 22.5 seconds.
- [x] First Hub attempt exposed a status-subscription race: Omnish could attach before the starting SignalR caller joined the session group, causing the client to miss `ready` despite a successful launch.
- [x] Rebuilt the Hub with current-status replay after start/subscribe and reran the Hub round-trip.
- [x] Status replay works. The next Hub run exposed the handshake failure: Omnish sends JSON `token`, but the C# record expected case-sensitive `Token`, producing a null token and closing the socket with code 1006.
- [x] Bound the registration field explicitly to JSON `token`, rejected malformed tokens without throwing, rebuilt, and reran successfully.

### First milestone result

The provider-agnostic round-trip is now proven end to end:

1. The test authenticates with `RemoteTerminalHub` and requests opaque command `agy` in `D:\SSDProjects`.
2. The Hub launches a visible Omnish process with session configuration in environment variables.
3. Omnish creates and renders its normal PTY, lets the shell settle, and runs `agy` without any Agy-specific launch code.
4. Omnish registers its loopback WebSocket with the Hub and reports `ready`.
5. The test sends a nonce-transformation prompt through SignalR as raw terminal input.
6. Agy signs in, receives the prompt, and renders the reversed nonce.
7. Raw PTY output travels Omnish -> Hub -> test; the test observes both transformed fragments and passes.

Verified on 2026-08-15 by:

```powershell
python TestScripts/AIFeature/omnish_backend_roundtrip_test.py --client agy
python TestScripts/AIFeature/remote_terminal_roundtrip_test.py
```

The standalone test passed in 18.5 seconds. The full Hub test passed in 22.5 seconds.
- [x] Added `TestScripts/AIFeature/remote_terminal_roundtrip_test.py`, which launches opaque command `agy`, sends a nonce-transformation prompt as raw terminal input, and requires transformed output absent from the submitted prompt.
- [x] Ran the first full Hub-mediated `agy` round-trip. The Hub and Omnish release builds both succeed.
- [ ] Replace Android's Gemini-specific session calls after the transport is proven.

The Hub must not know how Codex, Agy, Gemini, Claude, or another AI client works internally. It launches a command and relays terminal data.

## Confirmed decisions

- AI clients run locally and use their own installed authentication and configuration.
- Omni will not call model-provider APIs directly.
- No API-key management will be added to Omni for this feature.
- Omnish will open as a visible desktop window.
- The PC user and Android user will control and observe the same terminal session.
- The first automated success condition is based on recognizable terminal output, not a provider-specific `FINISHED` event.
- Either `agy` or `codex` may be used for the initial proof. The recommended order is `agy` first, followed by `codex` through the identical transport.
- Omnish changes must be isolated so rebasing the fork onto upstream remains easy.

## Non-goals for the first milestone

The first milestone will not attempt to:

- Parse AI responses into semantic message objects.
- Identify reasoning, tool calls, code diffs, or approval dialogs.
- Detect when an AI turn has semantically finished.
- Use Codex-, Agy-, or Gemini-specific protocols.
- Resume provider conversations through provider-specific session APIs.
- Recreate the complete old Gemini Android UI immediately.
- Render a full terminal emulator on Android immediately.
- Support multiple Omnish tabs inside one Omnish process immediately.
- Attach to arbitrary terminal processes that were not launched by the Hub.

Those may be added later without changing the raw terminal foundation.

## Core architectural rule

The boundary between Omni and a local AI client is a terminal, not an AI-provider API.

```text
Android / test
      |
      | authenticated SignalR commands and events
      v
Omni Hub
      |
      | local authenticated WebSocket bridge
      v
Visible Omnish window
      |
      | PTY input and output
      v
PowerShell -> agy, codex, or another command
```

Everything above the PTY treats the launched command as opaque text. Adding another client should normally require only a configured command name, not Hub code.

## Why Omnish is required

The existing Hub `TerminalService` redirects standard input, standard output, and standard error from a hidden process. That is not a real terminal. Interactive clients can depend on terminal dimensions, ANSI control sequences, cursor positioning, raw key input, and alternate-screen behavior.

Omnish already has the required terminal mechanics:

- A Windows ConPTY-backed pseudoterminal through `portable-pty`.
- Raw byte output rather than line-only output.
- Raw input writing.
- Terminal resizing.
- An xterm renderer.
- Process-tree cleanup through Windows Job Objects.
- Output buffering and backpressure handling.

The plan therefore routes through the actual Omnish PTY. It does not try to hook a separate visible `cmd.exe` window and it does not extend the Hub's redirected-stream `TerminalService` into a fake terminal.

## Ownership boundaries

### Android or automated client

The remote client owns:

- Requesting a remote terminal session.
- Selecting a configured command and workspace.
- Sending terminal input.
- Receiving terminal output.
- Requesting terminal resize once Android has a terminal renderer.
- Requesting session stop.

The remote client does not own the local process directly.

### Hub

The Hub owns:

- Authentication and authorization.
- Creating an opaque remote terminal session ID.
- Generating a short-lived Omnish host token.
- Launching the visible Omnish process.
- Matching one Omnish host connection to one remote session.
- Routing controller input to Omnish.
- Routing Omnish output to subscribed controllers.
- Tracking lifecycle and timeouts.
- Stopping only processes that belong to a known session.
- Logging enough state to diagnose launch and round-trip failures.

The Hub does not parse or understand the launched command's terminal output.

### Omnish

Omnish owns:

- Opening and displaying the real PTY.
- Sending local keyboard input to the PTY as it does today.
- Receiving remote input and writing it to the same PTY.
- Displaying PTY output locally as it does today.
- Mirroring the same raw PTY output to the Hub.
- Typing the initial requested command after the PTY opens.
- Reporting PTY and window lifecycle events.

Omnish does not need to know that a command is an AI client.

### Launched client

The launched process owns:

- Authentication.
- Provider selection.
- Model selection.
- Conversation persistence.
- Tool execution and approvals.
- Terminal UI behavior.

Omni merely provides the terminal and transport.

## Session model

The new feature must not use the operating-system PID as its public identity.

```text
RemoteTerminalSession
  Id                 opaque GUID
  Command            agy, codex, or another approved command
  Workspace          canonical absolute directory
  State              Launching, HostConnected, Ready, Running, Exited, Failed
  OmnishProcessId    optional diagnostic process ID
  PtyId              optional Omnish-local diagnostic ID
  CreatedAt          UTC timestamp
  LastActivityAt     UTC timestamp
  ExitCode           optional integer
  Failure            optional safe error description
```

PIDs may be recorded for diagnostics and cleanup, but Android and SignalR messages should use the opaque session ID.

For the first milestone, one Hub session launches one Omnish process and uses its initial terminal pane. This avoids tab selection and multi-pane routing ambiguity. Multi-tab hosting can be considered after the one-to-one lifecycle is reliable.

## Transport split

Two transports are used for different trust boundaries.

### Android/test to Hub

Use the existing authenticated SignalR connection. This preserves the established mobile networking, API-key authentication, reconnect handling, and remote access path.

Proposed Hub methods:

```text
StartRemoteTerminal(command, workspace, cols, rows) -> sessionId
SendRemoteTerminalInput(sessionId, dataBase64)
ResizeRemoteTerminal(sessionId, cols, rows)
StopRemoteTerminal(sessionId)
SubscribeRemoteTerminal(sessionId)
UnsubscribeRemoteTerminal(sessionId)
GetRemoteTerminalSessions()
```

Proposed Hub-to-client events:

```text
ReceiveRemoteTerminalCreated(sessionSummary)
ReceiveRemoteTerminalStatus(sessionId, state, detail)
ReceiveRemoteTerminalOutput(sessionId, sequence, dataBase64)
ReceiveRemoteTerminalExited(sessionId, exitCode)
ReceiveRemoteTerminalSessions(sessionSummaries)
```

Base64 is used at the SignalR boundary so arbitrary PTY bytes are preserved. Treating terminal data as UTF-8 strings would eventually corrupt incomplete multibyte characters or binary control data.

Each output frame receives a monotonically increasing sequence number. The first milestone may only log gaps; later Android reconnect support can use the sequence to request buffered output.

### Omnish to Hub

Use a small local WebSocket endpoint hosted by the Hub, separate from Android SignalR.

Suggested endpoint:

```text
ws://127.0.0.1:<hub-port>/internal/remote-terminal/<sessionId>
```

Reasons for a local WebSocket:

- Browser WebView code can use the built-in `WebSocket` API.
- Omnish does not need a new SignalR dependency.
- Binary terminal frames can be forwarded without JSON or base64 overhead.
- The endpoint can be restricted to loopback.
- One WebSocket maps to one terminal session, keeping framing simple.

Connection authentication:

- The Hub generates a cryptographically random, short-lived host token for each launch.
- The token is passed to the Omnish process through an environment variable, not a command-line argument.
- Omnish presents the token during the WebSocket handshake or in the first registration message.
- The token becomes invalid after the expected host registers successfully.
- The endpoint accepts only loopback connections.
- The Android API key is never passed to Omnish.

The host WebSocket uses:

- Binary Omnish-to-Hub frames for PTY output.
- Binary Hub-to-Omnish frames for PTY input.
- Small JSON text frames for `host-ready`, `pty-ready`, `resize`, `exit`, `error`, and heartbeat messages.

## Launch flow

### 1. Remote request

The client calls `StartRemoteTerminal` with:

```json
{
  "command": "agy",
  "workspace": "D:/SSDProjects",
  "cols": 120,
  "rows": 40
}
```

For the first milestone, `command` is a simple executable command with no arbitrary shell composition. Arguments can be introduced as a separate string array later.

### 2. Hub validation

The Hub:

1. Confirms the caller is authenticated.
2. Canonicalizes the workspace and confirms it exists.
3. Validates terminal dimensions against sane limits.
4. Validates the requested command against configured remote terminal profiles.
5. Creates the session ID and one-time host token.
6. Records the session as `Launching`.

Initially configured profiles should include:

```text
agy   -> agy
codex -> codex
shell -> no startup command
```

The profile is a safety boundary, not a provider adapter. The Hub still does not inspect client behavior or output.

### 3. Hub launches Omnish

The Hub starts the release or configured Omnish executable as a normal visible desktop process.

The workspace continues to be supplied through Omnish's existing launch-directory behavior. Remote bridge configuration is supplied through process environment variables such as:

```text
OMNI_REMOTE_TERMINAL_SESSION
OMNI_REMOTE_TERMINAL_TOKEN
OMNI_REMOTE_TERMINAL_HUB_URL
OMNI_REMOTE_TERMINAL_COMMAND
OMNI_REMOTE_TERMINAL_COLS
OMNI_REMOTE_TERMINAL_ROWS
```

The names are illustrative and should be finalized once in a shared protocol constants file.

The environment is inherited by the shell and child AI client. This is acceptable only because the host token is one-time, short-lived, loopback-only, and invalidated after registration. If later threat modeling rejects inheritance, the configuration should be moved to a restricted temporary launch descriptor that Omnish deletes immediately after reading.

### 4. Omnish starts normally

Omnish still renders its normal visible window and initial terminal pane. The remote feature activates only when the remote-launch configuration is present. A normal user launch follows the existing code path without opening a bridge connection.

### 5. Bridge binds to the PTY

When the initial PTY opens, the Omni-specific bridge:

1. Associates the first terminal leaf and PTY with the remote session.
2. Connects to the Hub's local WebSocket endpoint.
3. Reports `pty-ready` with the Omnish PTY ID and dimensions.
4. Writes the configured command followed by Enter into the PTY.
5. Begins mirroring every PTY output byte chunk to the Hub.

The original Omnish delivery path remains intact, so the same output is still written to the local xterm renderer.

### 6. Bidirectional control

Remote input follows:

```text
Android/test -> SignalR -> Hub session -> local WebSocket -> Omnish -> PtySession.write
```

Output follows:

```text
PTY -> Omnish xterm renderer
    -> Omni bridge -> local WebSocket -> Hub session -> SignalR -> Android/test
```

Local keyboard input continues to write directly to the same `PtySession`. Remote and local input are therefore equivalent from the launched client's perspective.

### 7. Shutdown

Any of these conditions ends the session:

- Android requests stop.
- The Omnish window closes.
- The PTY exits.
- The Omnish process exits.
- The host never connects before the launch timeout.
- The host connection is lost beyond a reconnect grace period.

The Hub broadcasts the final state and disposes its session record. Omnish's existing PTY Job Object remains responsible for killing its PTY child tree when the window or PTY is closed.

## Minimal Omnish changes

The fork must remain easy to rebase. Omni-specific behavior should be additive and concentrated in new files.

### New isolated frontend files

Recommended location:

```text
src/Omni/remote-terminal/
  bridge.ts
  launchConfig.ts
  protocol.ts
  ptyTap.ts
```

Responsibilities:

- Read remote launch configuration through one Tauri command.
- Own the local WebSocket connection.
- Buffer input until the PTY exists.
- Bind exactly one PTY to the remote launch session.
- Forward raw PTY byte chunks.
- Write the initial command once.
- Forward resize, input, and stop instructions.
- Report lifecycle and errors.

### New isolated Rust file

Recommended location:

```text
src-tauri/src/omni/remote_launch.rs
```

Responsibilities:

- Read and validate the remote-launch environment variables once.
- Expose a serializable launch configuration through a Tauri command.
- Drain secrets after the first successful read where practical.
- Avoid changing normal Omnish launch behavior.

### Upstream-owned touchpoints

Keep changes to existing upstream files limited to small hooks:

1. `src-tauri/src/lib.rs`
   - Add the new Omni module.
   - Register the one launch-config Tauri command.

2. `src/modules/terminal/lib/useTerminalSession.ts`
   - Notify the isolated tap when a PTY opens.
   - Copy each PTY output chunk to the isolated tap after or before normal local rendering.
   - Notify the tap when the PTY exits.

3. Application bootstrap, only if required
   - Import and initialize the remote bridge once.

The PTY implementation, renderer pool, tab model, shell integration, and existing terminal components should not be refactored for this feature.

### Hook shape

The upstream terminal hook should remain generic and inert when no listener is registered. Conceptually:

```typescript
terminalTap.onOpened(leafId, pty);
terminalTap.onData(leafId, bytes);
terminalTap.onExit(leafId, code);
```

All remote behavior stays behind `terminalTap`. The existing terminal code should not import SignalR, Hub models, remote-session state, or AI-client names.

This structure makes rebasing straightforward: if upstream changes terminal internals, only three small hook calls need to be relocated.

## Hub implementation structure

Recommended additions:

```text
OmniSync.Hub/src/OmniSync.Hub/
  Logic/RemoteTerminal/
    RemoteTerminalSession.cs
    RemoteTerminalSessionManager.cs
    RemoteTerminalProfile.cs
    RemoteTerminalProtocol.cs
  Infrastructure/RemoteTerminal/
    OmnishLauncher.cs
    RemoteTerminalHostEndpoint.cs
  Presentation/Hubs/
    RemoteTerminalHub.cs
```

### `RemoteTerminalSessionManager`

The manager is the single source of truth. It should:

- Use a concurrent session dictionary keyed by GUID.
- Serialize state transitions per session.
- Associate one host WebSocket with each session.
- Associate zero or more authenticated controller connections with each session.
- Buffer a small amount of output for late subscribers.
- Buffer remote input only while Omnish is launching, with a strict size limit.
- Assign output sequence numbers.
- Enforce launch and inactivity timeouts.
- Ensure stop is idempotent.
- Never kill a process that is not recorded as owned by the session.

### `OmnishLauncher`

The launcher should:

- Resolve the Omnish executable from Hub settings, not a hardcoded development path.
- Validate the executable exists before creating a session.
- Launch without elevation.
- Set the requested workspace.
- Set the remote-launch environment variables.
- Capture the Omnish process ID for lifecycle tracking.
- Not redirect Omnish standard input or output.
- Not use shell string concatenation to construct the startup command.

### `RemoteTerminalHub`

The SignalR hub should:

- Require the existing Omni authentication handshake.
- Validate session access on every operation.
- Add clients to a session-specific SignalR group.
- Broadcast output only to that group, not `Clients.All`.
- Apply input and resize rate limits.
- Limit maximum frame size.
- Use the session manager rather than owning process state itself.

## Android implementation plan

The first automated milestone does not require Android UI changes, but the protocol should be designed for the eventual application path.

### Stage A: transport support

Add a remote-terminal repository/client that can:

- Start a session.
- Subscribe to its output.
- Send UTF-8 text and raw key sequences.
- Stop a session.
- Track state independently from legacy Gemini sessions.

Do not initially merge it into the existing PID-indexed `AiMessage` maps. The new session uses a GUID and raw terminal frames, so forcing it into those maps would prolong the legacy architecture.

### Stage B: minimal raw output screen

For manual validation, a simple screen may:

- Decode output bytes incrementally as UTF-8.
- Display a bounded scrollback log.
- Provide a text input and Send button.
- Provide Enter, Escape, Ctrl+C, arrow, and Tab keys.

This will not display all TUI behavior correctly, but it is sufficient to verify command and response flow after the Python test passes.

### Stage C: actual terminal renderer

For production use with interactive AI TUIs, Android needs a VT/xterm-compatible terminal emulator component. It must process:

- ANSI/CSI escape sequences.
- Cursor movement and screen erasure.
- Alternate-screen buffers.
- Terminal resize.
- Bracketed paste if supported.
- Raw control keys.
- Incremental UTF-8 decoding.

The Hub should not strip ANSI output or convert the stream into chat bubbles. Doing so would destroy the exact terminal semantics this design is intended to preserve.

## First round-trip test

Create a new test instead of rewriting the legacy Gemini test in place:

```text
TestScripts/AIFeature/omnish_cli_roundtrip_test.py
```

Keeping `roundtrip_test.py` intact preserves historical evidence until the Gemini implementation is deliberately removed.

### Test arguments

Recommended interface:

```text
python TestScripts/AIFeature/omnish_cli_roundtrip_test.py \
  --command agy \
  --workspace D:/SSDProjects \
  --timeout 180
```

The same test must later pass with:

```text
--command codex
```

The test contains no command-specific parsing.

### Test sequence

1. Verify the Hub port is reachable.
2. Connect to the regular SignalR hub.
3. Authenticate.
4. Register terminal-created, status, output, and exited handlers.
5. Request a remote terminal session with the selected command and workspace.
6. Wait for `Ready`.
7. Generate a random lowercase nonce.
8. Send a prompt that asks the client to transform the nonce to uppercase and output it.
9. Incrementally decode output and remove ANSI sequences only in the test's matching copy.
10. Pass when the expected uppercase nonce appears before timeout.
11. Request session stop.
12. Confirm an exit or stopped status.

The expected response must not appear literally in the sent prompt. Otherwise local terminal echo could make the test pass before the AI client responds.

Example:

```text
Sent:     Convert omni_roundtrip_ab12cd to uppercase and print only the result.
Expected: OMNI_ROUNDTRIP_AB12CD
```

### Test prerequisites

- Omnish release executable is built and configured in Hub settings.
- The chosen AI client is installed and resolvable inside Omnish's shell.
- The chosen client has already completed its local login/setup flow.
- The workspace is authorized by Omnish.
- No first-run trust or update dialog blocks terminal input.

If the output shows a login, trust, or update prompt, the test should fail with the captured terminal tail and a clear prerequisite message rather than attempting provider-specific automation.

### Test evidence

On failure, report:

- Remote session ID.
- Omnish PID if assigned.
- Last known state.
- Whether the host WebSocket connected.
- Whether the PTY reported ready.
- Number of input and output bytes.
- Last bounded terminal-output tail with control sequences escaped.
- Hub logs filtered to the session ID.

Do not write unbounded terminal dumps or leave temporary logs staged for commit.

## Delivery phases

### Phase 1: protocol and test skeleton

Deliverables:

- Shared protocol names and DTOs.
- `RemoteTerminalSessionManager` state machine tests.
- Host token validation tests.
- New Python test with event handlers and clear expected behavior.
- No Omnish launch yet; use a fake host connection for Hub contract tests.

Verification:

- An authenticated test can create a session.
- An unauthenticated test cannot create, subscribe, send, resize, or stop.
- A fake host can register once and echo bytes through the Hub.
- Output is isolated by session group.

### Phase 2: visible Omnish launch and PTY tap

Deliverables:

- `OmnishLauncher`.
- One-time local host endpoint.
- Isolated Omnish remote-launch module.
- Minimal PTY open/data/exit hooks.
- Initial command injection.

Verification:

- Starting `shell` opens a visible Omnish window at the requested workspace.
- Text sent by the test appears in the visible terminal.
- Commands typed locally are visible in forwarded output.
- Closing the Omnish window ends the Hub session.

### Phase 3: Agy round trip

Deliverables:

- Configure the `agy` launch profile.
- Run the nonce transformation test through the complete stack.
- Add lifecycle logging required by any failures.

Verification:

- `omnish_cli_roundtrip_test.py --command agy` passes repeatedly.
- No Agy-specific parser or Hub branch exists.

### Phase 4: Codex round trip

Deliverables:

- Configure the `codex` launch profile.
- Exercise the same test unchanged except for `--command codex`.
- Fix only general terminal transport defects revealed by Codex's TUI.

Verification:

- `omnish_cli_roundtrip_test.py --command codex` passes.
- No Codex protocol, JSON event parser, app-server integration, or direct provider API exists in the Hub.

### Phase 5: Android minimal control screen

Deliverables:

- Start-session command selection.
- Remote output display.
- Text and essential special-key input.
- Stop and reconnect behavior.

Verification:

- A session can be started from Android.
- The Omnish window is visible on the PC.
- Android and the PC can both type into and observe the same client.

### Phase 6: remove dead Gemini implementation

Begin only after the replacement round trip is stable.

Remove or replace:

- `gmi.exe` and `gemini.js` launch logic.
- Gemini WMI process discovery.
- `omni-gemini-cli-{pid}` pipe connections.
- Gemini response, dialog, thought, diff, and history parsing.
- Marker-based history extraction and repeated JSON unescaping.
- `KillAllGeminiProcesses` behavior.
- Gemini-specific model defaults and debug log cleanup.
- Gemini-specific test fixtures that have no historical value.

Preserve until their replacement is explicit:

- Tell PC entry points.
- Jarvis/quick-action workflows.
- Workspace selection.
- Android voice input.
- Session naming and lists.
- Notifications.

These features should be redirected to remote terminal profiles rather than deleted merely because their original backend died.

## Rebase strategy for Omnish

The remote bridge is fork-specific and should not be mixed into upstream terminal abstractions.

Rules:

1. New behavior lives under `src/Omni` and `src-tauri/src/omni`.
2. Existing upstream files receive only import, registration, and tap calls.
3. No upstream component is renamed or moved.
4. No general terminal refactor is bundled into this work.
5. The PTY API remains unchanged unless a minimal generic hook is unavoidable.
6. Omni hooks are no-ops during ordinary launches.
7. Each upstream touchpoint gets a focused test or compile-time contract.
8. Maintain a short `UPSTREAM_INTEGRATION.md` inside the isolated bridge directory listing every upstream-owned line that must exist after a rebase.

Before rebasing Omnish upstream:

1. Record the small set of hook locations.
2. Rebase upstream normally.
3. Resolve upstream terminal changes without pulling Omni networking into terminal code.
4. Reapply or relocate the hooks.
5. Run normal Omnish terminal tests first.
6. Run the fake-host bridge test.
7. Run the Agy and Codex round-trip tests.

The target is that upstream conflicts are limited to a few obvious hook lines even if Omnish's internal terminal implementation changes substantially.

## Security requirements

Although this is not a provider API integration, it provides remote command input and therefore has a large security impact.

- Require existing Hub authentication before every controller operation.
- Restrict the Omnish host WebSocket to loopback.
- Use a unique one-time host token per launch.
- Do not put persistent API keys or Hub credentials in command-line arguments.
- Use configured command profiles rather than arbitrary shell strings initially.
- Canonicalize and authorize workspaces.
- Limit input frame size and rate.
- Limit buffered input and output.
- Do not broadcast terminal output through `Clients.All`.
- Do not log raw terminal contents by default because they may contain secrets.
- Stop only the Omnish process tree recorded for that session.
- Make stop idempotent and resilient to already-exited processes.
- Do not auto-answer terminal prompts in the generic transport.

## Failure handling

Required terminal states and expected behavior:

### Omnish executable missing

Fail before session creation completes and return a configuration error containing the resolved executable path.

### Omnish starts but host does not connect

After the launch timeout, mark the session failed, close the owned Omnish process if it is still running, and include the session ID in logs.

### Host connects but PTY does not open

Mark the session failed with the safe error reported by Omnish. Do not inject the startup command.

### Input arrives before PTY ready

Queue a bounded amount per session and flush it after `pty-ready`. Reject further input after the bound is reached.

### WebSocket temporarily disconnects

For milestone one, mark the session disconnected and fail after a short grace period. Do not silently create another PTY. Reattachment can be added later using the same session ID and token rotation.

### Output consumer is slow

Use bounded queues. Preserve the local Omnish renderer even if remote output must be dropped. Notify the remote client of a sequence gap or overflow rather than blocking the PTY reader.

### AI client exits back to the shell

The PTY remains alive because the shell remains alive. The generic bridge cannot infer that the AI client ended. The session continues until the user stops it or closes Omnish. Later shell integration may report foreground-command boundaries without becoming client-specific.

### Omnish closes

The Job Object terminates the PTY child tree. The Hub marks the session exited and notifies controllers.

## Observability

Every lifecycle log should contain the remote session ID. Recommended events:

```text
SessionRequested
WorkspaceValidated
OmnishLaunchStarted
OmnishProcessStarted
HostConnected
PtyReady
StartupCommandSent
InputForwarded
OutputForwarded
OutputDropped
HostDisconnected
StopRequested
PtyExited
OmnishExited
SessionFailed
SessionClosed
```

Input/output logs should record byte counts, not content, unless an explicit debug mode is enabled. Debug content must be bounded and control characters must be escaped.

## Acceptance criteria for the first goal

The first goal is complete when all of the following are true:

- The Hub can launch a visible Omnish window in a requested workspace.
- Omnish opens a real PTY-backed terminal.
- Omnish runs the requested opaque command inside that terminal.
- The Hub contains no branch that recognizes `agy` or `codex` output.
- The PC user can see and interact with the terminal normally.
- The automated client can send terminal input through the Hub.
- The automated client receives the same PTY output that Omnish renders.
- The nonce round-trip test passes with `agy`.
- The same test passes with `codex` by changing only the command argument.
- Closing or stopping the session cleans up the owned process tree.
- Normal Omnish launches behave exactly as before.
- Omnish-specific changes are isolated with only minimal hooks in upstream-owned files.

## Deferred capabilities

Once the raw terminal path is stable, later work may add provider-neutral terminal conveniences:

- Terminal screen snapshots for reconnect.
- Full Android VT rendering.
- Clipboard paste and file upload.
- Multiple Omnish tabs per process.
- Session persistence and reattachment.
- Shell prompt and foreground-command state through OSC integration.
- Configurable startup command arguments and environment profiles.
- Optional command-specific helpers outside the Hub core.

Any future semantic AI integration must remain optional. The raw terminal transport must continue to work for unknown clients without modification.
