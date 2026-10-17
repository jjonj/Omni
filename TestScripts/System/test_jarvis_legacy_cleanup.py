from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HUB_SRC = ROOT / "OmniSync.Hub" / "src"
RPC = HUB_SRC / "OmniSync.Hub" / "Presentation" / "Hubs" / "RpcApiHub.cs"
SPEC = ROOT / "OmniNotes" / "Inbox" / "Jarvis.md"
LEGACY_DIR = ROOT / "OmniSync.Hub" / "Voice"

legacy_symbols = (
    "JarvisAutoStart",
    "JarvisWorkspace",
    "JarvisSystemContextPath",
    "JarvisModel",
    "LaunchJarvisCommand",
    "TriggerJarvis",
    "JarvisLaunchAction",
    "JarvisPoC",
)

assert not LEGACY_DIR.exists(), f"Legacy voice PoC still exists: {LEGACY_DIR}"
assert SPEC.exists(), f"Canonical Jarvis spec missing: {SPEC}"
assert "Omni — Jarvis Assistant Specification" in SPEC.read_text(encoding="utf-8")

for path in HUB_SRC.rglob("*"):
    if path.is_file() and path.suffix in {".cs", ".xaml", ".json"}:
        text = path.read_text(encoding="utf-8", errors="ignore")
        for symbol in legacy_symbols:
            assert symbol not in text, f"Legacy {symbol} remains in {path}"

assert "SendJarvisTranscript" in RPC.read_text(encoding="utf-8")
print("Jarvis legacy cleanup checks passed.")
