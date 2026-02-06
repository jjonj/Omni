import subprocess
import os
import sys

# Get HF_TOKEN from environment or prompt
hf_token = os.environ.get("HF_TOKEN")
if not hf_token:
    print("HF_TOKEN environment variable not found.")
    hf_token = input("Please enter your Hugging Face Token: ").strip()
    if not hf_token:
        print("No token provided. Exiting.")
        sys.exit(1)

# Paths
VENV_PYTHON = os.path.join("personaplex", ".venv", "Scripts", "python.exe")
INPUT_WAV = os.path.join("personaplex", "assets", "test", "input_assistant.wav")
OUTPUT_WAV = os.path.join("personaplex", "output.wav")
OUTPUT_JSON = os.path.join("personaplex", "output.json")

# Command
# We use --cpu-offload because 8GB VRAM is not enough for the 7B model at 16-bit
cmd = [
    VENV_PYTHON, "-m", "moshi.offline",
    "--voice-prompt", "NATF2.pt",
    "--input-wav", INPUT_WAV,
    "--seed", "42424242",
    "--output-wav", OUTPUT_WAV,
    "--output-text", OUTPUT_JSON,
    "--cpu-offload"
]

# Environment with token
env = os.environ.copy()
env["HF_TOKEN"] = hf_token

print(f"Running Personaplex offline evaluation on {INPUT_WAV}...")
print("This will download ~15GB of model weights on first run.")
print("It might take a while to load and process...")

try:
    subprocess.run(cmd, env=env, check=True)
    print(f"\nSuccess! Output saved to {OUTPUT_WAV}")
except subprocess.CalledProcessError as e:
    print(f"\nError running Personaplex: {e}")
except KeyboardInterrupt:
    print("\nInterrupted.")
