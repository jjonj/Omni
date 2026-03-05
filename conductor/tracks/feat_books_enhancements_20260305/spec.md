# Specification: Android Books Screen Enhancements

## Overview
Enhance the Books and Audiobooks experience on the Android app with in-progress tracking, management tools, and reader improvements.

## Functional Requirements

### 1. Landing Page
- **In-Progress Section:** Add a dedicated section at the top of the "All" tab displaying books and audiobooks that are currently being read or listened to (saved progress).

### 2. Management Menu
- **Long-Press Menu:** Implement a menu for books in the browse list:
  - **Set Category:** Move the book to a specific folder.
  - **Remove Progress:** Reset current progress to zero.
  - **Hide:** Mark the book as hidden in the library.
  - **Delete:** Remove from the remote library (requires confirmation popup).
- **Offline Deletion:** Allow deleting books from the "Offline" tab WITHOUT confirmation.

### 3. Reader Enhancements (EPUB & PDF)
- **Fix EPUB Bug:** Resolve the issue where EPUBs display a black "Loading EPUB..." screen.
- **Customization:** Add controls for background and text colors (Global Settings).
- **Invert Toggle:** Add a toggle to invert colors for image-based PDFs (e.g., white-on-black).
- **Pinch to Zoom:** Implement pinch-to-zoom for the reader and persist the zoom level between pages and sessions.
- **Scroll Progress:** For EPUBs, persist the scroll position in addition to the page progress.

### 4. Audiobook Player
- **Playback Speed:** Add options to change playback speed (e.g., 0.5x, 1x, 1.25x, 1.5x, 2x).
- **Sleep Timer:** Add a timer (15, 30, 60, 120 mins) or an "End of Chapter/File" option.
- **Multi-File Support:**
  - Display the current file name below the main book title.
  - Add Next/Previous buttons for navigating between tracks in multi-file books.

## Acceptance Criteria
- "In-Progress" section correctly identifies and displays active books.
- Long-press menu actions correctly update the library and progress.
- EPUB reader correctly renders content instead of a black screen.
- Reader themes and zoom levels are persisted across sessions.
- Audiobook player features (speed, timer, multi-file navigation) function correctly.

## Out of Scope
- Automatic book categorization.
- Support for external reader apps.
