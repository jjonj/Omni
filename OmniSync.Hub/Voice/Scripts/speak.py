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
    parser.add_argument("--speed", type=float, default=1.05, help="Playback speed (default 1.05)")
    parser.add_argument("--continue", dest="cont", action="store_true", help="Automatically call get_voice_command.py --skip-wake after speaking.")
    args = parser.parse_args()

    onnx_dir = os.path.join(BASE_DIR, "supertonic", "assets", "onnx")
    voice_style_path = os.path.join(BASE_DIR, "supertonic", "assets", "voice_styles", f"{args.voice}.json")
    
    print(f"Jarvis Speaking: {args.text}")
    import time
    
    # 1. Load
    start_load = time.time()
    tts = load_text_to_speech(onnx_dir, use_gpu=False) # GPU is buggy on your sys currently, CPU is fast anyway
    style = load_voice_style([voice_style_path])
    load_duration = time.time() - start_load
    print(f"[TIMER] TTS Load took {load_duration:.2f}s")
    
    # 2. Synthesize
    start_syn = time.time()
    wav, duration = tts(args.text, "en", style, total_step=5, speed=args.speed)
    syn_duration = time.time() - start_syn
    print(f"[TIMER] TTS Synthesis took {syn_duration:.2f}s")
    
    # 3. Save & Play
    import sounddevice as sd
    import numpy as np
    output_path = os.path.join(BASE_DIR, "Audio", "response.wav")
    w = wav[0, : int(tts.sample_rate * duration[0].item())]
    
    # Add 0.2s silence at the start to prevent hardware clipping
    silence = np.zeros(int(tts.sample_rate * 0.2))
    w_padded = np.concatenate([silence, w])
    
    sf.write(output_path, w_padded, tts.sample_rate)
    
    # Play internally
    print("Playing response...")
    start_play = time.time()
    sd.play(w_padded, tts.sample_rate)
    sd.wait()
    play_duration = time.time() - start_play
    print(f"[TIMER] Playback took {play_duration:.2f}s")

    if args.cont:
        import subprocess
        # BASE_DIR is .../Voice, the script is in .../Voice/Scripts
        cmd_script = os.path.join(BASE_DIR, "Scripts", "get_voice_command.py")
        print(f"Continuing conversation: {cmd_script}")
        # Use run instead of Popen to block until the user is done speaking
        subprocess.run([sys.executable, cmd_script, "--skip-wake"])

if __name__ == "__main__":
    main()
