import os
import sys
import numpy as np
import sounddevice as sd
import librosa
import pickle
import subprocess
import time
import queue
import threading

# --- Robust Config ---
FINGERPRINT_FILE = r"D:\SSDProjects\Omni\OmniSync.Hub\Voice\JarvisPoC\fingerprint.pkl"
FS = 16000
CHUNK_DURATION = 1.5
THRESHOLD = 0.95      # Balanced
ENERGY_THRESHOLD = 0.02 # Ignore very quiet noise

if not os.path.exists(FINGERPRINT_FILE):
    print("Error: Fingerprint file not found.")
    sys.exit(1)

with open(FINGERPRINT_FILE, "rb") as f:
    target_fingerprints = pickle.load(f)

print(f"\nJarvis Listener: ACTIVE.")
print(f"Monitoring for 'Jarvis'...")

audio_queue = queue.Queue()

def audio_processor():
    audio_buffer = np.zeros(int(FS * CHUNK_DURATION))
    
    while True:
        indata = audio_queue.get()
        if indata is None: break
        
        audio_buffer = np.roll(audio_buffer, -len(indata))
        audio_buffer[-len(indata):] = indata.flatten()
        
        energy = np.sqrt(np.mean(indata**2))
        if energy < ENERGY_THRESHOLD:
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
            
            if max_sim > THRESHOLD:
                print(f"\n[MATCH] Confidence: {max_sim:.4f}")
                recorder_path = r"D:\SSDProjects\Omni\OmniSync.Hub\Voice\JarvisPoC\recorder.py"
                subprocess.Popen([sys.executable, recorder_path])
                os._exit(0)
        except Exception as e:
            pass

threading.Thread(target=audio_processor, daemon=True).start()

def callback(indata, frames, time_info, status):
    audio_queue.put(indata.copy())

try:
    with sd.InputStream(samplerate=FS, channels=1, callback=callback, blocksize=2048):
        while True:
            time.sleep(1)
except KeyboardInterrupt:
    print("\nStopped.")