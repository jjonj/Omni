import os
import subprocess
import re

def split_by_silence(file_path, output_folder):
    print(f"Processing {file_path}...")
    # Use -35dB and shorter duration to catch quick beeps
    cmd = [
        'ffmpeg', '-i', file_path,
        '-af', 'silencedetect=noise=-35dB:d=0.1',
        '-f', 'null', '-'
    ]
    result = subprocess.run(cmd, stderr=subprocess.PIPE, text=True, encoding='utf-8')
    
    silence_starts = re.findall(r'silence_start: ([\d.-]+)', result.stderr)
    silence_ends = re.findall(r'silence_end: ([\d.-]+)', result.stderr)
    
    starts = [float(s) for s in silence_starts]
    ends = [float(e) for e in silence_ends]
    
    if not starts:
        print(f"  No silence detected.")
        return

    segments = []
    # If audio starts after a silence block
    if starts[0] < 0.1:
        seg_start = ends[0] if ends else 0
    else:
        seg_start = 0

    # Segments are between silences
    for i in range(len(starts)):
        seg_end = starts[i]
        if seg_end - seg_start > 0.01: # Allow extremely short beeps
            segments.append((seg_start, seg_end))
        
        # Next segment starts when this silence ends
        if i < len(ends):
            seg_start = ends[i]
        else:
            # We reached a silence_start but there is no silence_end
            # This means the rest of the file is silence
            seg_start = None
            break

    # Add final segment ONLY if we didn't end in silence
    if seg_start is not None:
        segments.append((seg_start, None))

    base_name = os.path.splitext(os.path.basename(file_path))[0]
    
    valid_count = 0
    for i, (start, end) in enumerate(segments):
        temp_output = os.path.join(output_folder, f"temp_{i}.wav")
        
        split_cmd = ['ffmpeg', '-y', '-ss', str(start), '-i', file_path]
        if end is not None:
            duration = end - start
            if duration < 0.01: continue
            split_cmd.extend(['-t', str(duration)])
        
        split_cmd.extend(['-c', 'copy', temp_output])
        subprocess.run(split_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        
        if not os.path.exists(temp_output): continue

        # Check for actual content using volumedetect
        vol_cmd = ['ffmpeg', '-i', temp_output, '-af', 'volumedetect', '-f', 'null', '-']
        vol_res = subprocess.run(vol_cmd, stderr=subprocess.PIPE, text=True, encoding='utf-8')
        
        # If max_volume is very low (e.g., -60dB), it's probably silence
        max_vol_match = re.search(r'max_volume: ([\d.-]+) dB', vol_res.stderr)
        if max_vol_match:
            if float(max_vol_match.group(1)) < -50:
                os.remove(temp_output)
                continue

        valid_count += 1
        final_output = os.path.join(output_folder, f"{base_name}_v{valid_count:02d}.wav")
        if os.path.exists(final_output): os.remove(final_output)
        os.rename(temp_output, final_output)
        print(f"  Saved v{valid_count:02d} ({start}s - {end if end else 'EOF'})")

def main():
    folder = r"Resources\SoundEffects"
    orig_folder = os.path.join(folder, "Originals")
    
    if not os.path.exists(orig_folder):
        print("Originals folder not found.")
        return

    # Clean up existing split files to avoid confusion
    for f in os.listdir(folder):
        path = os.path.join(folder, f)
        if ("_v" in f or "_split_" in f) and f.lower().endswith(".wav"):
            if os.path.exists(path):
                os.remove(path)

    files = [f for f in os.listdir(orig_folder) if f.lower().endswith(".wav")]
    print(f"Found {len(files)} original files.")

    for file in files:
        full_path = os.path.join(orig_folder, file)
        split_by_silence(full_path, folder)

if __name__ == "__main__":
    main()
