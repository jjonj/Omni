import os

file_path = r"D:\SSDProjects\Tools\gemini-cli\packages\cli\src\utils\remoteControl.ts"

newline_escape = '\n'

part1 = r"""/**
 * @license
 * Copyright 2025 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */

import * as net from 'node:net';
import { appEvents, AppEvent } from './events.js';
import { debugLogger } from '@google/gemini-cli-core';

export function startRemoteControl() {
  // Use TCP port instead of named pipe to avoid EACCES on Windows
  // Port range: 20000 - 29999 based on PID
  const pipeName = 20000 + (process.pid % 10000);

  const server = net.createServer((socket) => {
    debugLogger.log(`Remote control client connected on ${pipeName}`);

    let buffer = '';
    socket.on('data', (data) => {
      buffer += data.toString();
      if (buffer.includes('"""') ) { // This is where the newline_escape should be inserted
        const lines = buffer.split('"""'); // This is where the newline_escape should be inserted
        buffer = lines.pop() || '';
        for (const line of lines) {
          if (!line.trim()) continue;
          try {
            const msg = JSON.parse(line);
            if (msg.command === 'prompt' && msg.text) {
              debugLogger.log(
                `Received remote prompt: ${msg.text.substring(0, 50)}...`,
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
        socket.write(JSON.stringify({ type: 'response', text }) + '"""'); // This is where the newline_escape should be inserted
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
    // Listen on localhost only
    server.listen(pipeName, '127.0.0.1', () => {
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
""

full_content = part1 + newline_escape + part2 + newline_escape + part3 + newline_escape + part4

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(full_content)

print(f"Successfully patched {file_path}")
