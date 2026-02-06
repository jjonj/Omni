import os
import re

def rename_sounds():
    folder = r"Resources\SoundEffects"
    if not os.path.exists(folder):
        return

    # Get all wav files in the root of SoundEffects
    files = [f for f in os.listdir(folder) if f.lower().endswith(".wav")]
    
    # Sort files to maintain some consistency in numbering
    files.sort()

    categories = ["Back", "Error", "Scroll", "Select"]
    counters = {cat: 1 for cat in categories}

    for filename in files:
        found_cat = None
        for cat in categories:
            if cat.lower() in filename.lower():
                found_cat = cat
                break
        
        if found_cat:
            new_name = f"{found_cat}_{counters[found_cat]:02d}.wav"
            old_path = os.path.join(folder, filename)
            new_path = os.path.join(folder, new_name)
            
            # If target exists (rare here but safe), increment counter
            while os.path.exists(new_path):
                counters[found_cat] += 1
                new_name = f"{found_cat}_{counters[found_cat]:02d}.wav"
                new_path = os.path.join(folder, new_name)

            print(f"Renaming: {filename} -> {new_name}")
            os.rename(old_path, new_path)
            counters[found_cat] += 1
        else:
            print(f"Skipping (no category found): {filename}")

if __name__ == "__main__":
    rename_sounds()
