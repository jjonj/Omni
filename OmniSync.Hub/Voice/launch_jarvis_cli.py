import os
import subprocess
import sys

def launch():
    # Define paths
    voice_dir = os.path.dirname(os.path.abspath(__file__))
    # Hardcode to main
    gemini_cli_dir = r"D:\\SSDProjects\\Tools\\omni-gemini-cli\\main"
    bundle_path = os.path.join(gemini_cli_dir, "bundle", "gemini.js")
    preprompt_path = os.path.join(voice_dir, "MIND", "PrePrompt.md")
    log_path = os.path.join(voice_dir, "jarvis_cli_debug.log")

    if not os.path.exists(bundle_path):
        print(f"Error: Gemini CLI bundle not found at {bundle_path}")
        return

    # Environment variables
    env = os.environ.copy()
    env["GEMINI_SYSTEM_MD"] = preprompt_path
    env["GEMINI_DEBUG_LOG_FILE"] = log_path

    # Command
    # --yolo: Skip confirmation for tool execution
    # --model: Use the specified model
    # --workspace: Set the workspace
    cmd = [
        "node",
        bundle_path,
        "--yolo",
        "--model", "JarvisFast",
        "--workspace", "D:/SSDProjects"
    ]

    print(f"Launching Jarvis CLI...")
    print(f"Workspace: D:/.")
    print(f"Model: JarvisFast")
    print(f"System Prompt: {preprompt_path}")
    print("-" * 40)

    try:
        # Run the command in a new window
        # subprocess.CREATE_NEW_CONSOLE is Windows specific
        print(f"Opening Jarvis CLI in a new window...")
        subprocess.Popen(cmd, env=env, cwd=voice_dir, creationflags=subprocess.CREATE_NEW_CONSOLE)
        print("Launched successfully.")
    except Exception as e:
        print(f"Error launching Jarvis CLI: {e}")

if __name__ == "__main__":
    launch()
