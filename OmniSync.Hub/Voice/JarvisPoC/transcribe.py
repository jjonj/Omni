import os
import time
import subprocess
import sys

LOCK_FILE = r"D:\SSDProjects\Omni\OmniSync.Hub\Voice\JarvisPoC\recording.lock"
INPUT_FILE = r"D:\SSDProjects\Omni\OmniSync.Hub\Voice\JarvisPoC\speech_input.wav"
TRANSCRIPT_FILE = r"D:\SSDProjects\Omni\OmniSync.Hub\Voice\JarvisPoC\transcript.txt"

def transcribe():
    print("Transcribe Worker: Waiting for recording...")
    
    # Wait for the lock file to appear (recording started)
    while not os.path.exists(LOCK_FILE):
        if os.path.exists(INPUT_FILE):
            # If file exists and is very new, assume we missed the lock
            if (time.time() - os.path.getmtime(INPUT_FILE)) < 5:
                break
        time.sleep(0.1)
        
    # Wait for the lock file to disappear (recording finished)
    while os.path.exists(LOCK_FILE):
        time.sleep(0.1)
        
    print("Transcribe Worker: Processing audio...")
    
    # The functioning command from history
    abs_input = os.path.abspath(INPUT_FILE)
    # parakeet-rs is a sibling directory
    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parakeet_dir = os.path.join(base_dir, "parakeet-rs")
    cmd = ["cargo", "run", "--example", "transcribe", "--", abs_input, "tdt"]
    
    try:
        result = subprocess.run(cmd, cwd=parakeet_dir, capture_output=True, text=True)
        output = result.stdout
        
        # Check output for transcription
        if "Transcription completed" in output:
            # Extract line before "Sentencess:"
            lines = output.splitlines()
            transcription = ""
            for i, line in enumerate(lines):
                if "Sentencess:" in line and i > 0:
                    transcription = lines[i-1].strip()
                    break
            
            if not transcription:
                # Fallback: find first non-empty line after "Running..."
                found_running = False
                for line in lines:
                    if "Running" in line: found_running = True
                    elif found_running and line.strip() and "Sentencess" not in line:
                        transcription = line.strip()
                        break

            print(f"\n[JARVIS HEARD] {transcription}")
            with open(TRANSCRIPT_FILE, "w") as f:
                f.write(transcription)
        else:
            print("Transcribe Worker: Error - could not parse output.")
            
    except Exception as e:
        print(f"Transcribe Worker Error: {e}")

if __name__ == "__main__":
    transcribe()
