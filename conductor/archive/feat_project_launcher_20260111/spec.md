# Specification: Project Environment Launcher (OmniHub Productivity)

## Overview
This feature allows users to define "Projects" within OmniHub. Launching a project will automatically prepare the Windows environment by opening specific folders and programs with predefined screen layouts (position and size).

## Functional Requirements

### 1. Project Configuration
- **Flat Project List:** Users can define multiple projects, each with a unique name and settings.
- **Actions per Project:**
    - **Open Folder:** Specify a path and an optional layout.
    - **Run Program:** Specify an executable path and optional arguments.
- **Layout Management:**
    - **Absolute Coordinates:** Define X, Y, Width, and Height in pixels.
    - **Screen Ratios:** Define position and size as percentages of the current screen (e.g., Left 50%, Top-Right 25%).

### 2. Window & Tab Management
- **Redundancy Prevention:** When opening a folder, the system must check if that folder (or an instance of the file manager) is already open.
- **Tab Integration:**
    - If a compatible window exists, open the folder in a **new tab** instead of a new window.
    - Support for **Windows Explorer** (Windows 11 tabs).
    - Support for **OneCommander**.

### 3. Hotkey Integration
- **OmniHub Hotkey Manager:** Leverage the existing hotkey system to bind dedicated global hotkeys to specific projects.
- **Successive Execution:** If multiple project hotkeys are pressed in succession, the environment should update/launch them in a compatible, non-conflicting way.

### 4. Prototyping & Tools
- **Explorer Tab Logic:** Research and implement a method to command Windows Explorer to open a new tab.
- **OneCommander Integration:** Research and implement tab-opening logic for OneCommander.
- **Layout Capture Tool:** A script/helper to "Capture Current Layout" from active windows to simplify project configuration for the user.

## Non-Functional Requirements
- **Responsiveness:** Projects should launch quickly with minimal delay between actions.
- **Robustness:** Invalid paths or closed programs should be handled gracefully without crashing the Hub.

## Acceptance Criteria
- [ ] A user can create a project with at least one "Open Folder" and one "Run Program" action.
- [ ] Launching a project correctly positions windows according to "Screen Ratio" settings.
- [ ] Opening a folder that is already "visible" in Explorer/OneCommander opens a new tab rather than a new window.
- [ ] Dedicated hotkeys successfully trigger project launches via the Hub's hotkey manager.
- [ ] The "Layout Capture" helper correctly populates project settings based on currently open windows.
