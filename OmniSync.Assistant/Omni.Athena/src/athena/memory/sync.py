#!/usr/bin/env python3
"""
athena.memory.sync
==================
Core logic for synchronizing workspace content to Supabase pgvector.
Handles chunking, metadata extraction, and batch uploads.
"""

import re
import os
import hashlib
import json
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple
from concurrent.futures import ThreadPoolExecutor, as_completed

# ...

from athena.memory.vectors import get_client, get_embedding

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
MANIFEST_PATH = PROJECT_ROOT / ".agent" / "state" / "sync_manifest.json"


def get_file_hash(content: str) -> str:
    """Calculate MD5 hash of content."""
    return hashlib.md5(content.encode("utf-8")).hexdigest()


def load_manifest() -> Dict:
    """Load sync manifest."""
    if MANIFEST_PATH.exists():
        try:
            return json.loads(MANIFEST_PATH.read_text())
        except:
            return {}
    return {}


def save_manifest(manifest: Dict):
    """Save sync manifest."""
    MANIFEST_PATH.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2))


def Extract_Metadata_Simplistic(content: str) -> Dict:
    """Simple frontmatter extractor."""
    meta = {}
    if content.startswith("---"):
        try:
            end = content.find("---", 3)
            if end != -1:
                yaml_block = content[3:end]
                for line in yaml_block.splitlines():
                    if ":" in line:
                        key, val = line.split(":", 1)
                        meta[key.strip()] = val.strip().strip('"').strip("'")
        except:
            pass
    return meta


def extract_metadata(content: str, filename: str) -> Dict:
    """Extract metadata wrapper."""
    return Extract_Metadata_Simplistic(content)


def chunk_markdown(content: str) -> List[str]:
    """Simple chunking stub (returns full content as list for now)."""
    # For now, we rely on semantic search handling large contexts or truncation at embedding level
    return [content]


def sync_file_to_supabase(
    file_path: Path,
    table_name: str,
    extra_metadata: Dict = None,
    manifest: Dict = None,
    save_on_success: bool = True,
):
    """
    Sync a single file to Supabase.
    Generates embeddings and inserts into the specified table.
    """
    if not file_path.exists():
        return False

    content = file_path.read_text(encoding="utf-8")

    # Delta Check
    current_hash = get_file_hash(content)
    _manifest = manifest if manifest is not None else load_manifest()
    relative_path = str(file_path.relative_to(PROJECT_ROOT))

    if _manifest.get(relative_path) == current_hash:
        # print(f"⏩ Skipping {file_path.name} (unchanged)") # Reduce noise in parallel
        return True

    meta = extract_metadata(content, file_path.name)
    if extra_metadata:
        meta.update(extra_metadata)

    chunks = chunk_markdown(content)
    client = get_client()

    # Check if table supports chunking (legacy schema check)
    # For now, we sync the whole file as one entry to match search functions
    print(f"📡 Syncing {file_path.name} to {table_name}...")

    embedding = get_embedding(content[:30000])  # Cap content for embedding safety
    data = {
        "content": content,
        "embedding": embedding,
        "file_path": str(file_path.relative_to(PROJECT_ROOT)),
        "title": meta.get("title", file_path.name),
    }

    # Add table-specific fields based on schema
    if table_name == "sessions":
        # Extract date from filename YYYY-MM-DD
        date_match = re.search(r"(\d{4}-\d{2}-\d{2})", file_path.name)
        data["date"] = date_match.group(1) if date_match else "2026-01-01"
        data["session_number"] = (
            int(re.search(r"session-(\d+)", file_path.name).group(1))
            if "session-" in file_path.name
            else 1
        )
    elif table_name == "protocols":
        code_match = re.match(r"(\d+)", file_path.name)
        data["code"] = code_match.group(1) if code_match else "000"
        data["name"] = file_path.stem
    elif table_name == "case_studies":
        code_match = re.match(r"(CS-\d+)", file_path.name)
        data["code"] = code_match.group(1) if code_match else file_path.stem
    elif table_name == "capabilities":
        data["title"] = file_path.stem
        if "name" in data:
            del data["name"]
    elif table_name == "frameworks":
        data["name"] = file_path.stem
        data["title"] = meta.get("title", file_path.name)
    elif table_name == "workflows":
        data["name"] = file_path.stem
        if "title" in data:
            del data["title"]
    elif table_name == "knowledge":
        data["title"] = meta.get("title", file_path.name)
    elif table_name == "playbooks":
        data["name"] = file_path.stem
        data["title"] = meta.get("title", file_path.name)
    elif table_name == "entities":
        data["entity_name"] = file_path.stem
    elif table_name == "system_docs":
        data["filename"] = file_path.name
        data["doc_type"] = "system"
        data["title"] = meta.get("title", file_path.name)
    elif table_name == "user_profile":
        data["filename"] = file_path.name
        data["title"] = meta.get("title", file_path.name)
        data["category"] = "general"
    elif table_name == "insights":
        data["filename"] = file_path.name
        data["title"] = meta.get("title", file_path.name)

    # Determine conflict target based on table schema
    conflict_target = "file_path"
    if table_name in ["workflows", "capabilities"]:
        conflict_target = "name"

    try:
        client.table(table_name).upsert(data, on_conflict=conflict_target).execute()
        # Update manifest after success
        _manifest[relative_path] = current_hash
        if save_on_success:
            save_manifest(_manifest)
    except Exception as e:
        # Fallback ONLY for tables known to use 'code' as unique key
        if table_name in ["protocols", "case_studies"] and "code" in str(e).lower():
            print(f"🔄 Retrying {file_path.name} with 'code' conflict resolution...")
            client.table(table_name).upsert(data, on_conflict="code").execute()
            _manifest[relative_path] = current_hash
            if save_on_success:
                save_manifest(_manifest)
        else:
            raise e

    return True


def sync_directory(directory: Path, table_name: str, recursive: bool = True):
    """Sync an entire directory to a Supabase table."""
    if not directory.exists():
        return

    pattern = "**/*.md" if recursive else "*.md"
    files = list(directory.glob(pattern))

    if not files:
        return

    print(f"🚀 Syncing {len(files)} files to '{table_name}' (Double-Parallel Mode)...")
    manifest = load_manifest()

    with ThreadPoolExecutor(max_workers=10) as executor:
        # Submit all tasks
        # save_on_success=False to prevent race conditions on file write
        future_to_file = {
            executor.submit(
                sync_file_to_supabase, f, table_name, manifest=manifest, save_on_success=False
            ): f
            for f in files
        }

        completed = 0
        failed = 0

        for future in as_completed(future_to_file):
            f = future_to_file[future]
            try:
                future.result()
                completed += 1
            except Exception as e:
                print(f"❌ Error syncing {f.name}: {e}")
                failed += 1

    # Save manifest once at the end
    save_manifest(manifest)
    print(f"✅ Sync Complete: {completed} processed, {failed} failed.")
