import os
import sys
import subprocess

# Add the supertonic/py directory to sys.path so we can import helper
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PY_DIR = os.path.join(BASE_DIR, "supertonic", "py")
sys.path.append(PY_DIR)

import soundfile as sf
from helper import load_text_to_speech, timer, sanitize_filename, load_voice_style

def main():
    onnx_dir = os.path.join(BASE_DIR, "supertonic", "assets", "onnx")
    voice_styles_dir = os.path.join(BASE_DIR, "supertonic", "assets", "voice_styles")
    save_dir = os.path.join(BASE_DIR, "outputs_supertonic")
    
    # Defaults
    text = "Hello! I am Supertonic, a blazingly fast text to speech system running locally on your device."
    voice_name = "M1"
    
    print("=== Supertonic TTS ===")
    user_text = input(f"Enter text (default: '{text}'): ").strip()
    if user_text:
        text = user_text
        
    voice_style_path = os.path.join(voice_styles_dir, f"{voice_name}.json")
    
    # 1. Load TTS
    tts = load_text_to_speech(onnx_dir, use_gpu=True)
    
    # 2. Load Style
    style = load_voice_style([voice_style_path])
    
    # 3. Generate
    print(f"Generating audio for: '{text}'")
    with timer("Inference"):
        wav, duration = tts(text, "en", style, total_step=5, speed=1.05)
        
    # 4. Save
    if not os.path.exists(save_dir):
        os.makedirs(save_dir)
        
    fname = f"{sanitize_filename(text, 20)}.wav"
    output_path = os.path.join(save_dir, fname)
    
    # wav shape is [bsz, T]
    w = wav[0, : int(tts.sample_rate * duration[0].item())]
    sf.write(output_path, w, tts.sample_rate)
    
    print(f"\nSUCCESS! Audio saved to: {output_path}")
    os.startfile(output_path) # Open it automatically

if __name__ == "__main__":
    main()
