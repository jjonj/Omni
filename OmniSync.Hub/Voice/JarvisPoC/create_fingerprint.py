import os
import numpy as np
import librosa
import glob
import pickle

SAMPLE_DIR = "JarvisPoC/custom_samples"
OUTPUT_FILE = "JarvisPoC/fingerprint.pkl"

def create_fingerprints():
    print("Extracting features from all 10 samples...")
    files = glob.glob(os.path.join(SAMPLE_DIR, "jarvis_*.wav"))
    
    all_fingerprints = []
    
    for f in files:
        y, sr = librosa.load(f, sr=16000)
        # Extract MFCCs
        mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=13)
        # Mean across time for this specific utterance
        mean_mfcc = np.mean(mfcc, axis=1)
        # Normalize the vector
        norm = np.linalg.norm(mean_mfcc)
        if norm > 1e-6:
            all_fingerprints.append(mean_mfcc / norm)
        
    if not all_fingerprints:
        print("No samples found!")
        return

    with open(OUTPUT_FILE, "wb") as f:
        pickle.dump(all_fingerprints, f)
        
    print(f"Saved {len(all_fingerprints)} fingerprints to {OUTPUT_FILE}")

if __name__ == "__main__":
    create_fingerprints()
