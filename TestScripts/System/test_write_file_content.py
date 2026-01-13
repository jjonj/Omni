import asyncio
import logging
import os
from signalrcore.hub_connection_builder import HubConnectionBuilder
import sys
import uuid

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger("TestWriteFile")

class WriteFileBot:
    def __init__(self, hub_url, api_key):
        self.hub_url = hub_url
        self.api_key = api_key
        self.connection = None
        self.loop = None
        self.future = None
        # Use absolute path
        user_profile = os.environ.get("USERPROFILE")
        self.filename = os.path.join(user_profile, f"test_write_hub_{uuid.uuid4().hex[:8]}.txt")
        self.content = "This is a test content from Python Test Script."
        
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
        
        try:
            self.connection.start()
            logger.info("Connected to Hub. Waiting for connection to stabilize...")
            await asyncio.sleep(2) 
            
            # Authenticate
            self.connection.send("Authenticate", [self.api_key])
            logger.info("Authentication sent.")
            await asyncio.sleep(1)
            
            # Debug: Call ExecuteCommand to see if it logs
            logger.info("Calling ExecuteCommand for debug...")
            self.connection.send("ExecuteCommand", ["echo hello"])
            await asyncio.sleep(1)

            # Call WriteFileContent
            logger.info(f"Writing file: {self.filename}")
            
            try:
                self.connection.send("WriteFileContent", [self.filename, self.content])
                logger.info("WriteFileContent sent.")
            except Exception as ex:
                logger.error(f"Error sending WriteFileContent: {ex}")
            
            # Give it a moment to write
            await asyncio.sleep(2)
            
            # Verify file exists locally (assuming we are on the same machine and default browse path is UserProfile)
            user_profile = os.environ.get("USERPROFILE")
            full_path = os.path.join(user_profile, self.filename)
            
            if os.path.exists(full_path):
                with open(full_path, "r") as f:
                    read_content = f.read()
                
                if read_content == self.content:
                    logger.info("SUCCESS: File written and content matches.")
                    # Cleanup
                    os.remove(full_path)
                    logger.info("Cleanup: File removed.")
                    sys.exit(0)
                else:
                    logger.error(f"FAILURE: Content mismatch. Expected '{self.content}', got '{read_content}'")
                    sys.exit(1)
            else:
                # Try checking if it wrote to CWD (if browse root is weird)
                cwd_path = os.path.join(os.getcwd(), self.filename)
                if os.path.exists(cwd_path):
                     logger.info("SUCCESS: File written to CWD.")
                     os.remove(cwd_path)
                     sys.exit(0)
                else:
                    logger.error(f"FAILURE: File {full_path} not found.")
                    sys.exit(1)

        except Exception as e:
            logger.error(f"FAILURE: An error occurred: {e}")
            sys.exit(1)
        finally:
            self.connection.stop()

if __name__ == "__main__":
    # Use 127.0.0.1 to avoid localhost IPv6 issues
    bot = WriteFileBot("http://127.0.0.1:5000/signalrhub", "test_api_key")
    asyncio.run(bot.start())
