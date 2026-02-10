# Technology Stack: OmniSync

## Overview
OmniSync is a multi-platform ecosystem utilizing native technologies for maximum system integration and performance.

## Core Components

### 1. Windows Hub (Server & Desktop Client)
-   **Runtime:** .NET 9.0 (C#)
-   **Framework:** Windows Presentation Foundation (WPF) for the Monitoring UI.
-   **Networking:** ASP.NET Core SignalR for real-time bi-directional communication.
-   **System Integration:** P/Invoke (`user32.dll`, `ole32.dll`) for low-level input injection and clipboard management.
-   **Instance Management:** Global Mutex (`Global\OmniSyncHubSingleInstance`) for single-instance enforcement with SignalR-based cross-process window restoration.
-   **Architecture:** Modular Monolith using Dependency Injection (Microsoft.Extensions.DependencyInjection) and a daily "QuickAction" routine framework.
-   **Omni Sweep Logic:**
    -   `IMacroService`: Shared execution engine for cross-platform automation scripts.
    -   `CalendarService`: Background ICS synchronization and parsing.
    -   `ProjectSearchService`: High-performance filesystem crawler for workspace discovery.

### 2. Android Client (Remote & Sensor)
-   **Language:** Kotlin (Native)
-   **UI Framework:** Jetpack Compose (Material Design 3) with custom `touchSlop` sensitivity for high-stability navigation.
-   **Networking:** SignalR Client for Android.
-   **AI Logic:**
    -   `InterleavedDiffMatcher`: A greedy matcher implementation for rendering human-readable code diffs in chat bubbles.
-   **System Integration:**
    -   `AccessibilityService` for input injection and macro triggers.
    -   `ForegroundService` for persistent connection.
    -   `WorkManager` for background tasks.
    -   **Logic Components:**
        -   `MacroParser` & `MacroExecutor`: Custom parser for AHK-like automation scripts.
        -   `ConflictResolver`: Line-by-line merge engine for offline/online sync.
-   **Build System:** Gradle (Headless/CLI support).

### 3. Chrome Extension (Browser Integration)
-   **Language:** JavaScript / HTML / CSS
-   -   **Manifest Version:** V3
-   -   **Networking:** SignalR JavaScript Client.

### 4. Web Utilities & Submodules
-   **Island Generator:** A standalone procedural generation tool integrated as a git submodule.
    -   **Tech:** Vanilla JavaScript, HTML5 Canvas, Hydrology-based heightmap generation.
    -   **Path:** `OmniSync.Web/www/IslandGenerator`

### 5. Infrastructure & Communication
-   **Protocols:** 
    - SignalR (WebSockets) for real-time bi-directional events.
    - REST API (OmniHubAPI) for authenticated external command execution and session management.
-   **Transport Security:** Tailscale VPN Mesh (WireGuard) - No public internet exposure.
-   **Serialization:** JSON / Protocol Buffers.

### 6. AI & Extension Framework
-   **Gemini CLI Extensions:** Support for Model Context Protocol (MCP) compatible extensions (e.g., `omni` extension) for host-AI interoperability.
-   **OmniProjectContext (OPC):** High-performance .NET 9.0 (C#) CLI tool for token-efficient codebase indexing and context injection.

## Extensibility
The system is designed to be extensible. New technology stacks and languages (e.g., Python for scripting, Go for specific micro-utilities) can be integrated as needed to support new features or "skills."
