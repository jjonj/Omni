import os
import shutil
from pathlib import Path

# --- Configuration ---
ROOT_DIR = r"B:\GDrive\Books"
DRY_RUN = False # Always True initially

CATEGORIES = {
    "Manga": ["manga", "manhwa", "comics", "doujin", "anime"],
    "Self-help": ["self-help", "psychology", "mindset", "productivity", "habit", "mood", "meditation", "diet", "brain", "mental", "memory", "learning", "neuro", "lucid", "dream"],
    "Fiction": ["fiction", "novel", "story", "thriller", "fantasy", "sci-fi", "midnight library", "three-body", "problem", "liu cixin"],
    "Technical": ["technical", "tutorial", "programming", "coding", "guide", "dev", "documentation", "learning", "distributed", "network", "algorithms", "database", "hci", "design", "ue4", "xna", "software", "modeling", "math", "poker", "gto", "theory"]
}

AUDIO_EXT = {".m4b", ".mp3", ".aac", ".opus", ".aax", ".aa"}
BOOK_EXT = {".epub", ".pdf", ".mobi", ".azw3"}

def classify_type(filename):
    ext = os.path.splitext(filename)[1].lower()
    if ext in AUDIO_EXT:
        return "Audiobooks"
    if ext in BOOK_EXT:
        return "Books"
    return None

def guess_category(filename):
    name = filename.lower()
    for cat, keywords in CATEGORIES.items():
        for kw in keywords:
            if kw in name:
                return cat
    return "Unsorted"

def reorganize():
    print(f"--- Reorganizing {ROOT_DIR} (DRY_RUN={DRY_RUN}) ---")
    if not os.path.exists(ROOT_DIR):
        print(f"ERROR: Root directory {ROOT_DIR} does not exist.")
        return

    moves = []
    handled_paths = set()

    # 1. Detect and handle "Folder Audiobooks"
    for root, dirs, files in os.walk(ROOT_DIR):
        rel_path = os.path.relpath(root, ROOT_DIR)
        
        # Skip if already inside a category folder of Books/Audiobooks
        # e.g. Books\Fiction is handled, but Books\SomeBook is not.
        parts = Path(rel_path).parts
        if len(parts) >= 2 and (parts[0] in ["Books", "Audiobooks"]) and (parts[1] in CATEGORIES or parts[1] == "Unsorted"):
            continue
        
        if rel_path == ".":
            continue
        
        if any(root.startswith(hp) for hp in handled_paths):
            continue

        dir_audio_files = [f for f in files if os.path.splitext(f)[1].lower() in AUDIO_EXT]
        if len(dir_audio_files) > 1:
            # Treat this entire folder as an Audiobook
            category = guess_category(os.path.basename(root))
            target_dir = os.path.join(ROOT_DIR, "Audiobooks", category, os.path.basename(root))
            
            if root != target_dir:
                moves.append((root, target_dir))
                handled_paths.add(root)

    # 2. Handle remaining individual files
    for root, dirs, files in os.walk(ROOT_DIR):
        rel_path = os.path.relpath(root, ROOT_DIR)
        parts = Path(rel_path).parts
        
        if len(parts) >= 2 and (parts[0] == "Books" or parts[0] == "Audiobooks"):
            continue
        
        if any(root.startswith(hp) for hp in handled_paths):
            continue

        for file in files:
            src_path = os.path.join(root, file)
            
            book_type = classify_type(file)
            if not book_type:
                continue 
            
            category = guess_category(file)
            target_dir = os.path.join(ROOT_DIR, book_type, category)
            target_path = os.path.join(target_dir, file)
            
            if src_path == target_path:
                continue

            moves.append((src_path, target_path))

    if not moves:
        print("No moves necessary.")
        return

    print(f"Total files to move: {len(moves)}")
    for src, dst in moves:
        print(f"MOVE: {os.path.relpath(src, ROOT_DIR)} -> {os.path.relpath(dst, ROOT_DIR)}")
        
        if not DRY_RUN:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            try:
                shutil.move(src, dst)
            except Exception as e:
                print(f"FAILED to move {src}: {e}")

    if DRY_RUN:
        print("\n--- DRY RUN COMPLETE. No files were moved. ---")

if __name__ == "__main__":
    reorganize()
