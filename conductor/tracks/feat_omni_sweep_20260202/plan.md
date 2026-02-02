# Implementation Plan: Omni Sweep

## Phase 1: Shared Macro Logic & Foundation
- [x] Task: Refactor existing Macro execution into a shared `IMacroService`
    - [x] Create `IMacroService` and `MacroService` in Logic layer
    - [x] Move `ProcessService.ExecuteMacro` logic into the new service
    - [x] Update `RpcApiHub` to use the new `IMacroService`
- [x] Task: Implement Macro Storage in `HubSettingsService`
    - [x] Add `List<MacroConfig>` to `HubSettings`
    - [x] Implement CRUD operations for macros in `HubSettingsService`
- [x] Task: Conductor - User Manual Verification 'Phase 1: Foundation' (Protocol in workflow.md) [checkpoint: Phase1]

## Phase 2: Hub Calendar Service
- [x] Task: Implement `CalendarService` to fetch and parse ICS
    - [x] Create `CalendarService` using `HttpClient` to fetch the ICS URL
    - [x] Implement a basic ICS parser to extract today's events
    - [x] Add a background timer to refresh the calendar every 15 minutes
- [x] Task: Write tests for `CalendarService` parsing logic
- [x] Task: Conductor - User Manual Verification 'Phase 2: Calendar' (Protocol in workflow.md) [checkpoint: Phase2]

## Phase 3: Search Engine & Settings UI
- [x] Task: Implement `ProjectSearchService`
    - [x] Add `ProjectRoots` list to `HubSettings`
    - [x] Implement recursive directory search with filtering for `.git` and common folders
    - [x] Add "Recent Workspaces" tracking logic
- [x] Task: Update Hub Settings UI (WPF)
    - [x] Add a "Search Roots" management section
    - [x] Add a "Macros" management tab (matching Android features)
- [x] Task: Conductor - User Manual Verification 'Phase 3: Search & Settings' (Protocol in workflow.md) [checkpoint: Phase3]

## Phase 4: Omni Sweep UI Implementation
- [x] Task: Create `OmniSweepWindow` and `OmniSweepViewModel`
    - [x] Rename/Refactor `ProjectSelectorWindow` to `OmniSweepWindow`
    - [x] Implement the categorized layout (Schedule, Macros, Workspaces, Search)
    - [x] Bind search input to `ProjectSearchService`
- [x] Task: Implement Keyboard Navigation & Execution
    - [x] Add support for Arrow keys and Enter to execute selected items (Launch CLI, Run Macro)
- [x] Task: Conductor - User Manual Verification 'Phase 4: Omni Sweep UI' (Protocol in workflow.md) [checkpoint: Phase4]

## Phase 5: Final Polish & Integration
- [x] Task: Sync Android Macro management with Hub Macro storage
    - [x] Ensure macros created on Hub are visible/editable on Android via SignalR
    - [x] Update Android `MainViewModel` to bidirectional sync with Hub
    - [x] Add "Refresh" button to Android Macro Manager
- [x] Task: Calendar Polish
    - [x] Implement basic RRULE parsing for recurring events
    - [x] Add "Refresh Calendar" to Hub tray icon
- [x] Task: Final end-to-end verification of all Omni Sweep features
- [x] Task: Conductor - User Manual Verification 'Phase 5: Final Polish' (Protocol in workflow.md) [checkpoint: Phase5]
