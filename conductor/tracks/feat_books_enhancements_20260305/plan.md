# Implementation Plan: Android Books Screen Enhancements

## Phase 1: Foundation & Bug Fixes
- [ ] **Task: Fix EPUB Black Screen Bug**
    - [ ] Research `EpubViewerScreen.kt` and `WebView` initialization.
    - [ ] Implement fix to ensure EPUB content loads correctly (likely JS interface or asset loading issue).
    - [ ] Verify fix by loading multiple EPUB files.
- [ ] **Task: Implement Reader Theme Customization**
    - [ ] Add `ReaderSettings` data class and persistence logic (DataStore or SharedPreferences).
    - [ ] Update `EpubViewerScreen` and `PdfViewerScreen` to support custom background and text colors.
    - [ ] Add a settings overlay in the reader UI for color selection.
- [ ] **Task: Implement Invert Colors for PDF**
    - [ ] Add "Invert Colors" toggle to the PDF reader settings.
    - [ ] Implement color inversion logic (e.g., using CSS filters or Paint color filters).
- [ ] **Task: Conductor - User Manual Verification 'Phase 1' (Protocol in workflow.md)**

## Phase 2: Navigation & Persistence
- [ ] **Task: Implement Pinch-to-Zoom**
    - [ ] Update `EpubViewerScreen` (WebView settings) and `PdfViewerScreen` (gesture detector) to support pinch-to-zoom.
    - [ ] Ensure zoom level is correctly tracked during interaction.
- [ ] **Task: Persist Zoom Levels**
    - [ ] Store current zoom level in `ReaderSettings` per book or globally as per spec.
    - [ ] Restore zoom level when opening a book or navigating pages.
- [ ] **Task: Persist EPUB Scroll Position**
    - [ ] Update EPUB progress saving logic to capture vertical scroll offset via JS.
    - [ ] Restore scroll position when resuming an EPUB book.
- [ ] **Task: Conductor - User Manual Verification 'Phase 2' (Protocol in workflow.md)**

## Phase 3: Library Management & UX
- [ ] **Task: Add In-Progress Section**
    - [ ] Update `BooksViewModel` to filter books/audiobooks with progress > 0.
    - [ ] Update `BooksScreen` to display this section at the top of the "All" tab using a horizontal or vertical list.
- [ ] **Task: Implement Long-Press Menu**
    - [ ] Add `combinedClickable` or similar long-press handler to book items.
    - [ ] Implement `ModalBottomSheet` with actions: Set Category, Remove Progress, Hide, Delete.
    - [ ] Implement "Set Category", "Remove Progress", and "Hide" logic in `BooksViewModel` and `SignalRClient`.
- [ ] **Task: Implement Delete Confirmation**
    - [ ] Add confirmation dialog for remote library deletion in the long-press menu.
    - [ ] Update "Offline" tab logic to allow deletion without confirmation.
- [ ] **Task: Conductor - User Manual Verification 'Phase 3' (Protocol in workflow.md)**

## Phase 4: Audiobook Player Enhancements
- [ ] **Task: Add Playback Speed Control**
    - [ ] Update `ExoPlayer` parameters in `BooksScreen` to support `PlaybackParameters`.
    - [ ] Add a speed selection UI in the audiobook player (0.5x to 2.0x).
- [ ] **Task: Implement Sleep Timer**
    - [ ] Add a timer mechanism for audiobooks that pauses playback.
    - [ ] Implement UI for selecting 15, 30, 60, 120 mins or "End of Chapter".
- [ ] **Task: Multi-file Display & Navigation**
    - [ ] Display the filename of the current playing track below the book title.
    - [ ] Add Next/Previous buttons for player controls for multi-file books.
    - [ ] Ensure seamless transition and correct indexing for multi-file playback.
- [ ] **Task: Conductor - User Manual Verification 'Phase 4' (Protocol in workflow.md)**
