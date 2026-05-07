"""
omni.__main__ — OPC Entry Point
================================

Enables running Omni Project Context (OPC) via: omni (global) or python -m opc

Usage:
    omni init .              # Initialize workspace in current directory
    omni check               # Run system health check
    omni --version           # Show version
    omni --help              # Show help
    omni:sync                # Sync Project File Index
"""

import argparse
import sys
from pathlib import Path

# Load environment variables FIRST
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

def run_check():
    """Run system health diagnostics."""
    print("✦ OMNI PROJECT CONTEXT (OPC) SYSTEM CHECK")
    print("=" * 60)
    
    # Check for .omni marker
    root_marker = Path.cwd() / ".omni"
    if root_marker.exists():
        print("   ✅ Workspace marker (.omni): Found")
    else:
        print("   ⚠️  Workspace marker (.omni): Missing")

    print("\n" + "=" * 60)
    return True

def main():
    parser = argparse.ArgumentParser(
        prog="omni",
        description="Omni Project Context (OPC) — Local Codebase Context for AI",
    )
    parser.add_argument("--version", "-v", action="store_true", help="Show version and exit")
    parser.add_argument(
        "--root",
        type=Path,
        default=None,
        help="Project root directory (auto-detected if not specified)",
    )

    # Subcommands
    subparsers = parser.add_subparsers(dest="command", help="Available commands")

    # init subcommand
    init_parser = subparsers.add_parser("init", help="Initialize a new OPC workspace")
    init_parser.add_argument(
        "target",
        nargs="?",
        type=Path,
        default=None,
        help="Target directory (use '.' for current directory)",
    )

    # check subcommand
    subparsers.add_parser("check", help="Run system health check")

    # omni:context subcommand
    opc_parser = subparsers.add_parser("omni:context", help="Project File Index tools")
    opc_parser.add_argument(
        "omni_context_cmd",
        choices=["sync", "session", "context"],
        help="Project Index command to run",
    )

    # Aliases
    subparsers.add_parser("omni:sync", help="Sync Project File Index")

    args = parser.parse_args()

    if args.version:
        print(f"omni-opc-cli v1.0.0")
        sys.exit(0)

    if args.command == "check":
        run_check()
        sys.exit(0)

    if args.command == "init":
        # Simplified init for OPC: just create .omni
        target = args.target or args.root or Path.cwd()
        omni_dir = target / ".omni"
        omni_dir.mkdir(exist_ok=True)
        (omni_dir / "opc").mkdir(exist_ok=True)
        print(f"Initialized OPC workspace at {target}")
        sys.exit(0)

    if args.command == "omni:sync":
        args.command = "omni:context"
        args.omni_context_cmd = "sync"

    if args.command == "omni:context":
        from .project_index import OpcOrchestrator

        orchestrator = OpcOrchestrator(project_root=args.root)
        if args.omni_context_cmd == "sync":
            print(orchestrator.handle_sync())
        elif args.omni_context_cmd == "session":
            print(orchestrator.handle_session())
        elif args.omni_context_cmd == "context":
            print(orchestrator.handle_context())
        sys.exit(0)

    if not args.command:
        parser.print_help()

if __name__ == "__main__":
    main()
