"""Non-destructive build and focused tests for Google Home Voice scenes."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "OmniSync.Hub" / "src"
TEST_PROJECT = SRC / "OmniSync.Hub.Tests" / "OmniSync.Hub.Tests.csproj"
OUTPUT = ROOT / "artifacts" / "voice_scene_test_build"
OUTPUT.mkdir(parents=True, exist_ok=True)

def run(args):
    print("RUN:", " ".join(map(str, args)), flush=True)
    result = subprocess.run(args, cwd=ROOT, check=False)
    print("EXIT:", result.returncode, flush=True)
    return result.returncode

common = [f"-p:OutputPath={OUTPUT}", "--no-restore", "--verbosity", "minimal"]
build_result = run(["dotnet", "build", str(TEST_PROJECT), *common])
if build_result:
    sys.exit(build_result)

test_result = run([
    "dotnet", "test", str(TEST_PROJECT), "--no-build", "--no-restore",
    "--filter", "FullyQualifiedName~VoiceSceneMacroTests",
    "--verbosity", "normal", f"-p:OutputPath={OUTPUT}",
])
sys.exit(test_result)
