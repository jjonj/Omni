"""Resolve Pixiv artwork bookmarks by exact Firefox Portable folder name.

Outputs JSON for the local OmniHub page. Does not read artwork contents or change
the browser profile. Nested bookmarks are included; duplicate folder names are
reported as ambiguous.
"""
import json
from contextlib import closing
import os
import sqlite3
import sys
from pathlib import Path
from urllib.parse import urlsplit

PROFILE = Path(os.environ.get(
    "UGOIRA_FIREFOX_PROFILE", r"Y:\FirefoxPortable\Data\profile"))


def artwork_url(url):
    """Only accept artwork URLs supported by the downloader."""
    try:
        parsed = urlsplit(url)
        if parsed.scheme not in ("http", "https") or parsed.hostname not in (
            "pixiv.net", "www.pixiv.net"
        ):
            return None
        segments = parsed.path.strip("/").split("/")
        if len(segments) >= 2 and segments[-2] == "artworks":
            artwork = segments[-1]
            if artwork.isascii() and artwork.isdecimal():
                return f"https://www.pixiv.net/en/artworks/{int(artwork)}"
    except (ValueError, TypeError):
        pass
    return None


def links_in_folder(name, profile=PROFILE):
    db = Path(profile) / "places.sqlite"
    if not db.is_file():
        raise ValueError(f"Firefox bookmark database not found at {db}")
    # A read-only SQLite connection sees Firefox's committed WAL changes.
    with closing(sqlite3.connect("file:" + db.as_posix() + "?mode=ro",
                                 uri=True, timeout=4)) as conn:
        folders = conn.execute(
            "SELECT id FROM moz_bookmarks WHERE type=2 "
            "AND title=? COLLATE NOCASE", (name,)
        ).fetchall()
        if not folders:
            raise ValueError(f'No Firefox bookmark folder named "{name}" was found.')
        if len(folders) != 1:
            raise ValueError(
                f'{len(folders)} Firefox bookmark folders are named "{name}". '
                "Use a unique folder name to select the right one."
            )
        # Follow subfolders recursively; read only URLs, never bookmark descriptions.
        entries = conn.execute(
            """
            WITH RECURSIVE folders(id) AS (
                SELECT ?
                UNION ALL
                SELECT child.id FROM moz_bookmarks child
                JOIN folders parent ON child.parent=parent.id
                WHERE child.type=2
            )
            SELECT moz_places.url FROM moz_bookmarks AS bookmark
            JOIN folders ON bookmark.parent=folders.id
            JOIN moz_places ON bookmark.fk=moz_places.id
            WHERE bookmark.type=1
            ORDER BY bookmark.id
            """, (folders[0][0],)
        ).fetchall()

    seen = set()
    urls = []
    skipped = 0
    for (url,) in entries:
        normalized = artwork_url(url)
        if not normalized:
            skipped += 1
            continue
        if normalized in seen:
            continue
        seen.add(normalized)
        urls.append(normalized)
    return {"folder": name, "links": urls, "totalBookmarks": len(entries),
            "skippedNonPixiv": skipped, "count": len(urls)}


def main():
    try:
        if len(sys.argv) != 2 or not sys.argv[1].strip():
            raise ValueError("Enter a Firefox bookmark folder name.")
        result = links_in_folder(sys.argv[1].strip())
        if not result["links"]:
            raise ValueError(
                f'Folder "{sys.argv[1].strip()}" contains no Pixiv artwork links.'
            )
        print(json.dumps(result, ensure_ascii=False))
    except (ValueError, sqlite3.Error, OSError) as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False))
        sys.exit(1)


if __name__ == "__main__":
    main()

