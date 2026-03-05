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
-   **Omni Sweep (Central Command Palette):** A unified Windows command palette (Ctrl+Alt+P) for instant access to recent workspaces, pinned macros, and a high-performance smart file search.
-   **Calendar Integration:** Real-time synchronization with Google Calendar to display the next scheduled event within the Hub and command palette.
-   **Macro Triggers:** Hardware button mapping (e.g., volume keys) on Android to trigger PC scripts.
-   **System Health:** Real-time monitoring of PC status (active window, processes) from the mobile dashboard.
-   **Startup Routines:** A "QuickActionService" framework that executes once-per-day maintenance tasks (e.g., browser tab cleanup based on title patterns) upon Hub initialization.
-   **Intelligent Alarms:** Advanced alarm system with gradual wake, macro integration, and custom dismiss messages.

### 4. Books & Media
-   **Persistent Library:** Seamless access to the PC books repository (`B:\GDrive\Books`) with category-based filtering (Audiobooks/Ebooks).
-   **Offline Mode:** Download books and audiobooks to the Android device for offline access.
-   **Progress Synchronization:** Bi-directional progress tracking (page/timestamp) synced between the Hub and mobile client.
-   **Rich Visuals:** Automatic cover extraction from metadata and folder assets for a polished library view.
-   **Wishlist Integration:** Quick-add titles to a persistent wishlist directly from the mobile search interface.
-   **Dynamic Content Discovery:** Automated thumbnail generation for PDF/EPUB files missing embedded covers.

### 4. AI Integration
-   **Hybrid AI Ecosystem:** Interaction with local LLMs via Gemini CLI, with deep system integration through custom extensions.
-   **OmniProjectContext (OPC):** High-performance C# tool providing dense, token-efficient codebase context to AI via lifecycle hooks.
-   **Interleaved Diffs:** Readable, line-by-line file modification previews (+/-) using a greedy matching algorithm for code changes.
-   **Remote Execution:** Triggering Hub commands and file modifications through natural language on the phone.
-   **Cross-Session Intelligence:** Capabilities for the AI to list, interact with, and retrieve history from other active CLI sessions.
-   **Intelligent Resource Opener:** AI-driven capability to open files (with line-specific targeting), folders, and URLs using host-defined application mappings and automatic window focusing.

### 4. Creative Tools & Submodules
-   **Procedural Generation:** Integration of standalone tools like the Island Generator for rapid creative prototyping and world-building.
