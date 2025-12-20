import os
import re

GEMINI_CLI_DIR = r"D:\\SSDProjects\\Tools\\gemini-cli"

def modify_file(rel_path, search_pattern, replacement, use_re=False):
    full_path = os.path.join(GEMINI_CLI_DIR, rel_path)
    with open(full_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    if use_re:
        new_content = re.sub(search_pattern, replacement, content, flags=re.MULTILINE)
    else:
        new_content = content.replace(search_pattern, replacement)
    
    if new_content == content:
        print(f"Warning: No changes made to {rel_path}")
    else:
        with open(full_path, 'w', encoding='utf-8') as f:
            f.write(new_content)
        print(f"Successfully modified {rel_path}")

# 1. Modify events.ts
modify_file(
    r"packages\cli\src\utils\events.ts",
    "PasteTimeout = 'paste-timeout',",
    "PasteTimeout = 'paste-timeout',\n  RemotePrompt = 'remote-prompt',\n  RemoteResponse = 'remote-response',"
)
modify_file(
    r"packages\cli\src\utils\events.ts",
    "[AppEvent.PasteTimeout]: never[];",
    "[AppEvent.PasteTimeout]: never[];\n  [AppEvent.RemotePrompt]: string[];\n  [AppEvent.RemoteResponse]: string[];"
)

# 2. Create remoteControl.ts
remote_control_content = r"""
/**
 * @license
 * Copyright 2025 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */

import * as net from 'node:net';
import { appEvents, AppEvent } from './events.js';
import { debugLogger } from '@google/gemini-cli-core';

export function startRemoteControl() {
  const pipeName = `\\.\\pipe\\gemini-cli-${process.pid}`;
  
  const server = net.createServer((socket) => {
    debugLogger.info(`Remote control client connected on ${pipeName}`);
    
    let buffer = '';
    socket.on('data', (data) => {
      buffer += data.toString();
      if (buffer.includes('\n')) {
        const lines = buffer.split('\n');
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (!line.trim()) continue;
          try {
            const msg = JSON.parse(line);
            if (msg.command === 'prompt' && msg.text) {
              appEvents.emit(AppEvent.RemotePrompt, msg.text);
            }
          } catch (e) {
            debugLogger.error(`Failed to parse remote command: ${e}`);
          }
        }
      }
    });

    const onResponse = (text: string) => {
      socket.write(JSON.stringify({ type: 'response', text }) + '\n');
    };

    appEvents.on(AppEvent.RemoteResponse, onResponse);

    socket.on('close', () => {
      appEvents.off(AppEvent.RemoteResponse, onResponse);
      debugLogger.info('Remote control client disconnected');
    });

    socket.on('error', (err) => {
      debugLogger.error(`Remote control socket error: ${err}`);
    });
  });

  server.listen(pipeName, () => {
    debugLogger.info(`Remote control listening on ${pipeName}`);
  });

  process.on('exit', () => {
    server.close();
  });
}
"

remote_control_path = os.path.join(GEMINI_CLI_DIR, r"packages\cli\src\utils\remoteControl.ts")
with open(remote_control_path, 'w', encoding='utf-8') as f:
    f.write(remote_control_content)
print(f"Created packages\cli\src\utils\remoteControl.ts")

# 3. Modify AppContainer.tsx to listen for RemotePrompt
modify_file(
    r"packages\cli\src\ui\AppContainer.tsx",
    "appEvents.on(AppEvent.OpenDebugConsole, openDebugConsole);",
    """appEvents.on(AppEvent.OpenDebugConsole, openDebugConsole);\n    const onRemotePrompt = (text: string) => {\n      handleFinalSubmit(text);\n    };\n    appEvents.on(AppEvent.RemotePrompt, onRemotePrompt);""",
)
modify_file(
    r"packages\cli\src\ui\AppContainer.tsx",
    "appEvents.off(AppEvent.OpenDebugConsole, openDebugConsole);",
    """appEvents.off(AppEvent.OpenDebugConsole, openDebugConsole);\n      appEvents.off(AppEvent.RemotePrompt, onRemotePrompt);""",
)

# 4. Modify useGeminiStream.ts to emit RemoteResponse
modify_file(
    r"packages\cli\src\ui\hooks\useGeminiStream.ts",
    "              if (pendingHistoryItemRef.current) {\n                addItem(pendingHistoryItemRef.current, userMessageTimestamp);\n                setPendingHistoryItem(null);\n              }",
    """              if (pendingHistoryItemRef.current) {\n                if (pendingHistoryItemRef.current.type === 'gemini' || pendingHistoryItemRef.current.type === 'gemini_content') {\n                  appEvents.emit(AppEvent.RemoteResponse, pendingHistoryItemRef.current.text);\n                }\n                addItem(pendingHistoryItemRef.current, userMessageTimestamp);\n                setPendingHistoryItem(null);\n              }""",
)

# 5. Start remote control in gemini.tsx
modify_file(
    r"packages\cli\src\gemini.tsx",
    "import { setupTerminalAndTheme } from './utils/terminalTheme.js';",
    "import { setupTerminalAndTheme } from './utils/terminalTheme.js';\nimport { startRemoteControl } from './utils/remoteControl.js';"
)
modify_file(
    r"packages\cli\src\gemini.tsx",
    "  const version = await getVersion();",
    "  const version = await getVersion();\n  startRemoteControl();"
)
