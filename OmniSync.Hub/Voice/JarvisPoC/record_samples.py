import sounddevice as sd
import numpy as np
import scipy.io.wavfile as wav
import os
import time

FS = 16000
DURATION = 1.5 # Seconds per sample
SAMPLES = 10
OUTPUT_DIR = "JarvisPoC/custom_samples"

os.makedirs(OUTPUT_DIR, exist_ok=True)

print(f"--- Jarvis Voice Sampler ---")
print(f"We will record {SAMPLES} samples. Speak naturally.")
print("Wait for the 'RECORDING' prompt for each one.\n")

for i in range(SAMPLES):
    input(f"Sample {i+1}/{SAMPLES}: Press Enter and then say 'Jarvis'...")
    print("RECORDING...")
    recording = sd.rec(int(DURATION * FS), samplerate=FS, channels=1, dtype='float32')
    sd.wait()
    print("DONE.\n")
    
    # Trim silence and save
    path = os.path.join(OUTPUT_DIR, f"jarvis_{i+1}.wav")
    wav.write(path, FS, recording)

print(f"Finished! All samples saved to {OUTPUT_DIR}")
