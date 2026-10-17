using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;

namespace OmniSync.Hub.Logic.Services.Mcp
{
    /// A shell command with its merged stdout/stderr kept in a bounded buffer.
    /// Output is captured through events instead of ReadToEnd, so a grandchild that inherits the pipe cannot block the caller.
    public sealed class ShellProcess : IDisposable
    {
        private const int MaxBufferChars = 1024 * 1024;

        private readonly StringBuilder _buffer = new();
        private readonly object _gate = new();
        private long _baseOffset; // absolute offset of _buffer[0]

        private ShellProcess(string id, Process child)
        {
            Id = id;
            Child = child;
        }

        public string Id { get; }
        public Process Child { get; }

        public static ShellProcess Start(string shell, string command, string? cwd, bool keepStdinOpen)
        {
            var psi = new ProcessStartInfo
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                RedirectStandardInput = true,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8,
            };
            if (!string.IsNullOrWhiteSpace(cwd)) psi.WorkingDirectory = McpPaths.Resolve(cwd);
            psi.Environment["GIT_TERMINAL_PROMPT"] = "0";
            psi.Environment["GIT_PAGER"] = "cat";

            if (string.Equals(shell, "cmd", StringComparison.OrdinalIgnoreCase))
            {
                psi.FileName = "cmd.exe";
                psi.Arguments = $"/d /s /c \"{command}\"";
            }
            else
            {
                // -EncodedCommand avoids every layer of quoting between the JSON argument and PowerShell.
                var script = "[Console]::OutputEncoding=[Text.Encoding]::UTF8; $OutputEncoding=[Text.Encoding]::UTF8; " + command;
                psi.FileName = "powershell.exe";
                psi.Arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + Convert.ToBase64String(Encoding.Unicode.GetBytes(script));
            }

            var child = new Process { StartInfo = psi };
            var session = new ShellProcess(Guid.NewGuid().ToString("N")[..8], child);
            child.OutputDataReceived += (_, e) => { if (e.Data != null) session.Append(e.Data + "\n"); };
            child.ErrorDataReceived += (_, e) => { if (e.Data != null) session.Append(e.Data + "\n"); };
            child.Start();
            child.BeginOutputReadLine();
            child.BeginErrorReadLine();
            if (!keepStdinOpen) child.StandardInput.Close(); // prompts see end-of-input instead of hanging
            return session;
        }

        private void Append(string text)
        {
            lock (_gate)
            {
                _buffer.Append(text);
                if (_buffer.Length <= MaxBufferChars) return;
                var drop = _buffer.Length - MaxBufferChars;
                _buffer.Remove(0, drop);
                _baseOffset += drop;
            }
        }

        /// Returns up to maxChars of output starting at an absolute offset. Offsets that were already dropped are clamped forward.
        public (string Text, long NextOffset, bool Dropped) ReadFrom(long offset, int maxChars)
        {
            lock (_gate)
            {
                long end = _baseOffset + _buffer.Length;
                bool dropped = offset < _baseOffset;
                long start = Math.Clamp(offset, _baseOffset, end);
                long stop = Math.Min(end, start + maxChars);
                var text = _buffer.ToString((int)(start - _baseOffset), (int)(stop - start));
                return (text, stop, dropped);
            }
        }

        public void KillTree()
        {
            try
            {
                if (!Child.HasExited) Child.Kill(entireProcessTree: true);
            }
            catch (InvalidOperationException) { } // already gone
            catch (System.ComponentModel.Win32Exception) { } // exited between the check and the kill
        }

        public void Dispose() => Child.Dispose();
    }

    /// Background shells started by start_process, addressed by id.
    public sealed class ShellProcessRegistry
    {
        private readonly ConcurrentDictionary<string, ShellProcess> _sessions = new();

        public ShellProcess Add(ShellProcess session)
        {
            _sessions[session.Id] = session;
            return session;
        }

        public ShellProcess? Get(string id) => _sessions.TryGetValue(id, out var session) ? session : null;
    }
}
