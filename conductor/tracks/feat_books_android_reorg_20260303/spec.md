# Specification: Android Books Screen & Library Reorganization

## 1. Overview
This track involves completing the "Books" feature for the OmniSync Android app and implementing a scripted library reorganization for the primary books repository on the Windows PC. The goal is to provide a seamless reading and listening experience with offline support and a clean, categorized library.

## 2. Functional Requirements

### 2.1 Android Books Screen
- **Persistent Library:** The app will automatically scan and display content from the configured library root on the PC (`B:\GDrive\Books`).
- **Category Filtering:** Support for filtering by top-level categories: `Audiobooks` vs. `Ebooks`.
- **Sub-category Browsing:** Inside each top-level category, allow browsing by sub-folders (e.g., `Manga`, `Self-help`, `Fiction`, `Technical`).
- **Search:** Real-time search by filename/book title across the entire library.
- **Offline Support (Downloads):** 
    - Option to download audiobooks and eBooks to the Android device.
    - A "Downloaded" filter or section to access offline content.
- **Progress Tracking:**
    - **Audiobooks:** Save and restore the last playback position (timestamp).
    - **Ebooks (PDF/EPUB):** Save and restore the last read page/position.
    - **Sync:** Periodically sync progress back to the Hub's persistent store.
- **Visuals:**
    - Display book covers by extracting them from metadata (EPUB/Audiobook).
    - Fallback to `cover.jpg/png` if present in the book's folder.
    - Use stylized icons as a final fallback.

### 2.2 Library Reorganization (PC)
- **Root Directory:** `B:\GDrive\Books`.
- **Target Structure:**
    - `[Audiobook|Book]/[Manga|Self-help|Fiction|Technical|Unsorted]/[Filename]`
- **Reorganization Logic:**
    - Classify as `Audiobook` (.m4b, .mp3, .aac) or `Book` (.epub, .pdf).
    - Categorize into sub-folders based on filename keywords (e.g., "manga", "technical", "tutorial", "novel").
    - Use a "Safe Scripted" approach: Generate a Python/PowerShell script that performs the moves for review before execution.
    - Guess title/category from the existing filename.
- **Unsorted Fallback:** Files that cannot be confidently categorized will be moved to an `Unsorted` folder within the appropriate top-level directory (`Audiobook/Unsorted` or `Book/Unsorted`).

## 3. Technical Requirements
- **Hub Integration:** 
    - Enhance `FileService` to support recursive metadata extraction (covers/titles).
    - Add a new `BookProgressService` to the Hub to store and sync progress.
- **Android Integration:**
    - Update `MainViewModel` to manage the persistent library state and download queue.
    - Implement a `DownloadManager` for reliable background file transfers.
    - Enhance `BooksScreen.kt` with the new UI elements for categories, progress, and downloads.

## 4. Acceptance Criteria
- [ ] `B:\GDrive\Books` is reorganized according to the proposed script (after user approval).
- [ ] Android app displays the full library from the PC without manual folder browsing.
- [ ] Audiobooks can be played, and the position is remembered after closing/reopening.
- [ ] eBooks can be opened, and the page position is tracked.
- [ ] Files can be downloaded and accessed when the Hub is offline.
- [ ] Book covers are displayed correctly where available.

## 5. Out of Scope
- Integration with external services like Goodreads or OpenLibrary.
- Support for DRM-protected content.
- Multi-user progress syncing (single-user focus).
