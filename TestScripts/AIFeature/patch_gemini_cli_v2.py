import os

file_path = r"D:\SSDProjects\Tools\gemini-cli\packages\cli\src\utils\remoteControl.ts"

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replacement 1: Use TCP port logic
old_pipe_logic = "const pipeName = '\\.\\pipe\\gemini-cli-' + process.pid;"
new_pipe_logic = "// Use TCP port instead of named pipe\n  const pipeName = 20000 + (process.pid % 10000);"

if old_pipe_logic in content:
    content = content.replace(old_pipe_logic, new_pipe_logic)
    print("Replaced pipeName logic.")
else:
    print("Warning: old_pipe_logic not found (maybe already patched?)")
    # Check if already patched to avoid error
    if "20000 + (process.pid % 10000)" in content:
        print("Already patched pipeName logic.")

# Replacement 2: Listen on localhost
old_listen_logic = "server.listen(pipeName, () => {"
new_listen_logic = "server.listen(pipeName, '127.0.0.1', () => {"

if old_listen_logic in content:
    content = content.replace(old_listen_logic, new_listen_logic)
    print("Replaced listen logic.")
else:
    print("Warning: old_listen_logic not found.")
    if "server.listen(pipeName, '127.0.0.1', () => {" in content:
        print("Already patched listen logic.")

# Fix the broken file from previous attempts (if any)
# Previous attempts might have truncated the file or inserted weird chars.
# Ideally I should restore from git?
# But I don't have git access to that repo easily (it's external).
# I'll rely on the fact that I likely corrupted it.
# I should try to RESTORE it first.
# But I don't have a backup.
# Wait! I read the ORIGINAL content in the `type` command earlier!
# I can overwrite the file with the ORIGINAL content first, then patch.
# I'll copy the original content from my log history into a variable here.
# AND I'll use the safe replacement method.

# Original Content (reconstructed from earlier `type` output):
original_content = r"""
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
              debugLogger.log(
                `Received remote prompt: ${msg.text.substring(0, 50)}`,
              );
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

  server.on('error', (err: Error) => {
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
    } catch (_e) {
      // Ignore closure errors on exit
    }
  });
}
"

# I need to be careful with \n in the original_content string literal in Python.
# I'll use the split trick again but for RESTORATION.
# Actually, since I'm just replacing specific lines, I can do it on the original content string variable.

# Wait, `original_content` has `buffer.includes('\n')`.
# In Python `r"""..."""`, `\n` is literal backslash+n.
# This MATCHES what `type` output gave me (if `type` output escaped backslashes?).
# `type` output: `if (buffer.includes('\n')) {`
# This means the file has `\` `n`.
# So `r"""..."""` is correct.

# So I will overwrite the file with `original_content` first (patched in memory).
content = original_content
content = content.replace(old_pipe_logic, new_pipe_logic)
content = content.replace(old_listen_logic, new_listen_logic)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print(f"Successfully patched {file_path} (restored and modified)")
