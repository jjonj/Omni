import os

file_path = r"D:\SSDProjects\Tools\gemini-cli\packages\cli\src\utils\remoteControl.ts"

# We use raw strings to avoid escaping issues
new_content = r"""/**
 * @license
 * Copyright 2025 Google LLC
 * SPDX-License-Identifier: Apache-2.0
 */

import * as net from 'node:net';
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import { appEvents, AppEvent } from './events.js';
import { debugLogger } from '@google/gemini-cli-core';

export function startRemoteControl() {
  // Use a random suffix to ensure uniqueness and avoid EACCES
  const randomSuffix = Math.random().toString(36).substring(2, 10);
  const pipeName = '\\.\pipe\gemini-cli-' + process.pid + '-' + randomSuffix;

  // Write the pipe name to a temp file for discovery
  const tempDir = os.tmpdir();
  const infoFile = path.join(tempDir, `gemini-pipe-${process.pid}.json`);
  
  try {
    fs.writeFileSync(infoFile, JSON.stringify({ pipeName, pid: process.pid }));
    debugLogger.log(`Wrote pipe info to ${infoFile}`);
  } catch (err) {
    debugLogger.error(`Failed to write pipe info file: ${err}`);
  }

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
      if (fs.existsSync(infoFile)) {
        fs.unlinkSync(infoFile);
      }
      server.close();
    } catch (_e) {
      // Ignore closure errors on exit
    }
  });
}
"""

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(new_content)

print(f"Successfully patched {file_path}")
