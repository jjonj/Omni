import asyncio
import logging
from signalrcore.hub_connection_builder import HubConnectionBuilder
import sys

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("TestFileAccess")

class FileExplorerBot:
    def __init__(self, hub_url, api_key):
        self.hub_url = hub_url
        self.api_key = api_key
        self.connection = None
        self.loop = None
        self.future = None
        self.current_step = "START" # START -> DRIVES -> WINDOWS -> SYSTEM32 -> DONE
        self.found = False
        
    async def start(self):
        self.loop = asyncio.get_running_loop()
        self.future = self.loop.create_future()
        
        self.connection = HubConnectionBuilder() \
            .with_url(self.hub_url) \
            .configure_logging(logging.WARNING) \
            .with_automatic_reconnect({
                "type": "raw",
                "keep_alive_interval": 10,
                "reconnect_interval": 5,
                "max_attempts": 5
            }) \
            .build()

        self.connection.on_open(lambda: logger.info("Connection opened."))
        self.connection.on_close(lambda: logger.info("Connection closed."))
        self.connection.on_error(lambda data: logger.error(f"Connection error: {data.error}"))
        
        # Register callbacks with thread-safe wrappers
        self.connection.on("ReceiveAvailableDrives", lambda x: self.loop.call_soon_threadsafe(self.on_receive_drives, x))
        self.connection.on("ReceiveDirectoryContents", lambda x: self.loop.call_soon_threadsafe(self.on_receive_contents, x))
        self.connection.on("ReceiveError", lambda x: self.loop.call_soon_threadsafe(self.on_error, x))

        try:
            self.connection.start()
            logger.info("Connected to Hub. Waiting for connection to stabilize...")
            await asyncio.sleep(2) # Give it a moment to establish connection fully
            
            # Authenticate
            self.connection.send("Authenticate", [self.api_key])
            logger.info("Authentication sent.")
            await asyncio.sleep(1) # Give it a moment to authenticate

            # Step 1: Get Drives
            logger.info("Step 1: Requesting Drives...")
            self.current_step = "DRIVES"
            self.connection.send("GetAvailableDrives", [])
            
            # Wait for completion or timeout
            await asyncio.wait_for(self.future, timeout=60)
            
            if self.found:
                logger.info("SUCCESS: System32 folder found!")
                sys.exit(0)
            else:
                logger.error("FAILURE: System32 folder not found within logic.")
                sys.exit(1)

        except asyncio.TimeoutError:
            logger.error("FAILURE: Operation timed out (60s).")
            sys.exit(1)
        except Exception as e:
            logger.error(f"FAILURE: An error occurred: {e}")
            sys.exit(1)
        finally:
            self.connection.stop()

    def on_receive_drives(self, drives):
        # Drives is usually a list of objects.
        # Example: [{"name": "C:\\", "path": "C:\\", "isDirectory": true, ...}, ...]
        drives = drives[0] if isinstance(drives, list) and len(drives) == 1 and isinstance(drives[0], list) else drives

        if self.current_step != "DRIVES":
            return
            
        logger.info(f"Received Drives: {len(drives)}")
        # Simple heuristic: Look for C:\
        target_drive = None
        for d in drives:
            # Check keys (case insensitive safely)
            name = d.get("name") or d.get("Name")
            path = d.get("path") or d.get("Path")
            
            if name and "C" in name:
                target_drive = path
                break
        
        if not target_drive and drives:
             # Fallback to first drive
             d = drives[0]
             target_drive = d.get("path") or d.get("Path")

        if target_drive:
            logger.info(f"Step 2: Inspecting Drive {target_drive}...")
            self.current_step = "WINDOWS"
            self.connection.send("ListDirectory", [target_drive])
        else:
            logger.error("No suitable drive found.")
            if not self.future.done():
                self.future.set_exception(Exception("No drives found"))

    def on_receive_contents(self, contents):
        # Contents might be wrapped in a list [contents] due to SignalR params
        contents = contents[0] if isinstance(contents, list) and len(contents) == 1 and isinstance(contents[0], list) else contents

        if self.current_step == "WINDOWS":
            # Looking for "Windows" folder
            logger.info(f"Received {len(contents)} items in drive root.")
            found_windows = False
            windows_path = ""
            
            for item in contents:
                name = item.get("name") or item.get("Name", "")
                path = item.get("path") or item.get("Path", "")
                is_dir = item.get("isDirectory", False) or item.get("IsDirectory", False)
                
                if is_dir and name.lower() == "windows":
                    found_windows = True
                    windows_path = path
                    break
            
            if found_windows:
                logger.info(f"Step 3: Found Windows at {windows_path}. Checking for System32...")
                self.current_step = "SYSTEM32"
                self.connection.send("ListDirectory", [windows_path])
            else:
                logger.error("Windows folder not found on this drive.")
                if not self.future.done():
                    self.future.set_exception(Exception("Windows folder not found"))

        elif self.current_step == "SYSTEM32":
            # Looking for "System32" folder
            logger.info(f"Received {len(contents)} items in Windows folder.")
            found_system32 = False
            
            for item in contents:
                name = item.get("name") or item.get("Name", "")
                is_dir = item.get("isDirectory", False) or item.get("IsDirectory", False)
                
                if is_dir and name.lower() == "system32":
                    found_system32 = True
                    break
            
            if found_system32:
                self.found = True
                if not self.future.done():
                    self.future.set_result(True)
            else:
                logger.error("System32 folder not found in Windows folder.")
                if not self.future.done():
                    self.future.set_exception(Exception("System32 folder not found"))

    def on_error(self, error):
        logger.error(f"Hub Error: {error}")
        # Optionally fail fast
        # if not self.future.done():
        #     self.future.set_exception(Exception(f"Hub Error: {error}"))

if __name__ == "__main__":
    # Use 127.0.0.1 to avoid localhost IPv6 issues, similar to how test_mouse_clicks works (though it used LAN IP)
    bot = FileExplorerBot("http://127.0.0.1:5000/signalrhub", "test_api_key")
    asyncio.run(bot.start())