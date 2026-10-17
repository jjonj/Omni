using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using OmniSync.Hub.Infrastructure.Services;

namespace OmniSync.Hub.Logic.Services.Mcp
{
    public sealed record McpToolResult(string Text, bool IsError, byte[]? Image = null, string? ImageMime = null)
    {
        public static McpToolResult Ok(string text) => new(text, false);
        public static McpToolResult WithImage(string text, byte[] image, string mime) => new(text, false, image, mime);
    }

    /// Full-access tool set for the whole PC. Nothing is sandboxed; paths are only expanded and normalised.
    public sealed class McpTools
    {
        private const int OutputHeadChars = 16_000;
        private const int OutputTailChars = 16_000;
        private const int MaxReadChars = 32_000;
        private const long MaxSearchFileBytes = 5L * 1024 * 1024;
        private static readonly UTF8Encoding Utf8NoBom = new(false);

        // Text tools set Run; tools that return non-text content (screenshot) set RunResult instead.
        private sealed record Tool(string Name, string Description, string SchemaJson, bool ReadOnly,
            Func<JsonObject, CancellationToken, Task<string>>? Run,
            Func<JsonObject, CancellationToken, Task<McpToolResult>>? RunResult = null);

        private readonly ShellProcessRegistry _processes;
        private readonly ScreenshotService _screenshots;
        private readonly ClipboardService _clipboard;
        private readonly AgentCursorService _agentCursor;
        private readonly Dictionary<string, Tool> _tools;

        public McpTools(ShellProcessRegistry processes, ScreenshotService screenshots, ClipboardService clipboard, AgentCursorService agentCursor)
        {
            _processes = processes;
            _screenshots = screenshots;
            _clipboard = clipboard;
            _agentCursor = agentCursor;
            var tools = new List<Tool>
            {
                new("agent_cursor_status",
                    "Inspect the independent Omni AI cursor, physical mouse and foreground, target HWND, target/current virtual desktops and monitor bounds. Coordinates are physical pixels, including negative monitor origins.",
                    """{"type":"object","properties":{}}""",
                    true, async (_, ct) => System.Text.Json.JsonSerializer.Serialize(await _agentCursor.StatusAsync(ct))),
                new("agent_cursor_move",
                    "Move the independent AI cursor in physical screen pixels on any monitor. For an inactive virtual desktop, provide target_hwnd (a top-level app HWND or child HWND) and optionally desktop_number to validate its desktop. The logical cursor is bound to that window; its overlay stays hidden on the user's active desktop. Omitting target_hwnd returns to normal on-screen hit testing.",
                    """{"type":"object","properties":{"x":{"type":"integer"},"y":{"type":"integer"},"target_hwnd":{"type":"integer","minimum":1},"desktop_number":{"type":"integer","minimum":1}},"required":["x","y"]}""",
                    false, async (args, ct) => System.Text.Json.JsonSerializer.Serialize(await _agentCursor.MoveAsync(RequiredCursorInt(args, "x"), RequiredCursorInt(args, "y"),
                        args["target_hwnd"]?.GetValue<long>(), args["desktop_number"]?.GetValue<int>(), ct))),
                new("agent_cursor_click",
                    "Click at Omni's independent virtual cursor. auto uses DPI-aware native left/right WM_MOUSEBUTTON messages for Unreal/Slate; other windows may use UI Automation. Explicit alternative mode=touch injects a Windows touch tap (left only, visible window), while mode=physical uses SendInput and briefly moves/restores the real Windows mouse (may activate target). No automatic fallback between methods.",
                    """{"type":"object","properties":{"button":{"type":"string","enum":["left","right"],"default":"left"},"count":{"type":"integer","minimum":1,"maximum":2,"default":1},"mode":{"type":"string","enum":["auto","uia","messages","touch","physical"],"default":"auto"}}}""",
                    false, async (args, ct) => System.Text.Json.JsonSerializer.Serialize(await _agentCursor.ClickAsync(Opt(args, "button") ?? "left", OptInt(args, "count", 1), Opt(args, "mode") ?? "auto", ct))),
                new("agent_cursor_scroll",
                    "Scroll at the virtual cursor. Default mode=messages posts WM_MOUSEWHEEL with native screen coordinates, never moving the physical mouse; positive detents scroll up, negative down. Explicit mode=physical uses SendInput (temporarily moves/restores real cursor), requiring the target exposed on the current Windows desktop. No automatic fallback.",
                    """{"type":"object","properties":{"detents":{"type":"integer","minimum":-20,"maximum":20,"description":"Signed wheel notches. Positive=up, negative=down (excluding zero)."},"mode":{"type":"string","enum":["messages","physical"],"default":"messages"}},"required":["detents"]}""",
                    false, async (args, ct) => System.Text.Json.JsonSerializer.Serialize(await _agentCursor.ScrollAsync(RequiredCursorInt(args, "detents"), Opt(args, "mode") ?? "messages", ct))),
                new("agent_cursor_drag_window",
                    "Drag the explicitly bound window using the logical AI cursor (left-drag equivalent). Move/bind the cursor inside the target HWND first, then supply destination physical screen coordinates to_x/to_y. Moves the top-level window with SetWindowPos/NOACTIVATE, including on inactive virtual desktops, without touching the physical mouse. This is window movement, not a generic control drag.",
                    """{"type":"object","properties":{"to_x":{"type":"integer"},"to_y":{"type":"integer"}},"required":["to_x","to_y"]}""",
                    false, async (args, ct) => System.Text.Json.JsonSerializer.Serialize(await _agentCursor.DragWindowAsync(
                        RequiredCursorInt(args, "to_x"), RequiredCursorInt(args, "to_y"), ct))),
                new("agent_cursor_hide",
                    "Hide the Omni AI cursor and clear its explicit window/desktop binding. Move it again before clicking.",
                    """{"type":"object","properties":{}}""",
                    false, async (_, ct) => System.Text.Json.JsonSerializer.Serialize(await _agentCursor.HideAsync(ct))),
                new("run_command",
                    "Run a command and wait for it to finish (PowerShell by default, or cmd). Returns exit code, timeout status and merged output. For anything that may run longer than the timeout, use start_process instead.",
                    """{"type":"object","properties":{"command":{"type":"string"},"shell":{"type":"string","enum":["powershell","cmd"],"default":"powershell"},"cwd":{"type":"string","description":"Working directory; defaults to the Hub's directory."},"timeout_seconds":{"type":"integer","minimum":1,"maximum":600,"default":45}},"required":["command"]}""",
                    false, RunCommandAsync),
                new("spawn_chatgpt_subagent",
                    "Create a named ChatGPT agent session in the already-running T3 Code desktop app and submit its first prompt. Requires title and either prompt or prompt_file; returns the new session ID on success. This opens a real session and starts work.",
                    """{"type":"object","properties":{"title":{"type":"string","description":"Name for the new ChatGPT session."},"prompt":{"type":"string","description":"Initial instructions to send to the new ChatGPT agent."},"prompt_file":{"type":"string","description":"Path to an existing UTF-8 text file on the Windows PC containing the initial instructions (instead of prompt)."}},"required":["title"],"oneOf":[{"required":["prompt"]},{"required":["prompt_file"]}]}""",
                    false, SpawnChatGptSubagentAsync),
                new("start_process",
                    "Start a long-running command in the background (builds, dev servers, watchers). Returns an id for read_process_output, write_process_stdin and stop_process.",
                    """{"type":"object","properties":{"command":{"type":"string"},"shell":{"type":"string","enum":["powershell","cmd"],"default":"powershell"},"cwd":{"type":"string"}},"required":["command"]}""",
                    false, StartProcessAsync),
                new("read_process_output",
                    "Read output from a background process. Pass the returned next_offset back as offset to continue where you left off.",
                    """{"type":"object","properties":{"id":{"type":"string"},"offset":{"type":"integer","minimum":0,"default":0}},"required":["id"]}""",
                    true, ReadProcessOutputAsync),
                new("write_process_stdin",
                    "Write text to a background process's stdin.",
                    """{"type":"object","properties":{"id":{"type":"string"},"text":{"type":"string"},"newline":{"type":"boolean","default":true}},"required":["id","text"]}""",
                    false, WriteProcessStdinAsync),
                new("stop_process",
                    "Kill a background process and its child processes.",
                    """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}""",
                    false, StopProcessAsync),
                new("read_file",
                    "Read a text file with line numbers. Use offset (1-based line) and limit to page through large files.",
                    """{"type":"object","properties":{"path":{"type":"string"},"offset":{"type":"integer","minimum":1,"default":1},"limit":{"type":"integer","minimum":1,"maximum":10000,"default":400}},"required":["path"]}""",
                    true, ReadFileAsync),
                new("write_file",
                    "Create or overwrite a text file (UTF-8). Parent directories are created. Set append to add to the end instead.",
                    """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"append":{"type":"boolean","default":false}},"required":["path","content"]}""",
                    false, WriteFileAsync),
                new("edit_file",
                    "Apply exact string replacements to a file. Edits run in order and the file is only written if all of them succeed. Keeps the file's BOM and line endings.",
                    """{"type":"object","properties":{"path":{"type":"string"},"edits":{"type":"array","minItems":1,"items":{"type":"object","properties":{"old_string":{"type":"string"},"new_string":{"type":"string"},"replace_all":{"type":"boolean","default":false}},"required":["old_string","new_string"]}}},"required":["path","edits"]}""",
                    false, EditFileAsync),
                new("list_directory",
                    "List a directory (dirs first), or show size and modified time for a single file.",
                    """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
                    true, ListDirectoryAsync),
                new("move_path",
                    "Move or rename a file or directory.",
                    """{"type":"object","properties":{"source":{"type":"string"},"destination":{"type":"string"},"overwrite":{"type":"boolean","default":false}},"required":["source","destination"]}""",
                    false, MovePathAsync),
                new("delete_path",
                    "Delete a file, or a directory (recursive must be true for non-empty directories).",
                    """{"type":"object","properties":{"path":{"type":"string"},"recursive":{"type":"boolean","default":false}},"required":["path"]}""",
                    false, DeletePathAsync),
                new("search_files",
                    "Search file contents with a regular expression under a directory. Returns file:line matches. Skips files over 5 MB and binary files.",
                    """{"type":"object","properties":{"path":{"type":"string"},"pattern":{"type":"string"},"glob":{"type":"string","default":"*"},"ignore_case":{"type":"boolean","default":false},"max_results":{"type":"integer","minimum":1,"maximum":2000,"default":200}},"required":["path","pattern"]}""",
                    true, SearchFilesAsync),
                new("list_processes",
                    "List running processes with PID, memory and window title. Optionally filter by name.",
                    """{"type":"object","properties":{"name":{"type":"string","description":"Substring of the process name."}}}""",
                    true, ListProcessesAsync),
                new("kill_process",
                    "Kill a process by PID.",
                    """{"type":"object","properties":{"pid":{"type":"integer","minimum":1},"tree":{"type":"boolean","default":true}},"required":["pid"]}""",
                    false, KillProcessAsync),
                new("screenshot",
                    "Capture a screenshot of a monitor and return it as a JPEG image. monitor 0 is the primary screen; higher numbers are the other screens in order.",
                    """{"type":"object","properties":{"monitor":{"type":"integer","minimum":0,"default":0},"scale":{"type":"number","minimum":0.1,"maximum":1,"default":0.5,"description":"Output size relative to the screen; lower is smaller and cheaper."},"quality":{"type":"integer","minimum":10,"maximum":95,"default":60,"description":"JPEG quality."}}}""",
                    true, null, CaptureScreenshotAsync),
                new("get_clipboard",
                    "Read the clipboard text.",
                    """{"type":"object","properties":{}}""",
                    true, GetClipboardAsync),
                new("set_clipboard",
                    "Replace the clipboard contents with text.",
                    """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
                    false, SetClipboardAsync),
            };
            _tools = tools.ToDictionary(t => t.Name);
        }

        public bool Contains(string name) => _tools.ContainsKey(name);

        private static int RequiredCursorInt(JsonObject args, string key) =>
            args[key]?.GetValue<int>() ?? throw new ArgumentException($"Missing required argument: {key}");

        public JsonArray DescribeTools() => new(_tools.Values.Select(t => (JsonNode)new JsonObject
        {
            ["name"] = t.Name,
            ["description"] = t.Description,
            ["inputSchema"] = JsonNode.Parse(t.SchemaJson),
            ["annotations"] = new JsonObject { ["readOnlyHint"] = t.ReadOnly },
        }).ToArray());

        public async Task<McpToolResult> InvokeAsync(string name, JsonObject arguments, CancellationToken ct)
        {
            var tool = _tools[name];
            if (tool.RunResult != null) return await tool.RunResult(arguments, ct);
            return McpToolResult.Ok(await tool.Run!(arguments, ct));
        }

        // ---- Screen and clipboard ----

        // Any drive letter after "I" (J..Z) being mounted blocks screenshots until the user resolves it.
        private static bool HasDriveAfterI() => Directory.GetLogicalDrives().Any(d => char.ToUpperInvariant(d[0]) > 'I');

        private Task<McpToolResult> CaptureScreenshotAsync(JsonObject args, CancellationToken ct) => Task.Run(() =>
        {
            if (HasDriveAfterI())
                return new McpToolResult("Rejected for security reasons, ask user to resolve", true);

            var monitor = OptInt(args, "monitor", 0);
            var scale = Math.Clamp(OptDouble(args, "scale", 0.5), 0.1, 1.0);
            var quality = Math.Clamp(OptInt(args, "quality", 60), 10, 95);

            var bytes = _screenshots.CapturePrimaryScreenToMemory(scale, quality, monitor);
            if (bytes.Length == 0)
                return new McpToolResult($"screenshot failed: no monitor at index {monitor}, or the capture errored (see the Hub activity log)", true);
            return McpToolResult.WithImage($"screenshot of monitor {monitor} ({bytes.Length} bytes JPEG)", bytes, "image/jpeg");
        }, ct);

        private Task<string> GetClipboardAsync(JsonObject args, CancellationToken ct) => Task.Run(() =>
        {
            var text = _clipboard.GetClipboardText();
            return text == null ? "clipboard does not contain text" : Truncate(text);
        }, ct);

        private Task<string> SetClipboardAsync(JsonObject args, CancellationToken ct) => Task.Run(() =>
        {
            var text = Req(args, "text");
            _clipboard.SetClipboardText(text);
            return $"clipboard set to {text.Length} chars";
        }, ct);

        // ---- Shell ----

        private static async Task<string> RunCommandAsync(JsonObject args, CancellationToken ct)
        {
            var timeout = Math.Clamp(OptInt(args, "timeout_seconds", 45), 1, 600);
            var sw = Stopwatch.StartNew();
            using var session = ShellProcess.Start(Opt(args, "shell") ?? "powershell", Req(args, "command"), Opt(args, "cwd"), keepStdinOpen: false);

            bool timedOut = false;
            try
            {
                using var limit = CancellationTokenSource.CreateLinkedTokenSource(ct);
                limit.CancelAfter(TimeSpan.FromSeconds(timeout));
                while (!session.Child.HasExited) await Task.Delay(50, limit.Token);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                timedOut = true;
            }
            if (timedOut || ct.IsCancellationRequested) session.KillTree();
            if (ct.IsCancellationRequested) ct.ThrowIfCancellationRequested();

            await WaitBrieflyForExit(session);
            var exit = session.Child.HasExited ? session.Child.ExitCode.ToString() : "n/a";
            var (text, _, _) = session.ReadFrom(0, int.MaxValue);
            return $"exit_code: {exit} | timed_out: {timedOut.ToString().ToLowerInvariant()} | {sw.Elapsed.TotalSeconds:F1}s\n{Truncate(text)}";
        }

        private static async Task<string> SpawnChatGptSubagentAsync(JsonObject args, CancellationToken ct)
        {
            const string scriptPath = @"D:\SSDProjects\Tools\t3code\scripts\spawn-chatgpt-session.mjs";
            var title = Req(args, "title").Trim();
            var prompt = Opt(args, "prompt");
            var promptFile = Opt(args, "prompt_file");

            if (title.Length == 0) throw new ArgumentException("title must not be empty.");
            if (string.IsNullOrWhiteSpace(prompt) == string.IsNullOrWhiteSpace(promptFile))
                throw new ArgumentException("Provide exactly one of prompt or prompt_file.");
            if (!File.Exists(scriptPath))
                throw new FileNotFoundException("T3 Code ChatGPT spawn script was not found.", scriptPath);

            using var process = new Process
            {
                StartInfo = new ProcessStartInfo("node.exe")
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                },
            };
            process.StartInfo.ArgumentList.Add(scriptPath);
            process.StartInfo.ArgumentList.Add("--title");
            process.StartInfo.ArgumentList.Add(title);

            if (!string.IsNullOrWhiteSpace(promptFile))
            {
                var path = McpPaths.Resolve(promptFile);
                if (!File.Exists(path)) throw new FileNotFoundException("ChatGPT prompt file was not found.", path);
                process.StartInfo.ArgumentList.Add("--message-file");
                process.StartInfo.ArgumentList.Add(path);
            }
            else
            {
                process.StartInfo.ArgumentList.Add("--message");
                process.StartInfo.ArgumentList.Add(prompt!);
            }

            try { process.Start(); }
            catch (System.ComponentModel.Win32Exception ex)
            {
                throw new InvalidOperationException("Could not launch Node.js. Ensure node.exe is installed and available to the Omni Hub.", ex);
            }

            // Drain both streams concurrently, so a large error cannot block process exit.
            var stdoutTask = process.StandardOutput.ReadToEndAsync();
            var stderrTask = process.StandardError.ReadToEndAsync();
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(115));
            try
            {
                await process.WaitForExitAsync(timeout.Token);
            }
            catch (OperationCanceledException)
            {
                try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
                catch (InvalidOperationException) { }
                if (ct.IsCancellationRequested) throw;
                throw new TimeoutException("Timed out waiting for T3 Code to create the ChatGPT session.");
            }

            var stdout = (await stdoutTask).Trim();
            var stderr = (await stderrTask).Trim();
            if (process.ExitCode != 0)
                throw new InvalidOperationException($"ChatGPT session spawn failed (exit {process.ExitCode}): {stderr}\n{stdout}".Trim());
            if (stdout.Length == 0)
                throw new InvalidOperationException("T3 Code spawn script succeeded without returning a session ID.");
            return stdout;
        }

        private Task<string> StartProcessAsync(JsonObject args, CancellationToken ct)
        {
            var session = _processes.Add(ShellProcess.Start(Opt(args, "shell") ?? "powershell", Req(args, "command"), Opt(args, "cwd"), keepStdinOpen: true));
            return Task.FromResult($"started process id={session.Id} pid={session.Child.Id}. Poll read_process_output with this id.");
        }

        private Task<string> ReadProcessOutputAsync(JsonObject args, CancellationToken ct)
        {
            var session = RequireProcess(args);
            var (text, next, dropped) = session.ReadFrom(OptInt(args, "offset", 0), MaxReadChars);
            var status = session.Child.HasExited ? $"exited with code {session.Child.ExitCode}" : "running";
            return Task.FromResult($"status: {status} | next_offset: {next}{(dropped ? " | older output was dropped" : "")}\n{text}");
        }

        private Task<string> WriteProcessStdinAsync(JsonObject args, CancellationToken ct)
        {
            var session = RequireProcess(args);
            if (session.Child.HasExited) throw new InvalidOperationException($"process {session.Id} has already exited");
            var text = Req(args, "text") + (OptBool(args, "newline", true) ? "\n" : "");
            session.Child.StandardInput.Write(text);
            session.Child.StandardInput.Flush();
            return Task.FromResult($"wrote {text.Length} chars to process {session.Id}");
        }

        private Task<string> StopProcessAsync(JsonObject args, CancellationToken ct)
        {
            var session = RequireProcess(args);
            session.KillTree();
            return Task.FromResult($"stopped process {session.Id}");
        }

        private ShellProcess RequireProcess(JsonObject args)
        {
            var id = Req(args, "id");
            return _processes.Get(id) ?? throw new ArgumentException($"No background process with id '{id}'.");
        }

        // ---- Files ----

        private static Task<string> ReadFileAsync(JsonObject args, CancellationToken ct) => Task.Run(() =>
        {
            var path = McpPaths.Resolve(Req(args, "path"));
            if (!File.Exists(path)) throw new FileNotFoundException("File not found", path);

            using (var fs = File.OpenRead(path))
            {
                var probe = new byte[8000];
                int n = fs.Read(probe, 0, probe.Length);
                if (Array.IndexOf(probe, (byte)0, 0, n) >= 0) throw new InvalidOperationException("Binary file; read_file only handles text.");
            }

            var lines = File.ReadAllLines(path);
            int first = Math.Max(1, OptInt(args, "offset", 1));
            int limit = Math.Clamp(OptInt(args, "limit", 400), 1, 10000);
            int last = Math.Min(lines.Length, first + limit - 1);
            var sb = new StringBuilder($"{path} | lines {first}-{last} of {lines.Length}\n");
            for (int i = first; i <= last; i++) sb.Append(i).Append('\t').Append(lines[i - 1]).Append('\n');
            return Truncate(sb.ToString());
        }, ct);

        private static Task<string> WriteFileAsync(JsonObject args, CancellationToken ct) => Task.Run(() =>
        {
            var path = McpPaths.Resolve(Req(args, "path"));
            var content = Req(args, "content");
            var parent = Path.GetDirectoryName(path);
            if (!string.IsNullOrEmpty(parent)) Directory.CreateDirectory(parent);
            if (OptBool(args, "append", false)) File.AppendAllText(path, content, Utf8NoBom);
            else File.WriteAllText(path, content, Utf8NoBom);
            return $"wrote {content.Length} chars to {path}";
        }, ct);

        private static Task<string> EditFileAsync(JsonObject args, CancellationToken ct) => Task.Run(() =>
        {
            var path = McpPaths.Resolve(Req(args, "path"));
            if (!File.Exists(path)) throw new FileNotFoundException("File not found", path);
            var edits = args["edits"] as JsonArray ?? throw new ArgumentException("'edits' must be an array of {old_string, new_string}.");

            var bytes = File.ReadAllBytes(path);
            bool hasBom = bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF;
            int skip = hasBom ? 3 : 0;
            var text = Utf8NoBom.GetString(bytes, skip, bytes.Length - skip);

            // Files with CRLF line endings: callers usually send LF, so convert the search and replacement text to match.
            bool crlf = text.Contains("\r\n");
            int applied = 0;
            for (int i = 0; i < edits.Count; i++)
            {
                var edit = edits[i] as JsonObject ?? throw new ArgumentException($"edit {i + 1} must be an object.");
                var oldText = Req(edit, "old_string");
                var newText = Req(edit, "new_string");
                if (crlf)
                {
                    oldText = ToCrlf(oldText);
                    newText = ToCrlf(newText);
                }
                if (oldText.Length == 0) throw new ArgumentException($"edit {i + 1}: old_string must not be empty.");

                int count = CountOccurrences(text, oldText);
                if (count == 0) throw new InvalidOperationException($"edit {i + 1}: old_string not found.");
                bool all = OptBool(edit, "replace_all", false);
                if (count > 1 && !all) throw new InvalidOperationException($"edit {i + 1}: old_string matches {count} times; add more context or set replace_all.");

                text = all ? text.Replace(oldText, newText, StringComparison.Ordinal) : ReplaceFirst(text, oldText, newText);
                applied++;
            }

            File.WriteAllText(path, text, new UTF8Encoding(hasBom));
            return $"applied {applied} edit(s) to {path}";
        }, ct);

        private static Task<string> ListDirectoryAsync(JsonObject args, CancellationToken ct) => Task.Run(() =>
        {
            var path = McpPaths.Resolve(Req(args, "path"));
            if (File.Exists(path))
            {
                var file = new FileInfo(path);
                return $"file {file.FullName} | {file.Length} bytes | modified {file.LastWriteTime:yyyy-MM-dd HH:mm}";
            }
            if (!Directory.Exists(path)) throw new DirectoryNotFoundException($"Not found: {path}");

            var entries = new DirectoryInfo(path).EnumerateFileSystemInfos()
                .OrderBy(e => e is DirectoryInfo ? 0 : 1)
                .ThenBy(e => e.Name, StringComparer.OrdinalIgnoreCase)
                .Take(5000)
                .ToList();
            var sb = new StringBuilder($"{path} ({entries.Count} entries)\n");
            foreach (var entry in entries)
            {
                if (entry is DirectoryInfo) sb.Append("[dir]  ").Append(entry.Name).Append('\n');
                else
                {
                    var file = (FileInfo)entry;
                    sb.Append("[file] ").Append(file.Name).Append("  ").Append(file.Length).Append(" bytes  ")
                      .Append(file.LastWriteTime.ToString("yyyy-MM-dd HH:mm")).Append('\n');
                }
            }
            return sb.ToString();
        }, ct);

        private static Task<string> MovePathAsync(JsonObject args, CancellationToken ct) => Task.Run(() =>
        {
            var source = McpPaths.Resolve(Req(args, "source"));
            var destination = McpPaths.Resolve(Req(args, "destination"));
            bool overwrite = OptBool(args, "overwrite", false);

            if (File.Exists(source))
            {
                var parent = Path.GetDirectoryName(destination);
                if (!string.IsNullOrEmpty(parent)) Directory.CreateDirectory(parent);
                File.Move(source, destination, overwrite);
            }
            else if (Directory.Exists(source))
            {
                if (Directory.Exists(destination))
                {
                    if (!overwrite) throw new IOException($"Destination already exists: {destination}");
                    Directory.Delete(destination, true);
                }
                Directory.Move(source, destination);
            }
            else throw new FileNotFoundException("Source not found", source);

            return $"moved {source} -> {destination}";
        }, ct);

        private static Task<string> DeletePathAsync(JsonObject args, CancellationToken ct) => Task.Run(() =>
        {
            var path = McpPaths.Resolve(Req(args, "path"));
            if (File.Exists(path))
            {
                File.Delete(path);
                return $"deleted file {path}";
            }
            if (Directory.Exists(path))
            {
                Directory.Delete(path, OptBool(args, "recursive", false));
                return $"deleted directory {path}";
            }
            throw new FileNotFoundException("Not found", path);
        }, ct);

        private static Task<string> SearchFilesAsync(JsonObject args, CancellationToken ct) => Task.Run(() =>
        {
            var root = McpPaths.Resolve(Req(args, "path"));
            if (!Directory.Exists(root)) throw new DirectoryNotFoundException($"Not found: {root}");

            var options = RegexOptions.Compiled | (OptBool(args, "ignore_case", false) ? RegexOptions.IgnoreCase : RegexOptions.None);
            var regex = new Regex(Req(args, "pattern"), options, TimeSpan.FromSeconds(5));
            var glob = Opt(args, "glob") ?? "*";
            int max = Math.Clamp(OptInt(args, "max_results", 200), 1, 2000);

            var hits = new List<string>();
            var enumeration = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true };
            foreach (var file in Directory.EnumerateFiles(root, glob, enumeration))
            {
                if (hits.Count >= max) break;
                if (new FileInfo(file).Length > MaxSearchFileBytes) continue;

                string[] lines;
                try { lines = File.ReadAllLines(file); }
                catch (IOException) { continue; }
                catch (UnauthorizedAccessException) { continue; }
                if (lines.Any(line => line.IndexOf('\0') >= 0)) continue;

                for (int i = 0; i < lines.Length && hits.Count < max; i++)
                {
                    if (regex.IsMatch(lines[i])) hits.Add($"{file}:{i + 1}: {lines[i].Trim()}");
                }
            }

            if (hits.Count == 0) return "no matches";
            return string.Join("\n", hits) + (hits.Count >= max ? $"\n[stopped at {max} results]" : "");
        }, ct);

        // ---- System ----

        private static Task<string> ListProcessesAsync(JsonObject args, CancellationToken ct) => Task.Run(() =>
        {
            var filter = Opt(args, "name");
            var rows = new List<(int Pid, string Name, double Mb, string Title)>();
            foreach (var process in Process.GetProcesses())
            {
                using (process)
                {
                    try
                    {
                        if (filter != null && !process.ProcessName.Contains(filter, StringComparison.OrdinalIgnoreCase)) continue;
                        rows.Add((process.Id, process.ProcessName, process.WorkingSet64 / 1048576.0, process.MainWindowTitle));
                    }
                    catch (InvalidOperationException) { } // exited while listing
                    catch (System.ComponentModel.Win32Exception) { } // access denied
                }
            }

            var sb = new StringBuilder($"{rows.Count} processes\n");
            foreach (var row in rows.OrderByDescending(r => r.Mb).Take(500))
                sb.Append($"{row.Pid,7}  {row.Name,-32} {row.Mb,8:F1} MB  {row.Title}\n");
            return sb.ToString();
        }, ct);

        private static Task<string> KillProcessAsync(JsonObject args, CancellationToken ct)
        {
            var pid = OptInt(args, "pid", -1);
            if (pid <= 0) throw new ArgumentException("pid must be a positive integer.");
            using var process = Process.GetProcessById(pid);
            var name = process.ProcessName;
            process.Kill(OptBool(args, "tree", true));
            return Task.FromResult($"killed {name} (pid {pid})");
        }

        // ---- Helpers ----

        private static async Task WaitBrieflyForExit(ShellProcess session)
        {
            // Poll instead of WaitForExit: the output pipes may stay open through a grandchild, and we must not wait on them.
            for (int i = 0; i < 40 && !session.Child.HasExited; i++) await Task.Delay(50);
        }

        private static string Truncate(string text)
        {
            if (text.Length <= OutputHeadChars + OutputTailChars) return text;
            var omitted = text.Length - OutputHeadChars - OutputTailChars;
            return text[..OutputHeadChars] + $"\n[... {omitted} chars omitted ...]\n" + text[^OutputTailChars..];
        }

        private static string ToCrlf(string text) => text.Replace("\r\n", "\n").Replace("\n", "\r\n");

        private static int CountOccurrences(string text, string value)
        {
            int count = 0;
            for (int idx = text.IndexOf(value, StringComparison.Ordinal); idx >= 0; idx = text.IndexOf(value, idx + value.Length, StringComparison.Ordinal))
                count++;
            return count;
        }

        private static string ReplaceFirst(string text, string oldValue, string newValue)
        {
            int idx = text.IndexOf(oldValue, StringComparison.Ordinal);
            return text[..idx] + newValue + text[(idx + oldValue.Length)..];
        }

        private static string Req(JsonObject args, string key) =>
            Opt(args, key) ?? throw new ArgumentException($"Missing required argument '{key}'.");

        private static string? Opt(JsonObject args, string key) =>
            args[key] is JsonValue value && value.TryGetValue<string>(out var s) ? s : null;

        private static double OptDouble(JsonObject args, string key, double fallback) =>
            args[key] is JsonValue value && value.TryGetValue<double>(out var d) ? d : fallback;

        private static int OptInt(JsonObject args, string key, int fallback) =>
            args[key] is JsonValue value && value.TryGetValue<int>(out var i) ? i : fallback;

        private static bool OptBool(JsonObject args, string key, bool fallback) =>
            args[key] is JsonValue value && value.TryGetValue<bool>(out var b) ? b : fallback;
    }

    public static class McpPaths
    {
        /// Expands %VARS% and a leading ~ (user profile), then makes the path absolute. No sandboxing.
        public static string Resolve(string path)
        {
            var expanded = Environment.ExpandEnvironmentVariables(path.Trim());
            if (expanded == "~" || expanded.StartsWith("~/") || expanded.StartsWith("~\\"))
                expanded = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile) + expanded[1..];
            return Path.GetFullPath(expanded);
        }
    }
}
