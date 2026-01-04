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
