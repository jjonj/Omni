using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

// OmniAgentHook: bridge between OmniHub and agent sessions that are open in another client.
//
//   hook                      Claude Code hook. Registered for SessionStart, SessionEnd, UserPromptSubmit,
//                             PostToolUse and Stop. Keeps ~/.omni/agent-live/<session>.json current (which
//                             process holds the session, desktop app or terminal, busy or idle) and hands
//                             messages queued in ~/.omni/agent-inbox/<session>.jsonl to the model.
//   inject <pid> <base64>     Types text + Enter into the console of <pid> (terminal-hosted agents).
//
// Every path must be cheap: the hook runs on every tool call of every Claude Code session.

var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".omni");
try
{
    if (args.Length >= 3 && args[0] == "inject")
        return ConsoleInjector.Inject(int.Parse(args[1]), Encoding.UTF8.GetString(Convert.FromBase64String(args[2])));
    if (args.Length >= 1 && args[0] == "hook")
        return Hook.Run(root, Console.In.ReadToEnd());
    Console.Error.WriteLine("usage: OmniAgentHook hook | inject <pid> <base64 text>");
    return 2;
}
catch (Exception error)
{
    // A hook must never break the session it runs in.
    Console.Error.WriteLine("OmniAgentHook: " + error.Message);
    return args.Length > 0 && args[0] == "inject" ? 1 : 0;
}

static class Hook
{
    public static int Run(string root, string input)
    {
        using var doc = JsonDocument.Parse(input.TrimStart('﻿', ' ', '\r', '\n'));
        var data = doc.RootElement;
        var session = Str(data, "session_id");
        var evt = Str(data, "hook_event_name");
        if (session.Length == 0 || session.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return 0;

        switch (evt)
        {
            case "SessionStart":
                WriteLive(root, session, data, busy: false);
                return 0;
            case "SessionEnd":
                var (pid, _) = Ancestry.FindClaude();
                File.Delete(LiveFile(root, session, pid));
                return 0;
            case "UserPromptSubmit":
                WriteLive(root, session, data, busy: true);
                return Deliver(root, session, evt, data);
            case "PostToolUse":
                return Deliver(root, session, evt, data);
            case "Stop":
                if (Deliver(root, session, evt, data, out var delivered) == 0 && !delivered)
                    WriteLive(root, session, data, busy: false);
                return 0;
            default:
                return 0;
        }
    }

    static int Deliver(string root, string session, string evt, JsonElement data) => Deliver(root, session, evt, data, out _);

    static int Deliver(string root, string session, string evt, JsonElement data, out bool delivered)
    {
        delivered = false;
        // Stop hooks re-entered from a block we issued still deliver newer messages, never the same ones twice.
        var inbox = Path.Combine(root, "agent-inbox", session + ".jsonl");
        if (!File.Exists(inbox)) return 0;
        var claimed = inbox + "." + Environment.ProcessId + ".claimed";
        try { File.Move(inbox, claimed); } catch (IOException) { return 0; } // another hook claimed it
        var messages = new List<string>();
        foreach (var line in File.ReadAllLines(claimed))
        {
            if (line.Trim().Length == 0) continue;
            using var row = JsonDocument.Parse(line);
            var text = Str(row.RootElement, "text");
            if (text.Length > 0) messages.Add(text);
        }
        File.Delete(claimed);
        if (messages.Count == 0) return 0;

        var body = "New message from the user, sent from the Omni phone app" +
                   (evt == "UserPromptSubmit" ? " earlier" : " while you were working") + ":\n\n" +
                   string.Join("\n\n", messages) +
                   // Clients such as the Claude desktop app never display hook context, so the user on the PC
                   // would not see what they sent; the agent's own reply is the one place that is always visible.
                   "\n\n(The user cannot see this message in the PC client. Start your next message to the user by quoting it " +
                   "on its own line as: \"📱 From your phone: <message>\", then respond to it.)";
        using var stdout = Console.OpenStandardOutput();
        using var writer = new Utf8JsonWriter(stdout);
        writer.WriteStartObject();
        // Hook context is only seen by the model; systemMessage makes the message visible in the client too.
        writer.WriteString("systemMessage", "📱 From your phone: " + string.Join("\n📱 ", messages));
        if (evt == "Stop")
        {
            writer.WriteString("decision", "block");
            writer.WriteString("reason", body);
        }
        else
        {
            writer.WriteStartObject("hookSpecificOutput");
            writer.WriteString("hookEventName", evt);
            writer.WriteString("additionalContext", body);
            writer.WriteEndObject();
        }
        writer.WriteEndObject();
        writer.Flush();
        delivered = true;
        return 0;
    }

    // One file per (session, process): a headless Omni turn on a session that is also open in the app
    // must not overwrite or delete the app's registration. Sessions started before the hook was installed
    // get their file on first activity (UserPromptSubmit / Stop).
    static string LiveFile(string root, string session, int pid) => Path.Combine(root, "agent-live", $"{session}.{pid}.json");

    static void WriteLive(string root, string session, JsonElement data, bool busy)
    {
        var (pid, client) = Ancestry.FindClaude();
        var liveFile = LiveFile(root, session, pid);
        Directory.CreateDirectory(Path.GetDirectoryName(liveFile)!);
        var tmp = liveFile + ".tmp";
        using (var stream = File.Create(tmp))
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteString("SessionId", session);
            writer.WriteNumber("Pid", pid);
            writer.WriteString("Client", client);
            writer.WriteString("Cwd", Str(data, "cwd"));
            writer.WriteString("TranscriptPath", Str(data, "transcript_path"));
            writer.WriteBoolean("Busy", busy);
            writer.WriteString("UpdatedAt", DateTime.UtcNow.ToString("o"));
            writer.WriteEndObject();
        }
        File.Move(tmp, liveFile, overwrite: true);
    }

    static string Str(JsonElement e, string name) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
}

/// <summary>Finds the claude.exe that runs this hook and whether it belongs to the desktop app or a terminal.</summary>
static class Ancestry
{
    public static (int Pid, string Client) FindClaude()
    {
        var parents = Snapshot();
        var pid = Environment.ProcessId;
        for (var depth = 0; depth < 8 && parents.TryGetValue(pid, out var parent); depth++)
        {
            pid = parent.ParentPid;
            if (!parents.TryGetValue(pid, out var info)) break;
            if (!info.Name.StartsWith("claude", StringComparison.OrdinalIgnoreCase)) continue;
            // The desktop app's own process is also Claude.exe; the CLI it spawns is its child.
            // Headless turns started by OmniHub are "omni": no console to type into, Omni already owns them.
            var parentPath = ImagePath(info.ParentPid);
            var client = parentPath.Contains("\\WindowsApps\\", StringComparison.OrdinalIgnoreCase) ||
                         parentPath.EndsWith("\\app\\Claude.exe", StringComparison.OrdinalIgnoreCase) ? "app"
                : parentPath.EndsWith("OmniSync.Hub.exe", StringComparison.OrdinalIgnoreCase) ? "omni"
                : "terminal";
            return (pid, client);
        }
        return (0, "unknown");
    }

    record struct Proc(int ParentPid, string Name);

    static Dictionary<int, Proc> Snapshot()
    {
        var result = new Dictionary<int, Proc>();
        var snap = CreateToolhelp32Snapshot(0x2 /*TH32CS_SNAPPROCESS*/, 0);
        if (snap == IntPtr.Zero || snap == new IntPtr(-1)) return result;
        try
        {
            var entry = new PROCESSENTRY32W { dwSize = (uint)Marshal.SizeOf<PROCESSENTRY32W>() };
            if (!Process32FirstW(snap, ref entry)) return result;
            do result[(int)entry.th32ProcessID] = new Proc((int)entry.th32ParentProcessID, entry.szExeFile);
            while (Process32NextW(snap, ref entry));
        }
        finally { CloseHandle(snap); }
        return result;
    }

    static string ImagePath(int pid)
    {
        var h = OpenProcess(0x1000 /*PROCESS_QUERY_LIMITED_INFORMATION*/, false, (uint)pid);
        if (h == IntPtr.Zero) return "";
        try
        {
            var sb = new StringBuilder(1024);
            var size = sb.Capacity;
            return QueryFullProcessImageNameW(h, 0, sb, ref size) ? sb.ToString() : "";
        }
        finally { CloseHandle(h); }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct PROCESSENTRY32W
    {
        public uint dwSize, cntUsage, th32ProcessID;
        public IntPtr th32DefaultHeapID;
        public uint th32ModuleID, cntThreads, th32ParentProcessID;
        public int pcPriClassBase;
        public uint dwFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string szExeFile;
    }

    [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint pid);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool Process32FirstW(IntPtr snap, ref PROCESSENTRY32W entry);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool Process32NextW(IntPtr snap, ref PROCESSENTRY32W entry);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool QueryFullProcessImageNameW(IntPtr h, uint flags, StringBuilder name, ref int size);
}

/// <summary>Types into another process's console input buffer, as if the user typed it.</summary>
static class ConsoleInjector
{
    public static int Inject(int pid, string text)
    {
        // Terminal UIs submit on Enter, so a multi-line message is sent as one line.
        text = text.Replace("\r\n", " ").Replace('\n', ' ').Replace('\r', ' ');
        FreeConsole();
        if (!AttachConsole((uint)pid)) throw new InvalidOperationException($"AttachConsole({pid}) failed: {Marshal.GetLastWin32Error()}");
        try
        {
            var handle = CreateFileW("CONIN$", 0xC0000000, 3, IntPtr.Zero, 3, 0, IntPtr.Zero);
            if (handle == new IntPtr(-1)) throw new InvalidOperationException($"CONIN$ failed: {Marshal.GetLastWin32Error()}");
            try
            {
                var records = new List<INPUT_RECORD>();
                foreach (var ch in text) AddKey(records, ch, 0, 0);
                AddKey(records, '\r', 0x0D, 0x1C);
                var array = records.ToArray();
                if (!WriteConsoleInputW(handle, array, (uint)array.Length, out var written) || written != array.Length)
                    throw new InvalidOperationException($"WriteConsoleInput failed: {Marshal.GetLastWin32Error()}");
            }
            finally { CloseHandle(handle); }
        }
        finally { FreeConsole(); }
        return 0;
    }

    static void AddKey(List<INPUT_RECORD> records, char ch, ushort vk, ushort scan)
    {
        foreach (var down in new[] { true, false })
            records.Add(new INPUT_RECORD
            {
                EventType = 1,
                KeyEvent = new KEY_EVENT_RECORD { bKeyDown = down ? 1 : 0, wRepeatCount = 1, wVirtualKeyCode = vk, wVirtualScanCode = scan, UnicodeChar = ch }
            });
    }

    [StructLayout(LayoutKind.Sequential)]
    struct KEY_EVENT_RECORD
    {
        public int bKeyDown;
        public ushort wRepeatCount, wVirtualKeyCode, wVirtualScanCode;
        public char UnicodeChar;
        public uint dwControlKeyState;
    }

    [StructLayout(LayoutKind.Explicit)]
    struct INPUT_RECORD
    {
        [FieldOffset(0)] public ushort EventType;
        [FieldOffset(4)] public KEY_EVENT_RECORD KeyEvent;
    }

    [DllImport("kernel32.dll", SetLastError = true)] static extern bool AttachConsole(uint pid);
    [DllImport("kernel32.dll")] static extern bool FreeConsole();
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr CreateFileW(string name, uint access, uint share, IntPtr security, uint disposition, uint flags, IntPtr template);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool WriteConsoleInputW(IntPtr handle, INPUT_RECORD[] records, uint count, out uint written);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
}
