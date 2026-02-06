import os
import sys
import numpy as np
import sounddevice as sd
import librosa
import pickle
import time
import queue
import threading
import scipy.io.wavfile as wav
import subprocess
import soundfile as sf
import string

# --- Configuration ---
BASE_DIR = r"D:\SSDProjects\Omni\OmniSync.Hub\Voice"
FINGERPRINT_FILE = os.path.join(BASE_DIR, "Assets", "fingerprint.pkl")
INPUT_WAV = os.path.join(BASE_DIR, "Audio", "speech_input.wav")
SAY_AGAIN_WAV = r"D:\SSDProjects\Omni\Resources\SoundEffects\Error_01.wav"
PARAKEET_DIR = os.path.join(BASE_DIR, "parakeet-rs")
ASR_LOG = os.path.join(BASE_DIR, "MIND", "asr_debug.log")

# Audio Settings
FS = 16000
WAKE_THRESHOLD = 0.95
WAKE_ENERGY_THRESHOLD = 0.02
VAD_SILENCE_THRESHOLD = 0.01
VAD_SILENCE_DURATION = 2.0
VAD_MAX_DURATION = 30

def get_fingerprints():
    if not os.path.exists(FINGERPRINT_FILE):
        raise FileNotFoundError(f"Fingerprint file not found: {FINGERPRINT_FILE}")
    with open(FINGERPRINT_FILE, "rb") as f:
        return pickle.load(f)

def listen_for_wake_word(target_fingerprints):
    print("\n[LISTENING] Waiting for 'Jarvis'...", flush=True)
    audio_queue = queue.Queue()
    
    def callback(indata, frames, time_info, status):
        audio_queue.put(indata.copy())

    stop_event = threading.Event()
    audio_buffer = np.zeros(int(FS * 1.5)) # 1.5s buffer

    def processor():
        nonlocal audio_buffer
        while not stop_event.is_set():
            try:
                indata = audio_queue.get(timeout=1)
            except queue.Empty:
                continue
            
            audio_buffer = np.roll(audio_buffer, -len(indata))
            audio_buffer[-len(indata):] = indata.flatten()
            
            energy = np.sqrt(np.mean(indata**2))
            if energy < WAKE_ENERGY_THRESHOLD:
                continue

            try:
                mfcc = librosa.feature.mfcc(y=audio_buffer, sr=FS, n_mfcc=13)
                current_mfcc = np.mean(mfcc, axis=1)
                norm = np.linalg.norm(current_mfcc)
                if norm < 1e-6: continue
                current_norm = current_mfcc / norm
                
                max_sim = 0
                for target in target_fingerprints:
                    sim = np.dot(current_norm, target)
                    if sim > max_sim:
                        max_sim = sim
                
                if max_sim > WAKE_THRESHOLD:
                    print(f"[WAKE] Detected (Confidence: {max_sim:.4f})", flush=True)
                    stop_event.set()
            except Exception:
                pass

    t = threading.Thread(target=processor, daemon=True)
    t.start()

    with sd.InputStream(samplerate=FS, channels=1, callback=callback, blocksize=2048):
        while not stop_event.is_set():
            time.sleep(0.1)
    
    return True

def record_command():
    print("[RECORDING] Speak now...", flush=True)
    audio_data = []
    silent_chunks = 0
    
    def callback(indata, frames, time, status):
        nonlocal silent_chunks
        audio_data.append(indata.copy())
        energy = np.sqrt(np.mean(indata**2))
        if energy < VAD_SILENCE_THRESHOLD:
            silent_chunks += 1
        else:
            silent_chunks = 0

    with sd.InputStream(samplerate=FS, channels=1, callback=callback, blocksize=int(FS * 0.1)):
        start = time.time()
        while True:
            if (silent_chunks * 0.1) > VAD_SILENCE_DURATION or (time.time() - start) > VAD_MAX_DURATION:
                break
            time.sleep(0.1)

    full_audio = np.concatenate(audio_data, axis=0)
    wav.write(INPUT_WAV, FS, full_audio)
    print("[RECORDING] Saved.", flush=True)

def transcribe():
    print("[ASR] Transcribing (GPU)...", flush=True)
    
    if not os.path.exists(INPUT_WAV):
        print(f"[ERROR] Input file missing: {INPUT_WAV}", flush=True)
        return ""
    
    file_size = os.path.getsize(INPUT_WAV)
    print(f"[DEBUG] Input WAV size: {file_size} bytes", flush=True)
    
    # Use the CUDA-enabled example for speed
    cmd = ["cargo", "run", "--example", "cuda_transcribe", "--features", "cuda", "--", os.path.abspath(INPUT_WAV)]
    print(f"[DEBUG] Command: {' '.join(cmd)}", flush=True)
    
    try:
        # Increase timeout and capture stderr
        result = subprocess.run(cmd, cwd=PARAKEET_DIR, capture_output=True, text=True, timeout=60)
        
        output = result.stdout
        error_output = result.stderr
        
        # Log to debug file
        log_path = ASR_LOG
        with open(log_path, "w", encoding="utf-8") as f:
            f.write(f"EXIT CODE: {result.returncode}\n")
            f.write("--- STDOUT ---\n")
            f.write(output)
            f.write("\n--- STDERR ---\n")
            f.write(error_output)

        if result.returncode != 0:
            print(f"[ASR] Error: Process exited with code {result.returncode}", flush=True)
            return ""
        
        transcription = ""
        lines = [line.strip() for line in output.splitlines() if line.strip()]
        
        # Strategy A: Look for "Transcription:" header (common in cuda_transcribe)
        try:
            if "Transcription:" in output:
                # Extract everything between "Transcription:" and "Sentences:"
                parts = output.split("Transcription:")
                if len(parts) > 1:
                    content = parts[1].split("Sentences:")[0].strip()
                    transcription = content
        except: pass

        if not transcription:
            # Strategy B: Line before "Sentencess:" (plural variant sometimes used)
            for i, line in enumerate(lines):
                if "Sentencess:" in line and i > 0:
                    transcription = lines[i-1]
                    break
        
        if not transcription:
        
            # Strategy C: Line before "Sentences:"
        
            for i, line in enumerate(lines):
        
                if "Sentences:" in line and i > 0:
        
                    transcription = lines[i-1]
        
                    break
        
        
        
        # Parse timers from output
        
        for line in lines:
        
            if "[TIMER]" in line:
        
                print(line, flush=True)
        
        
        
        if not transcription and lines:
        
            # Check if any line looks like real text
        
            for line in lines:
        
                if not any(x in line for x in ["Finished", "Running", "Transcription", "Sentences", "Words", "audio:", "execution"]):
        
                    transcription = line
        
                    break
        
        if not transcription:
            print("[DEBUG] ASR engine returned successfully but no text was parsed.", flush=True)
            print(f"[DEBUG] Total lines captured: {len(lines)}", flush=True)
            if len(lines) > 0:
                print(f"[DEBUG] Last few lines:\n" + "\n".join(lines[-3:]), flush=True)

        return transcription
    except subprocess.TimeoutExpired:
        print("ASR Error: Transcription timed out after 60s.", flush=True)
        return ""
    except Exception as e:
        print(f"ASR Error: {e}", flush=True)
        return ""

def is_garbage_transcription(text):
    if not text:
        return True
    
    # Strip punctuation and whitespace for pure word check
    import string
    clean_text = text.translate(str.maketrans('', '', string.punctuation)).strip().lower()
    
    if clean_text == "jarvis":
        print(f"[DEBUG] Only wake-word '{text}' detected. Treating as garbage.", flush=True)
        return True

    # Count non-standard ASCII characters
    # Standard ASCII is 32-126 (printable chars)
    non_ascii = 0
    for char in text:
        if not (32 <= ord(char) <= 126):
            non_ascii += 1
            
    ratio = non_ascii / len(text)
    if ratio > 0.5:
        print(f"[DEBUG] Garbage detected. Non-ASCII ratio: {ratio:.2%}", flush=True)
        return True
    return False

def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-wake", action="store_true", help="Skip wake-word detection and go straight to recording.")
    args = parser.parse_args()

    # Shared state for loop control
    is_continuation = False
    pending_audio = []
    fail_count = 0
    skip_wake_for_this_cycle = args.skip_wake

    while True: # Outer loop for the entire Jarvis life-cycle
        try:
            target_fingerprints = get_fingerprints()
            audio_queue = queue.Queue()
            
            def callback(indata, frames, time_info, status):
                audio_queue.put(indata.copy())

            with sd.InputStream(samplerate=FS, channels=1, callback=callback, blocksize=2048, dtype='float32'):
                
                # Phase 1: Wake Word Detection
                audio_buffer = np.zeros(int(FS * 1.5), dtype='float32') # 1.5s sliding window
                wake_detected = skip_wake_for_this_cycle
                
                if not wake_detected:
                    print("\n[LISTENING] Waiting for 'Jarvis'...", flush=True)
                
                while not wake_detected:
                    indata = audio_queue.get()
                    audio_buffer = np.roll(audio_buffer, -len(indata))
                    audio_buffer[-len(indata):] = indata.flatten()
                    
                    energy = np.sqrt(np.mean(indata**2))
                    if energy < WAKE_ENERGY_THRESHOLD:
                        continue

                    # Feature extraction
                    mfcc = librosa.feature.mfcc(y=audio_buffer, sr=FS, n_mfcc=13)
                    current_mfcc = np.mean(mfcc, axis=1)
                    norm = np.linalg.norm(current_mfcc)
                    if norm < 1e-6: continue
                    current_norm = current_mfcc / norm
                    
                    max_sim = 0
                    for target in target_fingerprints:
                        sim = np.dot(current_norm, target)
                        if sim > max_sim:
                            max_sim = sim
                    
                    if max_sim > WAKE_THRESHOLD:
                        print(f"[WAKE] Detected (Confidence: {max_sim:.4f})", flush=True)
                        wake_detected = True

                # --- Command Capture Loop ---
                final_text = ""
                while not final_text:
                    if fail_count >= 4:
                        print("\n[SYSTEM] Too many failed attempts. Returning to wake listener.", flush=True)
                        fail_count = 0
                        is_continuation = False
                        pending_audio = []
                        skip_wake_for_this_cycle = False
                        break # Break out to Phase 1

                    timeout = 10.0 if is_continuation else 5.0
                    print(f"[RECORDING] Waiting for speech (Timeout: {timeout}s)...", flush=True)
                    
                    audio_data = pending_audio
                    pending_audio = [] # Clear inherited
                    
                    # If fresh start after wake word, prepend the PRE-ROLL buffer
                    if fail_count == 0 and not is_continuation:
                        audio_data.append(audio_buffer.reshape(-1, 1).copy())
                    
                    if not is_continuation:
                        while not audio_queue.empty():
                            audio_queue.get()

                    speech_started = False
                    if is_continuation and len(audio_data) > 0:
                        combined_prev = np.concatenate(audio_data, axis=0)
                        if np.max(np.abs(combined_prev)) > VAD_SILENCE_THRESHOLD:
                            print("[RECORDING] Speech already detected in continuation buffer. Recording...", flush=True)
                            speech_started = True

                    silent_chunks = 0
                    start_wait_time = time.time()
                    exit_capture_loop = False
                    
                    while True:
                        try:
                            indata = audio_queue.get(timeout=0.1)
                        except queue.Empty:
                            if not speech_started and (time.time() - start_wait_time) > timeout:
                                print(f"[RECORDING] Timeout: No speech detected for {timeout}s. Returning to wake listener.", flush=True)
                                skip_wake_for_this_cycle = False
                                is_continuation = False
                                exit_capture_loop = True
                                break
                            continue
                        
                        energy = np.sqrt(np.mean(indata**2))
                        
                        if not speech_started:
                            if energy > VAD_SILENCE_THRESHOLD:
                                print("[RECORDING] Speech detected. Recording...", flush=True)
                                speech_started = True
                                audio_data.append(indata.copy())
                        else:
                            audio_data.append(indata.copy())
                            if energy < VAD_SILENCE_THRESHOLD:
                                silent_chunks += 1
                            else:
                                silent_chunks = 0

                            if (silent_chunks * (2048/FS)) > VAD_SILENCE_DURATION:
                                print("[RECORDING] Pause detected. Processing...", flush=True)
                                break
                            
                            if (time.time() - start_wait_time) > VAD_MAX_DURATION:
                                print("[RECORDING] Max duration reached.", flush=True)
                                break

                    if exit_capture_loop:
                        break # Break out to Phase 1

                    # --- SHADOW LOGIC ---
                    initial_segment = np.concatenate(audio_data, axis=0)
                    max_val = np.max(np.abs(initial_segment))
                    if max_val > 0:
                        initial_segment = initial_segment / max_val * 0.9
                    sf.write(INPUT_WAV, initial_segment, FS, subtype='FLOAT')

                    asr_result = {"text": None}
                    start_asr = time.time()
                    def background_asr():
                        asr_result["text"] = transcribe()
                    
                    asr_thread = threading.Thread(target=background_asr)
                    asr_thread.start()

                    shadow_audio = []
                    while asr_thread.is_alive():
                        try:
                            indata = audio_queue.get(timeout=0.1)
                            shadow_audio.append(indata.copy())
                        except queue.Empty:
                            continue
                    
                    asr_duration = time.time() - start_asr
                    print(f"[TIMER] ASR took {asr_duration:.2f}s", flush=True)

                    text = asr_result["text"]
                    
                    if text and not any(x == text.lower().strip() for x in ["sentencess:", "transcription:", "sentences:"]) and not is_garbage_transcription(text):
                        clean_text = text.strip().lower().rstrip(string.punctuation)
                        if clean_text.endswith("uh") or clean_text.endswith("um"):
                            print(f"\n[JARVIS_HEARD] {text} (Detected filler, resuming with shadow audio...)", flush=True)
                            is_continuation = True
                            pending_audio = shadow_audio 
                            audio_buffer.fill(0) 
                            continue 
                        
                        final_text = text
                        print(f"\n[JARVIS_HEARD] {final_text}", flush=True)
                        return # SUCCESSFUL EXIT
                    else:
                        fail_count += 1
                        is_continuation = False
                        print(f"\n[JARVIS_HEARD] (Empty, error, or garbage) - Attempt {fail_count}/4. Triggering retry...", flush=True)
                        if os.path.exists(SAY_AGAIN_WAV):
                            try:
                                data, fs = sf.read(SAY_AGAIN_WAV)
                                sd.play(data, fs)
                                sd.wait()
                            except: pass
                        time.sleep(0.5)
                        audio_buffer.fill(0)

        except KeyboardInterrupt:
            print("\nStopped.")
            break
        except Exception as e:
            print(f"\nCritical Error: {e}")
            break

if __name__ == "__main__":
    main()
