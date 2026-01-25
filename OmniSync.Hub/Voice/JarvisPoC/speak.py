import os
import sys
import argparse

# Add supertonic/py to path
BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PY_DIR = os.path.join(BASE_DIR, "supertonic", "py")
sys.path.append(PY_DIR)

import soundfile as sf
from helper import load_text_to_speech, load_voice_style

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("text", help="Text to speak")
    parser.add_argument("--voice", default="M1", help="Voice style (M1-M5, F1-F5)")
    args = parser.parse_args()

    onnx_dir = os.path.join(BASE_DIR, "supertonic", "assets", "onnx")
    voice_style_path = os.path.join(BASE_DIR, "supertonic", "assets", "voice_styles", f"{args.voice}.json")
    
    print(f"Jarvis Speaking: {args.text}")
    
    # 1. Load
    tts = load_text_to_speech(onnx_dir, use_gpu=False) # GPU is buggy on your sys currently, CPU is fast anyway
    style = load_voice_style([voice_style_path])
    
    # 2. Synthesize
    wav, duration = tts(args.text, "en", style, total_step=5, speed=1.05)
    
    # 3. Save & Play
    import sounddevice as sd
    output_path = "JarvisPoC/response.wav"
    w = wav[0, : int(tts.sample_rate * duration[0].item())]
    sf.write(output_path, w, tts.sample_rate)
    
    # Play internally
    print("Playing response...")
    sd.play(w, tts.sample_rate)
    sd.wait()

if __name__ == "__main__":
    main()
