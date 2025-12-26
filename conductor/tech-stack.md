# Technology Stack: OmniSync

## Overview
OmniSync is a multi-platform ecosystem utilizing native technologies for maximum system integration and performance.

## Core Components

### 1. Windows Hub (Server & Desktop Client)
-   **Runtime:** .NET 9.0 (C#)
-   **Framework:** Windows Presentation Foundation (WPF) for the Monitoring UI.
-   **Networking:** ASP.NET Core SignalR for real-time bi-directional communication.
-   **System Integration:** P/Invoke (`user32.dll`, `ole32.dll`) for low-level input injection and clipboard management.
-   **Architecture:** Modular Monolith using Dependency Injection (Microsoft.Extensions.DependencyInjection).

### 2. Android Client (Remote & Sensor)
-   **Language:** Kotlin (Native)
-   **UI Framework:** Jetpack Compose (Material Design 3)
-   **Networking:** SignalR Client for Android.
-   **System Integration:**
    -   `AccessibilityService` for input injection and macro triggers.
    -   `ForegroundService` for persistent connection.
    -   `WorkManager` for background tasks.
-   **Build System:** Gradle (Headless/CLI support).

### 3. Chrome Extension (Browser Integration)
-   **Language:** JavaScript / HTML / CSS
-   **Manifest Version:** V3
-   **Networking:** SignalR JavaScript Client.

### 4. Infrastructure & Communication
-   **Protocol:** SignalR (WebSockets with Long Polling fallback).
-   **Transport Security:** Tailscale VPN Mesh (WireGuard) - No public internet exposure.
-   **Serialization:** JSON / Protocol Buffers.

## Extensibility
The system is designed to be extensible. New technology stacks and languages (e.g., Python for scripting, Go for specific micro-utilities) can be integrated as needed to support new features or "skills."
