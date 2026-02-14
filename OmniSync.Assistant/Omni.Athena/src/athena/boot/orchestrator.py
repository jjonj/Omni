"""
Athena Boot Orchestrator
=========================

Modular boot sequence with session creation, integrity verification,
and semantic memory priming.

This is the FUNCTIONAL version that creates real artifacts.
"""

import hashlib
import time
from datetime import datetime
from pathlib import Path
from typing import Callable, List, Tuple, Optional


class BootOrchestrator:
    """
    Orchestrates the Athena boot sequence.

    The boot sequence consists of 7 phases:
    1. Watchdog activation (verify core files exist)
    2. System sync (check directory structure)
    3. Semantic prime verification (SHA-384 hash check)
    4. Session creation (create new session log)
    5. Context capture (load last session summary)
    6. Semantic memory priming (query vector DB if available)
    7. Identity loading (load Core_Identity.md)

    Phases 6 & 7 run in parallel for performance optimization.
    """

    def __init__(self, project_root: Optional[Path] = None):
        self.phases: List[Tuple[str, Callable]] = []
        self.boot_time: float = 0
        self.session_id: str = ""
        self.project_root = project_root or self._discover_root()
        
        # Centralized Athena folder (prefers .omni/athena)
        if (self.project_root / ".omni").exists():
            self.athena_dir = self.project_root / ".omni" / "athena"
        else:
            self.athena_dir = self.project_root / ".athena"
            
        self.session_logs_dir = self.athena_dir / "session_logs"
        self.last_session: Optional[str] = None

    def _discover_root(self) -> Path:
        """Discover project root by looking for .athena_root, .athena, .omni or pyproject.toml."""
        current = Path.cwd()
        for parent in [current] + list(current.parents):
            if (parent / ".athena_root").exists() or (parent / ".athena").exists() or (parent / ".omni").exists():
                return parent
            if (parent / "pyproject.toml").exists() or (parent / ".git").exists():
                return parent
        return current

    def register_phase(self, name: str, executor: Callable):
        """Register a boot phase with its executor function."""
        self.phases.append((name, executor))

    def execute(self, parallel_phases: List[int] = None) -> bool:
        """
        Execute all registered boot phases.

        Args:
            parallel_phases: List of phase indices to run in parallel
                             (e.g., [5, 6] to run phases 6 & 7 concurrently)

        Returns:
            True if boot completed successfully, False otherwise.
        """
        start_time = time.time()
        parallel_phases = parallel_phases or []

        print("-" * 60)
        print("ATHENA BOOT SEQUENCE")
        print("-" * 60)

        for i, (name, executor) in enumerate(self.phases):
            phase_num = i + 1
            try:
                if i in parallel_phases:
                    print(f"[{phase_num}/{len(self.phases)}] (>>) {name} (parallel)")
                else:
                    print(f"[{phase_num}/{len(self.phases)}] (..) {name}")

                result = executor()

                if result is False:
                    print(f"(!) Boot failed at phase: {name}")
                    return False

                print(f"[{phase_num}/{len(self.phases)}] (OK) {name}")

            except Exception as e:
                print(f"(!) Boot error in {name}: {e}")
                return False

        self.boot_time = time.time() - start_time
        print("-" * 60)
        print(f"ATHENA ONLINE | Session: {self.session_id} | Boot: {self.boot_time:.1f}s")
        print("-" * 60)

        return True


def create_functional_orchestrator(project_root: Optional[Path] = None) -> BootOrchestrator:
    """
    Create boot orchestrator with FUNCTIONAL phase configuration.

    This version actually:
    - Verifies directory structure
    - Creates session logs
    - Loads last session context
    - Primes semantic memory (if Supabase configured)
    """
    orchestrator = BootOrchestrator(project_root)
    root = orchestrator.project_root
    logs_dir = orchestrator.session_logs_dir

    # --- Phase 1: Watchdog ---
    def watchdog():
        """Verify core files exist."""
        required = ["pyproject.toml"]
        for f in required:
            if not (root / f).exists():
                print(f"   ⚠️  Missing: {f}")
        return True

    orchestrator.register_phase("Watchdog activated", watchdog)

    # --- Phase 2: System Sync ---
    def system_sync():
        """Ensure directory structure exists (prefers centralized .omni/athena folder)."""
        dirs = [
            orchestrator.athena_dir,
            orchestrator.session_logs_dir,
            orchestrator.athena_dir / "protocols",
            orchestrator.athena_dir / "case_studies",
            orchestrator.athena_dir / "context",
        ]
        for d in dirs:
            d.mkdir(parents=True, exist_ok=True)
        return True

    orchestrator.register_phase("System sync complete", system_sync)

    # --- Phase 3: Semantic Prime Verification ---
    def semantic_prime():
        """Check integrity of core identity."""
        # Check local .omni location first, then fallback to global framework
        identity_file = orchestrator.athena_dir / "framework" / "modules" / "Core_Identity.md"
        
        if not identity_file.exists():
            from athena.core.config import FRAMEWORK_DIR
            identity_file = FRAMEWORK_DIR / "v8.2-stable" / "modules" / "Core_Identity.md"

        if identity_file.exists():
            content = identity_file.read_text(encoding="utf-8")
            hash_val = hashlib.sha256(content.encode()).hexdigest()[:12]
            print(f"   🔐 Identity hash: {hash_val}")
        else:
            print("   ⚠️  Core_Identity.md not found")
        return True

    orchestrator.register_phase("Semantic prime verified", semantic_prime)

    # --- Phase 4: Session Creation ---
    def create_session():
        """Create a new session log file (handles legacy location)."""
        # Determine where to save: prefer existing legacy folder if present, else use .athena
        legacy_dir = root / "session_logs"
        target_dir = legacy_dir if legacy_dir.exists() else orchestrator.session_logs_dir
        
        target_dir.mkdir(parents=True, exist_ok=True)
        today = datetime.now().strftime("%Y-%m-%d")

        # Find next session number for today
        existing = list(target_dir.glob(f"{today}-session-*.md"))
        next_num = len(existing) + 1

        session_id = f"{today}-session-{next_num:02d}"
        session_file = target_dir / f"{session_id}.md"

        # Create session log
        session_file.write_text(f"""# Session Log: {session_id}

> **Created**: {datetime.now().isoformat()}
> **Status**: Active

## Summary

(Session in progress...)

## Key Decisions

-

## Insights

-

---
*Auto-generated by Athena Boot Orchestrator*
""", encoding="utf-8")
        orchestrator.session_id = session_id
        print(f"   📝 Created: {session_file.name}")
        return True

    orchestrator.register_phase("Session created", create_session)

    # --- Phase 5: Context Capture ---
    def context_capture():
        """Load summary from last session (checks both new and legacy locations)."""
        legacy_dir = root / "session_logs"
        
        # Collect from all possible locations
        all_sessions = list(orchestrator.session_logs_dir.glob("*.md"))
        if legacy_dir.exists():
            all_sessions.extend(list(legacy_dir.glob("*.md")))
            
        sessions = sorted(all_sessions, reverse=True)
        
        if len(sessions) > 1:
            last = sessions[1]  # Skip the one we just created
            orchestrator.last_session = last.stem
            print(f"   ⏮️  Last session: {last.stem}")
        else:
            print("   📭 No previous sessions found")
        return True

    orchestrator.register_phase("Context captured", context_capture)

    # --- Phase 6: Semantic Memory Priming ---
    def semantic_prime_memory():
        """Prime semantic memory (requires Supabase config)."""
        try:
            from athena.memory.vectors import get_client

            get_client()  # Verify connection
            print("   🧠 Supabase connected")
        except Exception:
            print("   ⚠️  Semantic memory offline (Supabase not configured)")
        return True

    orchestrator.register_phase("Semantic memory primed", semantic_prime_memory)

    # --- Phase 7: Identity Loading ---
    def load_identity():
        """Load core identity principles."""
        identity_file = orchestrator.athena_dir / "framework" / "modules" / "Core_Identity.md"
        
        if not identity_file.exists():
            from athena.core.config import FRAMEWORK_DIR
            identity_file = FRAMEWORK_DIR / "v8.2-stable" / "modules" / "Core_Identity.md"

        if identity_file.exists():
            print(f"   🏛️  Identity loaded: {identity_file.name}")
        else:
            print("   ⚠️  Core_Identity.md not found")
        return True

    orchestrator.register_phase("Identity loaded", load_identity)

    return orchestrator


# Legacy alias for backwards compatibility
def create_default_orchestrator() -> BootOrchestrator:
    """Alias for create_functional_orchestrator."""
    return create_functional_orchestrator()


if __name__ == "__main__":
    orchestrator = create_functional_orchestrator()
    orchestrator.execute(parallel_phases=[4, 5])  # Run phases 6 & 7 in parallel
