import sounddevice as sd
import numpy as np
import scipy.io.wavfile as wav
import os

# Original functioning settings
FS = 16000
CHANNELS = 1
SILENCE_THRESHOLD = 0.01
SILENCE_DURATION = 2.0
MAX_DURATION = 30

OUTPUT_FILE = "JarvisPoC/speech_input.wav"
LOCK_FILE = "JarvisPoC/recording.lock"

def record():
    with open(LOCK_FILE, "w") as f: f.write("1")
    print("Recording started...")
    
    audio_data = []
    silent_chunks = 0
    
    def callback(indata, frames, time, status):
        nonlocal silent_chunks
        audio_data.append(indata.copy())
        energy = np.sqrt(np.mean(indata**2))
        if energy < SILENCE_THRESHOLD:
            silent_chunks += 1
        else:
            silent_chunks = 0

    with sd.InputStream(samplerate=FS, channels=CHANNELS, callback=callback, blocksize=int(FS * 0.1)):
        import time
        start = time.time()
        while True:
            if (silent_chunks * 0.1) > SILENCE_DURATION or (time.time() - start) > MAX_DURATION:
                break
            time.sleep(0.1)

    full_audio = np.concatenate(audio_data, axis=0)
    wav.write(OUTPUT_FILE, FS, full_audio)
    if os.path.exists(LOCK_FILE): os.remove(LOCK_FILE)
    print("Recording saved.")

if __name__ == "__main__":
    record()