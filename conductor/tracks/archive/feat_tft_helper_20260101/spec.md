# Specification: TFT Helper Web Page

## Overview
Add a dedicated "TFT Helper" page to the OmniSync Web platform. This tool will assist players with team composition optimization, specific game mode solvers, and strategic direction based on current TFT sets (starting with Set 16).

## Functional Requirements

### 1. Navigation & Layout
- **Top Navigation Bar:** Tabs to switch between different helper modes.
- **Tabs:**
    - **Emblem Portal:** The primary tool for team comp calculation (formerly Optimizer).
    - **World Runes:** Embedded iframe of `https://worldrunestft.vercel.app/`.
    - **BronzeForLife:** Stub for a future solver.
    - **Director:** Stub for a future direction-finding tool (planned as drag-and-drop).
    - **Configuration:** Interface for managing set data.

### 2. Emblem Portal Mode
- **Logic:** Replicate the scoring and combinatorial logic from `TFT.py`.
- **Inputs:**
    - Unit selection (Must-include).
    - Emblem selection.
- **Outputs:**
    - Top 3 optimal Level 6 boards.
    - Top 3 optimal Level 8 boards.
- **Presentation:** Display units with icons, names, and costs.

### 3. Data Management
- **Source:** Data (Units, Traits, Items) will be stored in JSON files on the `OmniSync.Hub` server.
- **Assets:** Icons will be stored in categorized folders (e.g., `/assets/tft/icons/set16/champions/`).
- **Extensibility:** The system must support swapping JSON files and icon folders for future TFT sets.

### 4. Configuration Page
- **Purpose:** Allow viewing and potentially editing the current set's configuration (JSON data).

## Non-Functional Requirements
- **Visual Style:** Consistent with the existing OmniSync Web theme (Material Design/Bootstrap).
- **Local-First:** Data should be served from the local Hub.

## Out of Scope
- Full implementation of BronzeForLife and Director solvers.
- Automated harvesting of icons from Mobalytics (this will be a manual/semi-manual setup).
