# Omni connection configuration (Rust Hub)

**Current owner:** Rust OmniHub, in `D:\SSDProjects\Tools\Omnish\main`
(not the retired C# `OmniSync.Hub` project).

- Local HTTP API / SignalR: `http://127.0.0.1:5000/` and `/signalrhub`.
- LAN SignalR: `http://192.168.1.37:5000/signalrhub` if this PC still uses that IP.
- Gateway: `http://127.0.0.1:3333/gateway/`.
- Credentials: `%LOCALAPPDATA%\OmniHub\config\appsettings.json`;
  never embed the key in documentation, scripts or Git.
- Android configured endpoints are managed by `OmniSync.Android` settings.
- The PC should run `OmniHub Rust` scheduled task, not `OmniSync Hub`.
- For troubleshooting inspect active process and port ownership, then
  `docs/SERVICE_RECOVERY.md`. Browser extensions and CLI use port 5000.

To change Rust Hub source, use the separate Rust repository and its build
instructions. `build_run_omnihub.py` in this checkout is a non-deploying helper.
