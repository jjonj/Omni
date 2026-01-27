import sounddevice as sd
import numpy as np
import scipy.io.wavfile as wav
import os
import time
import librosa
import pickle
import shutil

# Config
FS = 16000
DURATION = 2.0 # Increased to 2s for sentences
SAMPLES = 10
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_DIR = os.path.join(BASE_DIR, "custom_samples")
PKL_FILE = os.path.join(BASE_DIR, "fingerprint.pkl")

def record_and_fingerprint():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    
    print("--- Jarvis Voice Trigger Updater ---")
    trigger_text = input("Enter the new trigger word or sentence: ").strip()
    if not trigger_text:
        print("Error: Trigger text cannot be empty.")
        return

    # Clean old samples
    for f in os.listdir(OUTPUT_DIR):
        if f.endswith(".wav"):
            os.remove(os.path.join(OUTPUT_DIR, f))

    print(f"\nWe will record {SAMPLES} samples of: '{trigger_text}'")
    print(f"Speak naturally. Each recording lasts {DURATION} seconds.\n")

    all_recordings = []

    for i in range(SAMPLES):
        input(f"Sample {i+1}/{SAMPLES}: Press Enter then say '{trigger_text}'...")
        print("RECORDING...")
        recording = sd.rec(int(DURATION * FS), samplerate=FS, channels=1, dtype='float32')
        sd.wait()
        print("DONE.\n")
        
        # Save WAV
        wav_path = os.path.join(OUTPUT_DIR, f"sample_{i+1}.wav")
        wav.write(wav_path, FS, recording)
        all_recordings.append(recording.flatten())

    print("Processing samples and generating fingerprints...")
    
    all_fingerprints = []
    for recording in all_recordings:
        # Extract MFCCs
        mfcc = librosa.feature.mfcc(y=recording, sr=FS, n_mfcc=13)
        mean_mfcc = np.mean(mfcc, axis=1)
        norm = np.linalg.norm(mean_mfcc)
        if norm > 1e-6:
            all_fingerprints.append(mean_mfcc / norm)

    if not all_fingerprints:
        print("Error: Could not extract features from any samples.")
        return

    # Save primary pkl
    with open(PKL_FILE, "wb") as f:
        pickle.dump(all_fingerprints, f)
    
    # Save named copy (sanitize filename)
    safe_name = "".join([c for c in trigger_text if c.isalnum() or c in (' ', '-', '_')]).strip().replace(" ", "_")
    named_pkl = os.path.join(BASE_DIR, f"{safe_name}.pkl")
    shutil.copy(PKL_FILE, named_pkl)

    print(f"\nSuccess!")
    print(f"Primary fingerprint updated: {PKL_FILE}")
    print(f"Named copy created: {named_pkl}")
    print(f"Trigger set to: '{trigger_text}'")

if __name__ == "__main__":
    record_and_fingerprint()
