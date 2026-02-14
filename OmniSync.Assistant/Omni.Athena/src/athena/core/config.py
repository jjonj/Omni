"""
athena.core.config
==================

Centralized configuration and path discovery.
"""

from pathlib import Path
from typing import Optional
import os


# Global Cache for PROJECT_ROOT
_PROJECT_ROOT_CACHE: Optional[Path] = None


def get_project_root() -> Path:
    """
    Discover project root by looking for '.athena_root', '.athena', '.omni' or 'pyproject.toml'.
    Caches the result after the first call.
    """
    global _PROJECT_ROOT_CACHE
    if _PROJECT_ROOT_CACHE:
        return _PROJECT_ROOT_CACHE

    # Priority 1: Check parents of CWD (User project context)
    current_cwd = Path.cwd()
    for parent in [current_cwd] + list(current_cwd.parents):
        if (parent / ".athena_root").exists() or (parent / ".athena").exists() or (parent / ".omni").exists():
            _PROJECT_ROOT_CACHE = parent
            return parent

    # Priority 2: Check parents of this file (The Athena SDK itself)
    current_file = Path(__file__).resolve()
    for parent in current_file.parents:
        if (parent / "pyproject.toml").exists():
            _PROJECT_ROOT_CACHE = parent
            return parent

    # Fallback to current environment variable
    root = os.getenv("ATHENA_ROOT")
    if root:
        _PROJECT_ROOT_CACHE = Path(root)
        return _PROJECT_ROOT_CACHE

    _PROJECT_ROOT_CACHE = current_cwd
    return _PROJECT_ROOT_CACHE


PROJECT_ROOT = get_project_root()

# === DIRECTORY HIERARCHY ===

# 1. The Athena System (Always stays in Athena-Public repo)
# We assume the SDK is running from within the Athena-Public repo structure
INTERNAL_ROOT = Path(__file__).resolve().parents[3] # Up from src/athena/core/config.py
AGENT_DIR = INTERNAL_ROOT / ".agent"
FRAMEWORK_DIR = INTERNAL_ROOT / ".framework"
PUBLIC_DIR = INTERNAL_ROOT / "Athena-Public"
SCRIPTS_DIR = AGENT_DIR / "scripts"

# 2. Project Metadata (Tucked into the local project's .athena or .omni/athena folder)
# Prefer .omni/athena if .omni exists
if (PROJECT_ROOT / ".omni").exists():
    ATHENA_DIR = PROJECT_ROOT / ".omni" / "athena"
else:
    ATHENA_DIR = PROJECT_ROOT / ".athena"

CONTEXT_DIR = ATHENA_DIR / "context"
MEMORIES_DIR = ATHENA_DIR / "memories"
SESSIONS_DIR = ATHENA_DIR / "session_logs"
MEMORY_DIR = ATHENA_DIR / "memory"
STATE_DIR = AGENT_DIR / "state" # State stays with the agent logic
MANIFEST_PATH = STATE_DIR / "sync_manifest.json"
SYSTEM_LEARNINGS_FILE = MEMORY_DIR / "SYSTEM_LEARNINGS.md"
USER_PROFILE_FILE = MEMORY_DIR / "USER_PROFILE.yaml"
INPUTS_DIR = CONTEXT_DIR / "inputs"

# === UNIFIED MEMORY CONFIGURATION ===
CORE_DIRS = {
    "sessions": SESSIONS_DIR,
    "case_studies": ATHENA_DIR / "case_studies",
    "protocols": ATHENA_DIR / "protocols",
    "capabilities": AGENT_DIR / "skills" / "capabilities",
    "workflows": AGENT_DIR / "workflows",
    "system_docs": FRAMEWORK_DIR / "v8.2-stable" / "modules",
}

# Extended Memory (Silos mapped to logical tables)
EXTENDED_DIRS = [
    (PROJECT_ROOT / "analysis", "case_studies"),
    (PROJECT_ROOT / "Marketing", "system_docs"),
    (PROJECT_ROOT / "proposals", "case_studies"),
    (PROJECT_ROOT / "Winston", "system_docs"),
    (PROJECT_ROOT / "docs" / "audit", "system_docs"),
    (PROJECT_ROOT / "gem_knowledge_base", "system_docs"),
    (PROJECT_ROOT / ".athena", "system_docs"),
    (PROJECT_ROOT / ".projects", "system_docs"),
    (PROJECT_ROOT / "Reflection Essay", "case_studies"),
]

def get_active_memory_paths():
    """Returns a deduplicated list of all active memory directory Paths."""
    paths = [p for p in CORE_DIRS.values() if p.exists()]
    paths.extend([p for p, _ in EXTENDED_DIRS if p.exists()])
    return sorted(list(set(paths)))

# Key Files
TAG_INDEX_PATH = CONTEXT_DIR / "TAG_INDEX.md"
TAG_INDEX_AM_PATH = CONTEXT_DIR / "TAG_INDEX_A-M.md"
TAG_INDEX_NZ_PATH = CONTEXT_DIR / "TAG_INDEX_N-Z.md"
CANONICAL_PATH = CONTEXT_DIR / "CANONICAL.md"

def get_current_session_log() -> Optional[Path]:
    if not SESSIONS_DIR.exists():
        return None
    import re
    pattern = re.compile(r"(\d{4}-\d{2}-\d{2})-session-(\d{2,3})\.md")
    session_files = []
    for f in SESSIONS_DIR.glob("*.md"):
        match = pattern.match(f.name)
        if match:
            date_str, session_num = match.groups()
            session_files.append((date_str, int(session_num), f))
    if not session_files:
        return None
    session_files.sort(key=lambda x: (x[0], x[1]), reverse=True)
    return session_files[0][2]
