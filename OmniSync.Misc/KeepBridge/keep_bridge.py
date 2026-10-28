"""Google Keep -> OmniHub voice inbox bridge (prototype).

"Hey Google, add <text> to my Omni list" puts an item on the Keep list "Omni".
This script watches that list and forwards every new item to OmniHub's voice inbox
(POST http://127.0.0.1:5000/api/voice-inbox). Items the hub accepts are deleted from the list.

Keep has no official API for personal accounts, so this uses the unofficial gkeepapi library,
which needs a one-time Google "master token". Run `setup` once, yourself, to create it.

  python keep_bridge.py setup      one-time: exchange an oauth_token cookie for a master token
  python keep_bridge.py show       print the Omni list
  python keep_bridge.py run        poll the list and forward items to OmniHub (OmniHub starts this)

Credentials are stored in %APPDATA%\\OmniSync\\google_keep.json.
"""
import argparse
import getpass
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

import gkeepapi
import gpsoauth

CONFIG_DIR = os.path.join(os.environ["APPDATA"], "OmniSync")
CREDENTIALS_PATH = os.path.join(CONFIG_DIR, "google_keep.json")
STATE_PATH = os.path.join(CONFIG_DIR, "google_keep_state.json")
LOCK_PATH = os.path.join(CONFIG_DIR, "google_keep_bridge.lock")
HUB_URL = "http://127.0.0.1:5000/api/voice-inbox"
LIST_TITLE = "omni"


def log(message):
    print(f"{time.strftime('%H:%M:%S')} {message}", flush=True)


def acquire_run_lock():
    """Hold an OS-backed lock for the lifetime of a bridge process."""
    os.makedirs(CONFIG_DIR, exist_ok=True)
    handle = open(LOCK_PATH, "a+b")
    if os.path.getsize(LOCK_PATH) == 0:
        handle.write(b"0")
        handle.flush()
    handle.seek(0)
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except (OSError, IOError):
        handle.close()
        return None
    return handle


def load_credentials():
    if not os.path.exists(CREDENTIALS_PATH):
        sys.exit(f"No Keep credentials at {CREDENTIALS_PATH}. Run: python keep_bridge.py setup")
    with open(CREDENTIALS_PATH, encoding="utf-8") as f:
        return json.load(f)


def setup():
    print("1. Open https://accounts.google.com/EmbeddedSetup in a browser and sign in.")
    print("   (The page may keep loading after sign-in; that's fine.)")
    print("2. Press F12 > Storage > Cookies > accounts.google.com, then search for 'oauth_token'.")
    print("   Copy that cookie's value (starts with oauth2_4/).")
    email = input("Google account email: ").strip()
    oauth_token = getpass.getpass("oauth_token (input hidden): ").strip()
    android_id = uuid.uuid4().hex[:16]
    result = gpsoauth.exchange_token(email, oauth_token, android_id)
    master_token = result.get("Token")
    if not master_token:
        sys.exit(f"Token exchange failed: {result.get('Error', result)}")
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CREDENTIALS_PATH, "w", encoding="utf-8") as f:
        json.dump({"email": email, "master_token": master_token, "android_id": android_id}, f, indent=2)
    print(f"Saved master token to {CREDENTIALS_PATH}")
    keep = login()
    print(f"Logged in. Omni list items: {[i.text for i in omni_items(keep)]}")


def login():
    creds = load_credentials()
    keep = gkeepapi.Keep()
    state = None
    if os.path.exists(STATE_PATH):
        try:
            with open(STATE_PATH, encoding="utf-8") as f:
                state = json.load(f)
        except (OSError, ValueError):
            state = None
    keep.authenticate(creds["email"], creds["master_token"], state=state, device_id=creds.get("android_id"))
    return keep


def save_state(keep):
    with open(STATE_PATH, "w", encoding="utf-8") as f:
        json.dump(keep.dump(), f)


def omni_items(keep):
    lists = [n for n in keep.all() if isinstance(n, gkeepapi.node.List) and not n.trashed
             and n.title.strip().lower() == LIST_TITLE]
    return [item for lst in lists for item in lst.items if not item.checked and item.text.strip()]


def item_occurred_at(item):
    timestamps = getattr(item, "timestamps", None)
    created = getattr(timestamps, "created", None)
    return created.isoformat() if created is not None else None


def forward(text, item_id, api_key, is_backlog=False, occurred_at=None):
    body = json.dumps({"text": text, "source": "google-keep", "sourceId": item_id,
                       "isBacklog": is_backlog, "occurredAt": occurred_at}).encode()
    req = urllib.request.Request(HUB_URL, data=body, method="POST",
                                 headers={"Content-Type": "application/json", "X-Omni-Api-Key": api_key})
    # The Hub acknowledges after durable acceptance; execution happens in its background dispatcher.
    with urllib.request.urlopen(req, timeout=10) as resp:
        return resp.status == 200


def show():
    keep = login()
    items = omni_items(keep)
    save_state(keep)
    print(json.dumps([{"id": i.id, "text": i.text} for i in items], ensure_ascii=False, indent=2))


def process_exists(pid):
    if not pid:
        return True
    try:
        if os.name == "nt":
            import ctypes
            handle = ctypes.windll.kernel32.OpenProcess(0x00100000, False, pid)
            if not handle:
                return False
            ctypes.windll.kernel32.CloseHandle(handle)
            return True
        os.kill(pid, 0)
        return True
    except (OSError, PermissionError):
        return False


def watch_parent_exit(parent_pid):
    """Exit promptly if OmniHub dies, even when a Keep network call blocks.

    Rust's piped child stdio can leave listener handles inherited by this
    process; delayed parent polling then keeps the dead Hub's ports occupied.
    """
    if os.name != "nt" or not parent_pid:
        return
    import ctypes
    import threading

    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = (ctypes.c_uint32, ctypes.c_int, ctypes.c_uint32)
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.WaitForSingleObject.argtypes = (ctypes.c_void_p, ctypes.c_uint32)
    kernel.WaitForSingleObject.restype = ctypes.c_uint32
    kernel.CloseHandle.argtypes = (ctypes.c_void_p,)
    kernel.CloseHandle.restype = ctypes.c_int
    handle = kernel.OpenProcess(0x00100000, False, parent_pid)  # SYNCHRONIZE
    if not handle:
        os._exit(0)  # Hub already gone; do not inherit its sockets indefinitely.

    def wait_for_parent():
        result = kernel.WaitForSingleObject(handle, 0xFFFFFFFF)  # INFINITE
        kernel.CloseHandle(handle)
        if result == 0:  # WAIT_OBJECT_0: parent has exited.
            os._exit(0)

    threading.Thread(target=wait_for_parent, daemon=True,
                     name="omnihub-parent-exit").start()


def run(interval, api_key, parent_pid=None):
    watch_parent_exit(parent_pid)
    lock_handle = acquire_run_lock()
    if lock_handle is None:
        log("Another Keep bridge is already running; exiting duplicate process.")
        return
    if parent_pid and not process_exists(parent_pid):
        log(f"Parent Hub PID {parent_pid} is gone; exiting.")
        return
    keep = login()
    first_poll = True
    log(f"Watching Keep list '{LIST_TITLE}' every {interval}s")
    while True:
        if parent_pid and not process_exists(parent_pid):
            log(f"Parent Hub PID {parent_pid} is gone; exiting orphan bridge.")
            return
        try:
            keep.sync()
            changed = False
            hub_unreachable = False
            for item in omni_items(keep):
                try:
                    if forward(item.text.strip(), item.id, api_key, is_backlog=first_poll,
                               occurred_at=item_occurred_at(item)):
                        log(f"Forwarded: {item.text.strip()}")
                        item.delete()
                        changed = True
                except urllib.error.HTTPError as e:
                    if e.code in (401, 403):
                        log(f"Hub rejected authentication (HTTP {e.code}); stopping bridge until configuration is fixed.")
                        return
                    if 400 <= e.code < 500:
                        log(f"Hub rejected item {item.id} with HTTP {e.code}; leaving it in Keep.")
                        continue
                    log(f"Hub HTTP error {e.code}, will retry: {e}")
                    hub_unreachable = True
                    break
                except (urllib.error.URLError, OSError) as e:
                    log(f"Hub not reachable, will retry: {e}")
                    hub_unreachable = True
                    break
            if changed:
                keep.sync()
            save_state(keep)
            if not hub_unreachable:
                first_poll = False
        except Exception as e:  # keep polling through transient Google errors
            log(f"Sync error: {type(e).__name__}: {e}")
            time.sleep(interval * 4)
            try:
                keep = login()
            except Exception as e2:
                log(f"Re-login failed: {e2}")
        time.sleep(interval)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["setup", "show", "run"])
    parser.add_argument("--interval", type=int, default=60,
                        help="Seconds between Keep polls (default: 60)")
    parser.add_argument("--api-key", default=os.environ.get("OMNI_API_KEY", "test_api_key"))
    parser.add_argument("--parent-pid", type=int, default=None,
                        help="Exit if the owning OmniHub process disappears")
    args = parser.parse_args()
    if args.command == "setup":
        setup()
    elif args.command == "show":
        show()
    else:
        run(args.interval, args.api_key, args.parent_pid)


if __name__ == "__main__":
    main()
