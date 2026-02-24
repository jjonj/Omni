"""
athena.mcp_server
=================

MCP Tool Server for Project Athena.
Exposes core capabilities (search, quicksave, health, session) as
standardized MCP tools, consumable by any MCP-compatible client.

Transport: stdio (default), SSE (optional via --sse flag).

Usage:
    # stdio (for IDE integration like Antigravity / Claude Desktop)
    python -m athena.mcp_server

    # SSE (for remote / multi-client access)
    python -m athena.mcp_server --sse --port 8765
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime

from fastmcp import FastMCP

from athena.core.permissions import (
    get_permissions,
    PermissionDenied,
    SecretModeViolation,
    Permission,
    Sensitivity,
)

# ---------------------------------------------------------------------------
# Server Init
# ---------------------------------------------------------------------------

mcp = FastMCP(
    name="omni-assistant",
    version="1.1.0",
    instructions=(
        "Project Omni Assistant MCP Server — a sovereign personal intelligence "
        "infrastructure. Use these tools to search memory, save checkpoints, "
        "check system health, and manage sessions.\n\n"
        "All tools are gated by the Permissioning Layer. Use permission_status "
        "to see what's accessible. Use set_secret_mode to toggle demo mode."
    ),
)

logger = logging.getLogger("omni-assistant.mcp")

# ---------------------------------------------------------------------------
# TOOL: omni:search (formerly smart_search)
# ---------------------------------------------------------------------------


@mcp.tool(
    name="omni:search",
    tags={"read", "memory", "search"},
)
def omni_search(
    query: str,
    limit: int = 10,
    strict: bool = False,
    rerank: bool = False,
) -> dict:
    """
    Search Omni Assistant's knowledge base using hybrid RAG (Canonical + Tags +
    Vectors + GraphRAG + SQLite + Filenames) with RRF fusion.
    This is a 'Global Brain Search' that queries everything: code, filenames,
    session logs, and vector memory.

    Args:
        query: The search query string.
        limit: Maximum number of results to return (default 10).
        strict: If True, filter out low-confidence results.
        rerank: If True, apply LLM-based reranking to top candidates.

    Returns:
        dict with 'results' (list of matches) and 'meta' (query info).
    """
    from athena.tools.search import run_search
    from athena.core.governance import get_governance

    # Permission gate
    perms = get_permissions()
    perms.gate("omni:search")

    # Governance: Mark search as performed
    get_governance().mark_search_performed(query)

    # Capture results via json_output mode
    import io

    old_stdout = sys.stdout
    sys.stdout = buffer = io.StringIO()

    try:
        run_search(
            query=query,
            limit=limit,
            strict=strict,
            rerank=rerank,
            json_output=True,
        )
        output = buffer.getvalue()
    finally:
        sys.stdout = old_stdout

    # Parse the JSON output
    try:
        results = json.loads(output)
    except json.JSONDecodeError:
        results = {"raw_output": output}

    return {
        "results": results if isinstance(results, list) else results,
        "meta": {
            "query": query,
            "limit": limit,
            "strict": strict,
            "rerank": rerank,
            "timestamp": datetime.now().isoformat(),
        },
    }


# ---------------------------------------------------------------------------
# TOOL: omni:research (formerly agentic_search)
# ---------------------------------------------------------------------------


@mcp.tool(
    name="omni:research",
    tags={"read", "memory", "search", "admin"},
)
def omni_research(
    query: str,
    limit: int = 10,
    validate: bool = True,
) -> dict:
    """
    Agentic RAG v2 — Multi-step query decomposition with parallel search
    and cosine validation. Use this for complex, multi-part queries.

    Pipeline: Decompose → Parallel Retrieve → Validate → Synthesize

    Args:
        query: Complex search query (e.g. "trading risk protocols and case studies").
        limit: Maximum number of results to return (default 10).
        validate: If True, validate results via cosine similarity against original query.

    Returns:
        dict with 'results', 'sub_queries', 'decomposed', and 'meta'.
    """
    from athena.tools.agentic_search import agentic_search as _agentic_search

    # Permission gate
    perms = get_permissions()
    perms.gate("omni:research")

    result = _agentic_search(query=query, limit=limit, validate=validate)

    return {
        "results": [r.to_dict() for r in result["results"]],
        "sub_queries": result["sub_queries"],
        "decomposed": result["decomposed"],
        "meta": {
            **result["meta"],
            "timestamp": datetime.now().isoformat(),
        },
    }


# ---------------------------------------------------------------------------
# TOOL: omni:quicksave (formerly quicksave)
# ---------------------------------------------------------------------------


@mcp.tool(
    name="omni:quicksave",
    tags={"write", "session", "checkpoint"},
)
def omni_quicksave(
    summary: str,
    bullets: list[str] | None = None,
) -> dict:
    """
    Save a checkpoint to the current session log. Appends a timestamped
    block with a summary and optional bullet points.

    Args:
        summary: Brief description of what was accomplished/decided.
        bullets: Optional list of specific items to record.

    Returns:
        dict with 'status', 'log_file', and 'timestamp'.
    """
    from athena.sessions import append_checkpoint
    from athena.core.governance import get_governance

    # Permission gate
    perms = get_permissions()
    perms.gate("omni:quicksave")

    # Governance: Check Triple-Lock compliance
    gov = get_governance()
    semantic = gov._state.get("semantic_search_performed", False)
    web = gov._state.get("web_search_performed", False)

    violation = None
    if not (semantic and web):
        missing = []
        if not semantic:
            missing.append("Semantic Search")
        if not web:
            missing.append("Web Research")
        violation = f"TRIPLE-LOCK VIOLATION: Missing: {', '.join(missing)}"

    gov.verify_exchange_integrity()  # Reset state

    try:
        log_path = append_checkpoint(summary, bullets)
        return {
            "status": "ok",
            "log_file": str(log_path),
            "timestamp": datetime.now().isoformat(),
            "governance": violation or "COMPLIANT",
        }
    except FileNotFoundError as e:
        return {
            "status": "error",
            "error": str(e),
            "hint": "No active session. Run boot first.",
        }


# ---------------------------------------------------------------------------
# TOOL: omni:status (formerly health_check)
# ---------------------------------------------------------------------------


@mcp.tool(
    name="omni:status",
    tags={"read", "system", "health"},
)
def omni_status() -> dict:
    """
    Run a health audit of Omni Assistant's core services (Vector API, Database).

    Returns:
        dict with check results for each subsystem.
    """
    from athena.core.health import HealthCheck

    # Permission gate
    get_permissions().gate("omni:status")

    vector = HealthCheck.check_vector_api()
    db = HealthCheck.check_database()

    return {
        "vector_api": vector,
        "database": db,
        "overall": "PASS" if (vector["status"] == "PASS" and db["status"] == "PASS") else "FAIL",
        "timestamp": datetime.now().isoformat(),
    }


# ---------------------------------------------------------------------------
# TOOL: omni:recall (formerly recall_session)
# ---------------------------------------------------------------------------


@mcp.tool(
    name="omni:recall",
    tags={"read", "session", "memory"},
)
def omni_recall(lines: int = 50) -> dict:
    """
    Retrieve the most recent session log content.

    Args:
        lines: Number of lines from the end of the log to return (default 50).

    Returns:
        dict with session file path and recent content.
    """
    from athena.sessions import recall_last_session

    # Permission gate
    perms = get_permissions()
    perms.gate("omni:recall")

    log_path = recall_last_session()

    if not log_path or not log_path.exists():
        return {
            "status": "error",
            "error": "No active session log found.",
        }

    content = log_path.read_text(encoding="utf-8")
    content_lines = content.splitlines()

    # Return the last N lines
    tail = content_lines[-lines:] if len(content_lines) > lines else content_lines
    tail_text = "\n".join(tail)

    # Redact if in secret mode
    if perms.secret_mode:
        tail_text = perms.redact(tail_text)

    return {
        "status": "ok",
        "session_file": str(log_path),
        "session_id": log_path.stem,
        "total_lines": len(content_lines),
        "content": tail_text,
    }


# ---------------------------------------------------------------------------
# TOOL: omni:governance_status (formerly governance_status)
# ---------------------------------------------------------------------------


@mcp.tool(
    name="omni:governance_status",
    tags={"read", "system", "governance"},
)
def omni_governance_status() -> dict:
    """
    Check the current Triple-Lock governance state. Shows whether semantic
    search and web search have been performed in the current exchange.

    Returns:
        dict with governance state and integrity score.
    """
    from athena.core.governance import get_governance

    # Permission gate
    get_permissions().gate("omni:governance_status")

    gov = get_governance()
    state = gov._state.copy()

    return {
        "semantic_search_performed": state.get("semantic_search_performed", False),
        "web_search_performed": state.get("web_search_performed", False),
        "integrity_score": gov.get_integrity_score(),
        "compliant": state.get("semantic_search_performed", False)
        and state.get("web_search_performed", False),
        "timestamp": datetime.now().isoformat(),
    }


# ---------------------------------------------------------------------------
# TOOL: omni:memory_paths (formerly list_memory_paths)
# ---------------------------------------------------------------------------


@mcp.tool(
    name="omni:memory_paths",
    tags={"read", "system", "config"},
)
def omni_memory_paths() -> dict:
    """
    List all active memory directories that Omni Assistant searches over.
    Useful for understanding what knowledge domains are indexed.

    Returns:
        dict with core and extended memory paths.
    """
    from athena.core.config import (
        CORE_DIRS,
        EXTENDED_DIRS,
        get_active_memory_paths,
    )

    # Permission gate
    get_permissions().gate("omni:memory_paths")

    core = {k: str(v) for k, v in CORE_DIRS.items()}
    extended = [{"path": str(p), "maps_to": t} for p, t in EXTENDED_DIRS]
    active = [str(p) for p in get_active_memory_paths()]

    return {
        "core_directories": core,
        "extended_directories": extended,
        "active_count": len(active),
    }


# ---------------------------------------------------------------------------
# TOOL: omni:browser_refresh
# ---------------------------------------------------------------------------


@mcp.tool(
    name="omni:browser_refresh",
    tags={"write", "system", "browser"},
)
def omni_browser_refresh(url: str | None = None) -> dict:
    """
    Force a browser refresh on all connected clients.
    If a URL is provided, only pages matching that URL will refresh.
    Works extensionless via DevSync or via the Omni Chrome Extension.

    Args:
        url: Optional URL substring to match (e.g. 'index.html').
    """
    import subprocess
    import os

    # Permission gate
    get_permissions().gate("omni:browser_refresh")

    # Use the omni_cli_script.py to send the command to the Hub
    # Root: D:\SSDProjects\Omni
    project_root = "D:\\SSDProjects\\Omni"
    cli_path = os.path.join(project_root, "OmniSync.Cli", "omni_cli_script.py")
    
    command = f"refresh_browser"
    if url:
        command += f' "{url}"'

    try:
        # Run the CLI script to send the command
        subprocess.run(
            ["python", cli_path, command],
            capture_output=True,
            text=True,
            check=True
        )
        return {
            "status": "ok",
            "message": f"Refresh command sent for {url or 'active tab'}.",
            "timestamp": datetime.now().isoformat(),
        }
    except Exception as e:
        return {
            "status": "error",
            "error": str(e)
        }


# ---------------------------------------------------------------------------
# RESOURCE: session_log (current)
# ---------------------------------------------------------------------------


@mcp.resource(
    uri="omni://session/current",
    name="Current Session Log",
    description="The full content of the active session log file.",
)
def current_session_resource() -> str:
    """Return the full current session log as a resource."""
    from athena.sessions import recall_last_session

    log_path = recall_last_session()
    if not log_path or not log_path.exists():
        return "No active session."
    return log_path.read_text(encoding="utf-8")


# ---------------------------------------------------------------------------
# RESOURCE: canonical memory
# ---------------------------------------------------------------------------


@mcp.resource(
    uri="omni://memory/canonical",
    name="Canonical Memory",
    description="The Canonical Memory (CANONICAL.md) — Omni Assistant's constitution.",
)
def canonical_memory_resource() -> str:
    """Return the Canonical Memory content."""
    from athena.core.config import CANONICAL_PATH

    if not CANONICAL_PATH.exists():
        return "CANONICAL.md not found."

    content = CANONICAL_PATH.read_text(encoding="utf-8")

    # Redact in secret mode
    perms = get_permissions()
    if perms.secret_mode:
        content = perms.redact(content)

    return content


# ---------------------------------------------------------------------------
# TOOL: omni:set_secret_mode (formerly set_secret_mode)
# ---------------------------------------------------------------------------


@mcp.tool(
    name="omni:set_secret_mode",
    tags={"admin", "security", "mode"},
)
def omni_set_secret_mode(enabled: bool) -> dict:
    """
    Toggle Secret Mode (demo/external mode). When active, only PUBLIC
    tools are accessible and sensitive content is redacted.

    Args:
        enabled: True to activate secret mode, False to deactivate.

    Returns:
        dict with mode state and list of blocked tools.
    """
    perms = get_permissions()
    return perms.set_secret_mode(enabled)


# ---------------------------------------------------------------------------
# TOOL: omni:permission_status (formerly permission_status)
# ---------------------------------------------------------------------------


@mcp.tool(
    name="omni:permission_status",
    tags={"read", "system", "security"},
)
def omni_permission_status() -> dict:
    """
    Show the current permission state: caller level, secret mode,
    accessible/blocked tools, and tool manifest.

    Returns:
        dict with full permission state and tool manifest.
    """
    perms = get_permissions()
    status = perms.get_status()
    status["manifest"] = perms.get_tool_manifest()
    return status


# ---------------------------------------------------------------------------
# Entry Point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Omni Assistant MCP Server")
    parser.add_argument("--sse", action="store_true", help="Use SSE transport")
    parser.add_argument("--port", type=int, default=8765, help="SSE port")
    args = parser.parse_args()

    if args.sse:
        mcp.run(transport="sse", port=args.port)
    else:
        mcp.run(transport="stdio")
