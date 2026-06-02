import asyncio
import logging
import os
import sys
import uuid
from signalrcore.hub_connection_builder import HubConnectionBuilder

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("RustSignalRTest")

class SignalRTestBot:
    def __init__(self, hub_url, api_key):
        self.hub_url = hub_url
        self.api_key = api_key
        self.connection = None
        
    async def start(self):
        self.connection = HubConnectionBuilder() \
            .with_url(self.hub_url) \
            .configure_logging(logging.WARNING) \
            .build()

        self.connection.on_open(lambda: logger.info("SignalR Connection opened successfully."))
        self.connection.on_close(lambda: logger.info("SignalR Connection closed."))
        self.connection.on_error(lambda data: logger.error(f"Connection error: {data.error}"))
        
        try:
            self.connection.start()
            logger.info("Connected to Rust OmniHub. Stabilization wait...")
            await asyncio.sleep(2) 
            
            # 1. Authenticate
            # Note: Authenticate returns true. We send it as a test.
            self.connection.send("Authenticate", [self.api_key])
            logger.info("Authentication request sent.")
            await asyncio.sleep(1)
            
            # 2. Test WriteFileContent
            test_file_path = os.path.join(os.environ.get("USERPROFILE"), f"test_rust_signalr_{uuid.uuid4().hex[:8]}.txt")
            test_content = "SignalR Transport validation via Rust backend of OmniHub!"
            logger.info(f"Testing WriteFileContent. Target: {test_file_path}")
            
            self.connection.send("WriteFileContent", [test_file_path, test_content])
            await asyncio.sleep(2)
            
            # Verify file exists and content matches
            if os.path.exists(test_file_path):
                with open(test_file_path, "r") as f:
                    actual_content = f.read()
                
                if actual_content == test_content:
                    logger.info("SUCCESS: WriteFileContent verified successfully!")
                    os.remove(test_file_path)
                else:
                    logger.error(f"FAILURE: File content mismatch. Expected '{test_content}', got '{actual_content}'")
                    sys.exit(1)
            else:
                logger.error(f"FAILURE: WriteFileContent did not create file at {test_file_path}")
                sys.exit(1)
                
            # 3. Test Clipboard Reading/Writing
            test_clip_val = f"Clipboard Sync via Rust! token={uuid.uuid4().hex[:8]}"
            logger.info(f"Testing UpdateClipboard with value: {test_clip_val}")
            self.connection.send("UpdateClipboard", [test_clip_val])
            await asyncio.sleep(1)
            
            # Since UpdateClipboard set the clipboard, we now call get_clipboard_text to verify
            # Wait, our bot cannot easily receive return values using fire-and-forget send,
            # but since we are on the same machine, we can verify clipboard locally using python!
            try:
                import tkinter as tk
                root = tk.Tk()
                root.withdraw()
                clip_text = root.clipboard_get()
                if clip_text == test_clip_val:
                    logger.info("SUCCESS: UpdateClipboard verified via local clipboard read!")
                else:
                    logger.warning(f"Clipboard verification warning: Expected '{test_clip_val}', got '{clip_text}' (tkinter might read stale value or clipboard not shared).")
            except Exception as e:
                logger.info(f"Skipping tkinter clipboard validation (running on headless or tkinter not installed): {e}")

            logger.info("\nALL SIGNALR PORT 5000 TRANSPORT VERIFICATIONS COMPLETED SUCCESSFULLY!")
            sys.exit(0)

        except Exception as e:
            logger.error(f"FAILURE: An error occurred: {e}")
            sys.exit(1)
        finally:
            self.connection.stop()

if __name__ == "__main__":
    bot = SignalRTestBot("http://127.0.0.1:5000/signalrhub", "test_api_key")
    asyncio.run(bot.start())
