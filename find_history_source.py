import os

file_path = r"D:\SSDProjects\Tools\omni-gemini-cli\main\packages\cli\src\omni\remoteControl.ts"

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Already reverted to writeRaw in previous step
# Now I need to ensure onHistory is actually used when AppEvent.RemoteHistory is emitted

# Let's check how onRequestRemoteHistory is implemented in the source
# Actually, I can just change emitRemoteResponse to emitRemoteHistory in the bundle
# but it's better to fix the source.

# In remoteControl.ts, there is already an onHistory handler:
# const onHistory = (text: string) => {
#   writeRaw({ type: 'history', text });
# };
# and it's registered:
# appEvents.on(AppEvent.RemoteHistory, onHistory);

# The problem is that OmniHook.tsx (or wherever emitRemoteResponse is) 
# is using AppEvent.RemoteResponse for history.

# Let's find where HISTORY_START is sent in the source.
