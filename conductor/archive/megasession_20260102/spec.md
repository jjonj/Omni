# Specification: Megasession - Android UI, Macros, and File Sync

## 1. Overview
This track focuses on a "Megasession" of tasks primarily targeting the `OmniSync.Android` application, with a secondary focus on general improvements and bug fixes defined in `Tasks.txt`. The immediate priority is enhancing the Android UI and functionality, specifically introducing a Macro system and improving offline file handling.

## 2. Functional Requirements

### 2.1 Remote Control - Macro System (Priority 1)
-   **Third Panel:** Add a third button panel to the Remote Control screen, accessible via a new button.
-   **Macro Grid:** The panel must contain a grid of macro buttons.
-   **Reordering:** Users must be able to reorder macro buttons via long-press and drag.
-   **Macro Manager:** Create a new screen to manage macros.
    -   **Syntax:** Implement an AHK-like syntax parser/executor for defining macro actions (e.g., key input, delays, window management).
    -   **Quick Actions:** Support predefined actions like opening folders, starting CLI AI in specific workspaces.
    -   **Dropdown:** Macros should be selectable from a dropdown for easy assignment.
    -   **Widget/Notification:** Macros must be exposed for use in home screen widgets and notifications.

### 2.2 Files & Video - Offline Capabilities (Priority 2)
-   **Offline Creation:** The "+" button must allow creating files (text/folder) in offline mode. These files should be cached locally and synced upon reconnection.
-   **Logging:** Add detailed logging to the activity log for all cache-related operations.
-   **Recursive Cache:** Implement "Cache Folder" functionality:
    -   Recursive caching with a max file size limit.
    -   Files exceeding the limit are replaced with placeholders (`myfile.mp4.fake`).
    -   UI should not list every sub-item of a cached folder; show the root folder in bold.
    -   **Settings:** Add "Max File Size" setting for caching.
    -   **Exclusions:** Add settings to exclude file/folder patterns (e.g., "G:/*") from automatic caching.

### 2.3 Files & Video - Conflict Resolution
-   **Merge Strategy:** When syncing offline edits with a newer server version:
    -   Attempt a line-by-line merge.
    -   Keep both versions of conflicting lines.
    -   Add `[MERGED]` tag to the content/filename to indicate a merge occurred.
    -   Fallback to `conflicted` suffix if merge ratio is poor.

### 2.4 General & Connectivity
-   **Wake on LAN:** Implement remote Wake on LAN for non-local networks (requires router config guidance).
-   **Reconnect:** Ensure auto-reconnect logic retries correctly when the app is reopened.

## 3. Non-Functional Requirements
-   **Performance:** Macro execution should be low-latency.
-   **Safety:** File operations (sync/merge) must never result in data loss; always prefer duplication/tagging over overwriting.
-   **UI/UX:** Adhere to existing OmniSync design patterns (Material/Bootstrap).

## 4. Out of Scope
-   Detailed AHK syntax parity (focus on core subset).
-   Advanced video streaming features (pinching/zooming).
