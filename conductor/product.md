# Initial Concept
OmniSync is a local-first, high-privilege personal ecosystem designed to integrate a Windows PC, an Android Device, and a Web Browser. It functions as a private "nervous system" for the user's devices, enabling real-time automation, clipboard synchronization, file access, and remote input control.

# Product Guide: OmniSync

## User Persona
The primary user is the developer/creator themselves—a power user who demands:
- **Zero Friction:** Instantaneous action execution.
- **Total Control:** Low-level system access (Accessibility Services, Win32 Hooks).
- **Privacy:** Local-first architecture over a private mesh VPN (Tailscale).

## Goals
1.  **Unified Ecosystem:** Dissolve the barriers between the phone and the PC. Copy on one, paste on the other. Type on the phone, see text on the PC.
2.  **Remote Command:** Provide a robust interface on Android to control the Windows PC (mouse, keyboard, media, app launching).
3.  **Extensibility:** A modular architecture designed for rapid addition of new "skills" and automation scripts.

## Core Features
### 1. Synchronization
-   **Clipboard Sync:** Bi-directional text and image synchronization between Windows and Android.
-   **File Access:** Remote browsing and editing of Markdown notes (e.g., Obsidian vault) with offline caching, recursive folder synchronization, and intelligent line-by-line conflict resolution.

### 2. Remote Control
-   **Input Injection:** Use the Android device as a trackpad and keyboard for the PC.
-   **System Control:** Managing volume, media playback, and process execution from the phone.
-   **Custom Macros:** A dedicated Macro system using AHK-like syntax to automate complex PC tasks from a customizable grid on the phone.

### 3. Automation & Monitoring
-   **Macro Triggers:** Hardware button mapping (e.g., volume keys) on Android to trigger PC scripts.
-   **System Health:** Real-time monitoring of PC status (active window, processes) from the mobile dashboard.
-   **Startup Routines:** A "QuickActionService" framework that executes once-per-day maintenance tasks (e.g., browser tab cleanup based on title patterns) upon Hub initialization.
-   **Intelligent Alarms:** Advanced alarm system with gradual wake, macro integration, and custom dismiss messages.

### 4. AI Integration
-   **Mobile AI Interface:** A dedicated chat interface on Android for interacting with local LLMs (via Gemini CLI).
-   **Interleaved Diffs:** Readable, line-by-line file modification previews (+/-) using a greedy matching algorithm for code changes.
-   **Remote Execution:** Triggering Hub commands and file modifications through natural language on the phone.

### 4. Creative Tools & Submodules
-   **Procedural Generation:** Integration of standalone tools like the Island Generator for rapid creative prototyping and world-building.
