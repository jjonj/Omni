import ctypes
import ctypes.wintypes
import asyncio
import time
import logging
import subprocess
from signalrcore.hub_connection_builder import HubConnectionBuilder

# --- CONFIGURATION ---
HUB_URL = "http://localhost:5000/signalrhub"
API_KEY = "test_api_key"
# ---------------------

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

def get_foreground_pid():
    hwnd = ctypes.windll.user32.GetForegroundWindow()
    if not hwnd:
        return 0
    pid = ctypes.wintypes.DWORD()
    ctypes.windll.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    return pid.value

def focus_via_powershell(workspace_name):
    """
    Attempts to focus WindowsTerminal if its title contains the workspace name.
    """
    logging.info(f"Attempting direct PowerShell focus for workspace '{workspace_name}'...")
    
    ps_script = f"""
    $ws = "{workspace_name}"
    $proc = Get-Process WindowsTerminal -ErrorAction SilentlyContinue | Where-Object {{ $_.MainWindowTitle -like "*($ws)*" }}
    if (!$proc) {{
        # Try finding ANY WindowsTerminal if specific one fails
        $proc = Get-Process WindowsTerminal -ErrorAction SilentlyContinue | Select-Object -First 1
    }}
    
    if ($proc) {{
        $wshell = New-Object -ComObject WScript.Shell
        $wshell.AppActivate($proc.Id) | Out-Null
        exit 0
    }} else {{
        exit 1
    }}
    """
    try:
        result = subprocess.run(["powershell", "-Command", ps_script], capture_output=True)
        return result.returncode == 0
    except Exception as e:
        logging.error(f"PowerShell execution failed: {e}")
        return False

class FocusTester:
    def __init__(self):
        self.connection_started = False
        self.sessions = []
        self.sessions_event = asyncio.Event()
        self.hub = None

    def on_open(self):
        self.connection_started = True

    def on_receive_sessions(self, args):
        self.sessions = args[0]
        self.sessions_event.set()

    async def run(self):
        self.hub = HubConnectionBuilder()\
            .with_url(HUB_URL)\
            .configure_logging(logging.ERROR)\
            .build()

        self.hub.on_open(self.on_open)
        self.hub.on("ReceiveAiSessions", self.on_receive_sessions)

        logging.info(f"Connecting to {HUB_URL}...")
        self.hub.start()

        for _ in range(5):
            if self.connection_started: break
            await asyncio.sleep(1)
        
        if not self.connection_started:
            logging.error("Failed to connect to Hub.")
            return

        self.hub.send("Authenticate", [API_KEY])
        await asyncio.sleep(1)
        self.hub.send("GetAiSessions", [])
        
        try:
            await asyncio.wait_for(self.sessions_event.wait(), timeout=5)
        except asyncio.TimeoutError:
            logging.error("Timed out waiting for sessions.")
            self.hub.stop()
            return

        if not self.sessions:
            logging.warning("No active CLI sessions found.")
            self.hub.stop()
            return

        logging.info(f"Found {len(self.sessions)} sessions. Testing focus for each...")

        for session in self.sessions:
            target_pid = session['pid']
            workspace = session.get('workspace', 'Unknown')
            name = session.get('name', 'Unknown')
            
            logging.info(f"--- Testing Focus for PID {target_pid} ({name} | {workspace}) ---")
            
            self.hub.send("FocusAiSession", [target_pid])
            await asyncio.sleep(3)
            
            focused_pid = get_foreground_pid()
            logging.info(f"Focused PID: {focused_pid}")
            
            # WindowsTerminal PID is 12908.
            # We succeed if we land on 12908 OR if we land on the target PID itself
            if focused_pid == 12908 or focused_pid == target_pid:
                logging.info(f"RESULT: Focus SUCCESS for {name}")
            else:
                logging.info(f"RESULT: Focus FAILED for {name} (Landed on {focused_pid})")
            
            await asyncio.sleep(1)

        self.hub.stop()

if __name__ == "__main__":
    asyncio.run(FocusTester().run())
