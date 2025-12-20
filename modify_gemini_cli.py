import os
import re

GEMINI_CLI_DIR = r"D:\\SSDProjects\\Tools\\gemini-cli"

def modify_file(rel_path, search_pattern, replacement, use_re=False):
    full_path = os.path.join(GEMINI_CLI_DIR, rel_path)
    if not os.path.exists(full_path):
        print(f"Error: File not found {full_path}")
        return
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

# 1. Fix useGeminiStream.ts imports
modify_file(
    r"packages\\cli\\src\\ui\\hooks\\useGeminiStream.ts",
    "import { isAtCommand, isSlashCommand } from '../utils/commandUtils.js';",
    "import { appEvents, AppEvent } from '../../utils/events.js';\nimport { isAtCommand, isSlashCommand } from '../utils/commandUtils.js';"
)

# 2. Fix remoteControl.ts debugLogger calls (info -> log)
modify_file(
    r"packages\\cli\\src\\utils\\remoteControl.ts",
    "debugLogger.info",
    "debugLogger.log"
)

# 3. Ensure pipe name is correct in remoteControl.ts
remote_control_content = r'''
/**
 * @license
 * Copyright 2025 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */

import * as net from 'node:net';
import { appEvents, AppEvent } from './events.js';
import { debugLogger } from '@google/gemini-cli-core';

export function startRemoteControl() {
  const pipeName = '\\.\\pipe\\gemini-cli-' + process.pid;

  const server = net.createServer((socket) => {
    debugLogger.log(`Remote control client connected on ${pipeName}`);

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
              debugLogger.log(`Received remote prompt: ${msg.text.substring(0, 50)}...`);
              appEvents.emit(AppEvent.RemotePrompt, msg.text);
            }
          } catch (e) {
            debugLogger.error(`Failed to parse remote command: ${e}`);
          }
        }
      }
    });

    const onResponse = (text: string) => {
      try {
        socket.write(JSON.stringify({ type: 'response', text }) + '\n');
      } catch (e) {
        debugLogger.error(`Failed to write to remote control socket: ${e}`);
      }
    };

    appEvents.on(AppEvent.RemoteResponse, onResponse);

    socket.on('close', () => {
      appEvents.off(AppEvent.RemoteResponse, onResponse);
      debugLogger.log('Remote control client disconnected');
    });

    socket.on('error', (err) => {
      debugLogger.error(`Remote control socket error: ${err}`);
    });
  });

  server.on('error', (err: any) => {
    debugLogger.error(`Remote control server error: ${err}`);
  });

  try {
    server.listen(pipeName, () => {
      debugLogger.log(`Remote control listening on ${pipeName}`);
    });
  } catch (err) {
    debugLogger.error(`Failed to start remote control server: ${err}`);
  }

  process.on('exit', () => {
    try {
      server.close();
    } catch (e) {}
  });
}
'''

remote_control_path = os.path.join(GEMINI_CLI_DIR, r"packages\\cli\\src\\utils\\remoteControl.ts")
with open(remote_control_path, 'w', encoding='utf-8') as f:
    f.write(remote_control_content)
print(r"Updated packages\cli\src\utils\remoteControl.ts")

# 4. Ensure RemoteResponse is emitted for slash commands and empty buffers in useGeminiStream.ts
modify_file(
    r"packages\\cli\\src\\ui\\hooks\\useGeminiStream.ts",
    "if (!shouldProceed || queryToSend === null) {\n              return;\n            }",
    "if (!shouldProceed || queryToSend === null) {\n              appEvents.emit(AppEvent.RemoteResponse, '[Command Handled]');\n              return;\n            }"
)

modify_file(
    r"packages\\cli\\src\\ui\\hooks\\useGeminiStream.ts",
    "if (geminiMessageBuffer) {\n        appEvents.emit(AppEvent.RemoteResponse, geminiMessageBuffer);\n      }",
    "appEvents.emit(AppEvent.RemoteResponse, geminiMessageBuffer);"
)

modify_file(
    r"packages\\cli\\src\\ui\\hooks\\useGeminiStream.ts",
    "return StreamingState.Idle;\n  }, [isResponding, toolCalls]);",
    "return StreamingState.Idle;\n  }, [isResponding, toolCalls]);\n\n  useEffect(() => {\n    if (streamingState === StreamingState.Idle) {\n      appEvents.emit(AppEvent.RemoteResponse, '[TURN_FINISHED]');\n    }\n  }, [streamingState]);"
)
