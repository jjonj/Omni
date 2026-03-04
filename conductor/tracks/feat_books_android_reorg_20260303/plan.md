# Implementation Plan: Android Books Screen & Library Reorganization

## Phase 1: PC Library Reorganization & Code Audit [checkpoint: 5f6aadc]
- [x] Task: Review existing local changes (`BooksScreen.kt`, `MainActivity.kt`, etc.) and integrate them into the track's baseline. (2b7b99c)
- [x] Task: Create a Python script `TestScripts/Books/reorganize_library.py` to dry-run the reorganization of `B:\GDrive\Books`. (d8e3d5a)
- [x] Task: Implement keyword-based classification logic (e.g., "manga" -> Manga, "tutorial" -> Technical). (d8e3d5a)
- [x] Task: Present the dry-run output to the user and obtain approval for the move operations. (d8e3d5a)
- [x] Task: Execute the reorganization script and verify the new folder structure. (d8e3d5a)
- [x] Task: Conductor - User Manual Verification 'PC Library Reorganization' (Protocol in workflow.md) (5f6aadc)


## Phase 2: Hub Backend Enhancements (TDD) [checkpoint: 9c10450]
- [x] Task: Write failing unit tests for `BookProgressService` (storing and retrieving last position/page). (5d58853)
- [x] Task: Implement `BookProgressService` in `OmniSync.Hub`. (5d58853)
- [x] Task: Write failing unit tests for recursive book scanning and metadata extraction in `FileService`. (1690e41)
- [x] Task: Update `FileService` to support optimized library indexing and cover extraction. (1690e41)
- [x] Task: Conductor - User Manual Verification 'Hub Backend Enhancements' (Protocol in workflow.md) (9c10450)

## Phase 3: Android App - Persistent Library & Categories (TDD)
## Phase 3: Android App - Persistent Library & Categories (TDD) [checkpoint: 32186c6]
- [x] Task: Write failing unit tests for `LibraryViewModel` (fetching and caching the library from the Hub). (5cca53b)
- [x] Task: Implement persistent library caching and category filtering in the Android app. (31eebc4)
- [x] Task: Update `BooksScreen.kt` to support top-level categories (Audiobooks/Ebooks) and sub-categories. (32186c6)
- [x] Task: Conductor - User Manual Verification 'Android App - Persistent Library & Categories' (Protocol in workflow.md) (32186c6)

## Phase 4: Android App - Downloads & Offline Support (TDD) [checkpoint: 4a95d8b]
- [x] Task: Write failing unit tests for `BookDownloadManager` (background transfers and status tracking). (e119e49)
- [x] Task: Implement `BookDownloadManager` and local file storage logic on Android. (9f81f21)
- [x] Task: Add "Offline" filter and download progress indicators to `BooksScreen.kt`. (9f81f21)
- [x] Task: Conductor - User Manual Verification 'Android App - Downloads & Offline Support' (Protocol in workflow.md) (4a95d8b)


## Phase 5: Android App - Progress Tracking & Visuals (TDD) [checkpoint: 7e587a5]
- [x] Task: Write failing unit tests for playback/reading position persistence. (4d7746e)
- [x] Task: Implement position tracking for Audiobooks (ExoPlayer integration). (1897f8b)
- [x] Task: Implement page tracking for eBooks (PDF/EPUB viewer integration). (e6bb22b)
- [x] Task: Update `BookListItem` to display covers extracted from metadata or fallback to folder covers. (4de4de3)
- [x] Task: Conductor - User Manual Verification 'Android App - Progress Tracking & Visuals' (Protocol in workflow.md) (7e587a5)

## Phase 6: Final Verification & Polish
- [x] Task: Perform end-to-end verification of library scanning, downloading, and progress synchronization. (7e587a5)
- [x] Task: Final UI polish and bug fixing. (7e587a5)
- [x] Task: Conductor - User Manual Verification 'Final Verification & Polish' (Protocol in workflow.md) (7e587a5)

