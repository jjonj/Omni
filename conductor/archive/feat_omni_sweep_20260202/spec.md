# Specification: Omni Sweep (Central Command Palette)

## 1. Overview
Omni Sweep is an evolution of the "Project Selector" (Ctrl+Alt+P). It serves as a unified command palette for the OmniSync ecosystem, providing instant access to calendar events, recent workspaces, pinned macros, and a high-performance file search across configured project roots.

## 2. Functional Requirements

### 2.1 Unified Command Palette (UI)
- **Categorized Sections:**
  - **Schedule:** Displays the "Next Event" fetched from Google Calendar.
  - **Recent Workspaces:** A list of recently opened projects with an option to open a CLI at that location.
  - **Pinned Macros:** Quick-access buttons for macros defined in the system.
  - **Search Results:** Real-time file and workspace search.
- **Visuals:** Modern, minimalist WPF window with a central search bar and keyboard-friendly navigation (Arrow keys to select, Enter to execute).

### 2.2 Calendar Integration (C#)
- Implement a background service in the Hub to fetch and parse the Google Calendar ICS file directly (using the same URL as Cortex Web: `jjonjex@gmail.com` public ical).
- Provide the "Next Event" to the UI.

### 2.3 Smart File & Workspace Search
- **Configurable Roots:** Users can manage project root directories (e.g., `D:/SSDProjects`) via the Hub Settings UI.
- **Search Logic:** Recursive search within these roots, prioritizing folder names and `.git` repositories.
- **CLI Integration:** Selecting a workspace should offer to open it in the default terminal/CLI.

### 2.4 Macro System Expansion
- **Common Logic:** Create a shared logic layer for macros that both the Android SignalR handler and the Hub UI can use.
- **Bidirectional Sync:**
    - Android app fetches the full list of macros from the Hub upon connection.
    - Saving or deleting a macro on Android synchronizes the change to the Hub's persistent storage.
    - Hub-only actions (e.g., `powershell`, `winactivate`) are preserved but gracefully skipped if run on Android.
- **Hub Macro Editor:** A new UI section in the Hub window to create, edit, and delete macros, matching the capabilities of the Android app.
- **Pinning:** Allow users to mark specific macros as "Pinned" for visibility in Omni Sweep.

### 2.5 Hub Settings Update
- Add a new tab or section in Hub Settings to:
  - Manage "Project Root Paths" for search.
  - Configure the Google Calendar ICS URL.
  - Manage Pinned Macros.

## 3. Acceptance Criteria
1. `Ctrl+Alt+P` triggers the Omni Sweep window.
2. The window displays the next calendar event accurately.
3. Users can search for and find files/folders within the configured roots.
4. Pinned macros are visible and executable from the UI.
5. New macros can be created and saved directly from the Hub's desktop interface.

## 4. Out of Scope
- Full-text search inside files (path/name search only).
- Deep integration with outlook/other calendar providers (ICS only for now).
