#!/usr/bin/env python3
"""
athena.cli.save - Quicksave Session Checkpoint
"""

import sys
from datetime import datetime
from pathlib import Path
from typing import Optional


def find_current_session(logs_dir: Path) -> Optional[Path]:
    """Find the most recent session log for today."""
    today = datetime.now().strftime("%Y-%m-%d")
    sessions = sorted(logs_dir.glob(f"{today}-session-*.md"), reverse=True)
    return sessions[0] if sessions else None


def run_quicksave(summary: str, bullets: Optional[list[str]] = None, project_root: Optional[Path] = None) -> bool:
    """
    Append a checkpoint to the current session log.
    If no session log exists, create one.
    """
    if project_root is None:
        # Auto-discover project root
        current = Path.cwd()
        for parent in [current] + list(current.parents):
            if (parent / ".omni").exists():
                project_root = parent
                break
            if (parent / ".athena").exists() or (parent / ".athena_root").exists():
                project_root = parent
                break
            if (parent / "pyproject.toml").exists() or (parent / ".git").exists():
                project_root = parent
                break
        else:
            project_root = current

    # Check multiple possible session log locations
    # Prefer .omni/athena/session_logs
    logs_dir = project_root / ".omni" / "athena" / "session_logs"
    if not logs_dir.exists():
        # Fallback to check if legacy exists, else create the new one
        legacy_dir = project_root / ".athena" / "session_logs"
        if legacy_dir.exists():
            logs_dir = legacy_dir
        else:
            logs_dir.mkdir(parents=True, exist_ok=True)

    session_file = find_current_session(logs_dir)

    if not session_file:
        # Create a fresh session log for today
        today = datetime.now().strftime("%Y-%m-%d")
        session_id = f"{today}-session-01"
        session_file = logs_dir / f"{session_id}.md"
        
        initial_content = f"# Session Log: {session_id}\n\n> **Created**: {datetime.now().isoformat()}\n> **Status**: Active\n\n(Auto-initialized via quicksave)\n"
        session_file.write_text(initial_content, encoding="utf-8")
        print(f"   📝 Initialized new session: {session_file.name}")

    timestamp = datetime.now().strftime("%H:%M")
    checkpoint = f"\n\n### [Checkpoint {timestamp}]\n{summary}\n"
    if bullets:
        for bullet in bullets:
            checkpoint += f"- {bullet}\n"

    with open(session_file, "a", encoding="utf-8") as f:
        f.write(checkpoint)

    print(f"(OK) Quicksave [{timestamp}] -> {session_file.name}")
    return True


if __name__ == "__main__":
    if len(sys.argv) > 1:
        msg = " ".join(sys.argv[1:])
        run_quicksave(msg)
    else:
        print("Usage: python -m athena save <summary>")
