# Remote terminal bridge

The Android terminal is an interactive xterm WebView backed by Omnish's real PTY. It transports raw terminal bytes, so ANSI color, cursor movement, alternate screens, and terminal applications remain intact.

```text
Android xterm input → SignalR RemoteTerminalHub → Hub WebSocket → Omnish PTY
Omnish PTY output → Hub WebSocket → SignalR RemoteTerminalOutput → Android xterm
```

The Hub starts Omnish with a session id and one-time token. Omnish connects only to the Hub loopback WebSocket and sends that token before any terminal data. The Hub accepts a single open Omnish socket per session; stale sockets are detached so Omnish can reconnect.

Android's local terminal page is the only page allowed in its WebView. JavaScript forwards xterm `onData` bytes through the `OmniTerminalInput` bridge; file and content access are disabled, and its content-security policy permits only local terminal assets.

For local renderer diagnosis, `terminal_output_prototype.html` can render a raw `.ansi` capture. Omnish also supports `OMNI_REMOTE_TERMINAL_DUMP_PATH` to persist its PTY output to a chosen file for a remote-terminal launch.
