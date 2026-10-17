using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace OmniSync.Hub.Logic.Services.Mcp;

/// <summary>
/// Foreground-aware window tools. Background input is HWND-targeted only: never SendInput,
/// SetCursorPos or SetForegroundWindow except for an explicitly permitted manage_window action.
/// </summary>
public sealed class McpWindowTools
{
    private sealed record Tool(string Name, string Description, string Schema, bool ReadOnly,
        Func<JsonObject, CancellationToken, Task<McpToolResult>> Execute);

    private readonly Dictionary<string, Tool> _tools;

    public McpWindowTools()
    {
        var tools = new[]
        {
            new Tool("get_desktop_state",
                "Get current foreground HWND, system cursor, virtual desktop and monitor geometry without changing focus.",
                """{"type":"object","properties":{}}""", true,
                (a, ct) => Result(() => State(), ct)),
            new Tool("list_windows",
                "Enumerate top-level HWNDs, PID, process, title, class, bounds, visibility and DWM cloaking. Includes windows on other native virtual desktops.",
                """{"type":"object","properties":{"include_hidden":{"type":"boolean","default":false},"include_cloaked":{"type":"boolean","default":true},"process_name":{"type":"string"},"max_results":{"type":"integer","minimum":1,"maximum":1000,"default":200}}}""", true,
                (a, ct) => Result(() => Windows(a), ct)),
            new Tool("capture_window",
                "Best-effort per-window JPEG via Win32 PrintWindow. Hidden, cloaked, minimized or GPU-composited windows may return black/stale frames; never activates the window.",
                """{"type":"object","properties":{"hwnd":{"type":"integer"},"quality":{"type":"integer","minimum":10,"maximum":95,"default":75}},"required":["hwnd"]}""", true,
                CaptureWindowAsync),
            new Tool("list_child_windows",
                "Enumerate child HWNDs (including nested editors) with native window class, title and parent. Read-only; works with Notepad on inactive virtual desktops.",
                """{"type":"object","properties":{"hwnd":{"type":"integer"},"max_results":{"type":"integer","minimum":1,"maximum":1000,"default":200}},"required":["hwnd"]}""", true,
                (a, ct) => Result(() => ChildWindows(a), ct)),
            new Tool("send_window_input",
                "Post text, key or client-coordinate mouse messages without changing foreground focus or moving the real cursor. target=auto redirects Notepad's top-level HWND to its RichEditD2DPT child; target=hwnd posts exactly to the supplied HWND, and target=editor resolves a unique native Edit/RichEdit descendant. Reports message queueing, not completed interaction.",
                """{"type":"object","properties":{"hwnd":{"type":"integer"},"kind":{"type":"string","enum":["text","key","mouse"]},"target":{"type":"string","enum":["auto","hwnd","editor"],"default":"auto"},"text":{"type":"string"},"key_code":{"type":"integer","minimum":1,"maximum":255},"x":{"type":"integer"},"y":{"type":"integer"},"button":{"type":"string","enum":["left","right"],"default":"left"}},"required":["hwnd","kind"]}""", false,
                (a, ct) => Result(() => SendWindowInput(a), ct)),
            new Tool("manage_window",
                "Move/resize, show without activating, hide or explicitly activate a window. Focus-changing actions require focus_policy=allow.",
                """{"type":"object","properties":{"hwnd":{"type":"integer"},"action":{"type":"string","enum":["move_resize","show_no_activate","hide","activate","minimize","maximize","restore"]},"focus_policy":{"type":"string","enum":["never","allow"],"default":"never"},"x":{"type":"integer"},"y":{"type":"integer"},"width":{"type":"integer","minimum":1},"height":{"type":"integer","minimum":1}},"required":["hwnd","action"]}""", false,
                (a, ct) => Result(() => ManageWindow(a), ct)),
            new Tool("list_monitors",
                "List physical monitor bounds, working areas, primary flag and effective DPI. Coordinates are physical desktop pixels.",
                """{"type":"object","properties":{}}""", true,
                (a, ct) => Result(() => JsonSerializer.Serialize(Monitors()), ct)),
            new Tool("find_paths",
                "Recursively find file/directory names by wildcard. Skips junctions/reparse points and inaccessible paths.",
                """{"type":"object","properties":{"path":{"type":"string"},"pattern":{"type":"string","default":"*"},"max_results":{"type":"integer","minimum":1,"maximum":1000,"default":200}},"required":["path"]}""", true,
                (a, ct) => Result(() => FindPaths(a, ct), ct)),
            new Tool("copy_path",
                "Copy file or directory, refusing existing targets unless overwrite=true. Does not follow junctions/reparse points.",
                """{"type":"object","properties":{"source":{"type":"string"},"destination":{"type":"string"},"overwrite":{"type":"boolean","default":false}},"required":["source","destination"]}""", false,
                (a, ct) => Result(() => CopyPath(a, ct), ct)),
            new Tool("transfer_file",
                "Read or write a small binary file using Base64. Maximum 4 MB to avoid overwhelming MCP output.",
                """{"type":"object","properties":{"mode":{"type":"string","enum":["read","write"]},"path":{"type":"string"},"base64":{"type":"string"},"overwrite":{"type":"boolean","default":false}},"required":["mode","path"]}""", false,
                (a, ct) => Result(() => TransferFile(a), ct)),
        };
        _tools = tools.ToDictionary(t => t.Name);
    }

    public bool Contains(string name) => _tools.ContainsKey(name);

    public JsonArray DescribeTools() => new(_tools.Values.Select(t => (JsonNode)new JsonObject
    {
        ["name"] = t.Name,
        ["description"] = t.Description,
        ["inputSchema"] = JsonNode.Parse(t.Schema),
        ["annotations"] = new JsonObject { ["readOnlyHint"] = t.ReadOnly }
    }).ToArray());

    public Task<McpToolResult> InvokeAsync(string name, JsonObject arguments, CancellationToken ct) =>
        _tools[name].Execute(arguments, ct);

    private static Task<McpToolResult> Result(Func<string> run, CancellationToken ct) =>
        Task.Run(() => { ct.ThrowIfCancellationRequested(); return McpToolResult.Ok(run()); }, ct);

    private static string State()
    {
        GetCursorPos(out var point);
        using var vds = new VirtualDesktopService();
        var desktop = vds.Available ? new { Available = true, Count = vds.Count, Current = vds.Current }
            : new { Available = false, Count = 0, Current = 0 };
        return JsonSerializer.Serialize(new
        {
            ForegroundHwnd = GetForegroundWindow().ToInt64(),
            Cursor = new { point.X, point.Y },
            VirtualDesktop = desktop,
            Monitors = Monitors()
        });
    }

    private static string Windows(JsonObject args)
    {
        bool hidden = Bool(args, "include_hidden", false);
        bool cloaked = Bool(args, "include_cloaked", true);
        string filter = Str(args, "process_name") ?? "";
        int max = Math.Clamp(Int(args, "max_results", 200), 1, 1000);
        var result = new List<object>();
        EnumWindows((hwnd, _) =>
        {
            if (result.Count >= max) return false;
            bool visible = IsWindowVisible(hwnd);
            if (!hidden && !visible) return true;
            int cloak = 0;
            try { DwmGetWindowAttribute(hwnd, 14, out cloak, sizeof(int)); } catch (DllNotFoundException) { }
            if (!cloaked && cloak != 0) return true;
            GetWindowThreadProcessId(hwnd, out var pid);
            string proc;
            try { proc = Process.GetProcessById((int)pid).ProcessName; } catch { proc = ""; }
            if (filter.Length != 0 && !proc.Contains(filter, StringComparison.OrdinalIgnoreCase)) return true;
            GetWindowRect(hwnd, out var bounds);
            var title = TextOf(hwnd);
            if (title.Length == 0 && !hidden) return true;
            var clazz = new StringBuilder(256);
            GetClassNameW(hwnd, clazz, clazz.Capacity);
            result.Add(new
            {
                Hwnd = hwnd.ToInt64(), Pid = pid, Process = proc, Title = title, Class = clazz.ToString(),
                Bounds = new { X = bounds.Left, Y = bounds.Top, Width = bounds.Right - bounds.Left, Height = bounds.Bottom - bounds.Top },
                Visible = visible, Cloaked = cloak != 0, CloakFlags = cloak
            });
            return true;
        }, IntPtr.Zero);
        return JsonSerializer.Serialize(result);
    }

    private static string ChildWindows(JsonObject args)
    {
        var root = Handle(args);
        VerifyWindow(root);
        int max = Math.Clamp(Int(args, "max_results", 200), 1, 1000);
        var children = new List<object>();
        bool truncated = false;
        EnumChildWindows(root, (child, _) =>
        {
            if (children.Count == max) { truncated = true; return false; }
            GetWindowRect(child, out var bounds);
            children.Add(new
            {
                Hwnd = child.ToInt64(), ParentHwnd = GetParent(child).ToInt64(),
                Class = ClassOf(child), Title = TextOf(child), Visible = IsWindowVisible(child),
                Bounds = new { X = bounds.Left, Y = bounds.Top,
                    Width = bounds.Right - bounds.Left, Height = bounds.Bottom - bounds.Top }
            });
            return true;
        }, IntPtr.Zero);
        return JsonSerializer.Serialize(new { RootHwnd = root.ToInt64(), Children = children, Truncated = truncated });
    }

    private static async Task<McpToolResult> CaptureWindowAsync(JsonObject args, CancellationToken ct)
    {
        var hwnd = Handle(args);
        int quality = Math.Clamp(Int(args, "quality", 75), 10, 95);
        // PrintWindow synchronously calls the target process; bound MCP latency if the target hangs.
        return await Task.Run(() => CaptureSync(hwnd, quality), ct)
            .WaitAsync(TimeSpan.FromSeconds(5), ct);
    }

    private static McpToolResult CaptureSync(IntPtr hwnd, int quality)
    {
        VerifyWindow(hwnd);
        if (!GetWindowRect(hwnd, out var bounds)) throw new Win32Exception(Marshal.GetLastWin32Error());
        int width = bounds.Right - bounds.Left, height = bounds.Bottom - bounds.Top;
        if (width <= 0 || height <= 0 || (long)width * height > 16_000_000)
            throw new ArgumentException("Window dimensions invalid or too large for capture.");
        using var bitmap = new Bitmap(width, height, PixelFormat.Format32bppArgb);
        using var graphics = Graphics.FromImage(bitmap);
        var hdc = graphics.GetHdc();
        bool ok;
        try { ok = PrintWindow(hwnd, hdc, 2) || PrintWindow(hwnd, hdc, 0); }
        finally { graphics.ReleaseHdc(hdc); }
        if (!ok) return new McpToolResult("PrintWindow failed; target may be protected, hidden, hung or GPU-rendered.", true);
        using var stream = new MemoryStream();
        var codec = ImageCodecInfo.GetImageEncoders().First(c => c.MimeType == "image/jpeg");
        using var encoder = new EncoderParameters(1);
        encoder.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, (long)quality);
        bitmap.Save(stream, codec, encoder);
        return McpToolResult.WithImage($"PrintWindow snapshot of HWND {hwnd.ToInt64()} ({width}x{height}); blank or stale pixels are possible.", stream.ToArray(), "image/jpeg");
    }

    private static string SendWindowInput(JsonObject args)
    {
        var requestedHwnd = Handle(args);
        VerifyWindow(requestedHwnd);
        string kind = Required(args, "kind");
        string target = Str(args, "target") ?? "auto";
        if (target is not ("auto" or "hwnd" or "editor")) throw new ArgumentException("target must be auto, hwnd or editor.");
        var hwnd = ResolveInputTarget(requestedHwnd, target, kind);
        var before = GetForegroundWindow().ToInt64();
        int queued = 0;
        switch (kind)
        {
            case "text":
                string value = Required(args, "text");
                if (value.Length > 10000) throw new ArgumentException("Maximum text length is 10000 UTF-16 code units.");
                foreach (char c in value) { Post(hwnd, 0x0102, new IntPtr(c), IntPtr.Zero); queued++; }
                break;
            case "key":
                int vk = Int(args, "key_code", 0);
                if (vk is < 1 or > 255) throw new ArgumentException("key_code must be between 1 and 255.");
                Post(hwnd, 0x0100, new IntPtr(vk), IntPtr.Zero);
                Post(hwnd, 0x0101, new IntPtr(vk), IntPtr.Zero);
                queued = 2;
                break;
            case "mouse":
                int x = Int(args, "x", int.MinValue), y = Int(args, "y", int.MinValue);
                if (x is < 0 or > 32767 || y is < 0 or > 32767) throw new ArgumentException("x/y must be nonnegative client coordinates <=32767.");
                int packed = (x & 0xffff) | (y << 16);
                string button = Str(args, "button") ?? "left";
                if (button is not ("left" or "right")) throw new ArgumentException("button must be left or right.");
                Post(hwnd, 0x0200, IntPtr.Zero, new IntPtr(packed));
                Post(hwnd, button == "left" ? 0x0201u : 0x0204u, new IntPtr(button == "left" ? 1 : 2), new IntPtr(packed));
                Post(hwnd, button == "left" ? 0x0202u : 0x0205u, IntPtr.Zero, new IntPtr(packed));
                queued = 3;
                break;
            default:
                throw new ArgumentException("kind must be text, key or mouse.");
        }
        var after = GetForegroundWindow().ToInt64();
        return JsonSerializer.Serialize(new
        {
            RequestedHwnd = requestedHwnd.ToInt64(), TargetHwnd = hwnd.ToInt64(),
            TargetClass = ClassOf(hwnd), TargetMode = target, Kind = kind, QueuedMessages = queued,
            ForegroundBefore = before, ForegroundAfter = after,
            FocusPreservedAtReturn = before == after,
            Warning = "Posting succeeded, but target application may ignore messages or later activate itself. No acknowledgement of text insertion."
        });
    }

    private static IntPtr ResolveInputTarget(IntPtr requestedHwnd, string mode, string kind)
    {
        if (mode == "hwnd") return requestedHwnd;
        string className = ClassOf(requestedHwnd);
        if (IsEditorClass(className)) return requestedHwnd;
        // Notepad 11 owns a nested RichEditD2DPT, even when cloaked on another desktop.
        // WM_CHAR to its top-level Notepad HWND queues successfully but is ignored.
        bool notepad = className.Equals("Notepad", StringComparison.OrdinalIgnoreCase);
        if (mode == "auto" && (!notepad || kind == "mouse")) return requestedHwnd;
        var richEdit = new List<IntPtr>();
        var edit = new List<IntPtr>();
        EnumChildWindows(requestedHwnd, (child, _) =>
        {
            string childClass = ClassOf(child);
            if (childClass.StartsWith("RichEdit", StringComparison.OrdinalIgnoreCase)) richEdit.Add(child);
            else if (childClass.Equals("Edit", StringComparison.OrdinalIgnoreCase)) edit.Add(child);
            return true;
        }, IntPtr.Zero);
        var matches = richEdit.Count > 0 ? richEdit : edit;
        if (matches.Count == 1) return matches[0];
        if (matches.Count == 0)
            throw new InvalidOperationException("No native Edit/RichEdit child found; use list_child_windows. Input was not sent.");
        throw new InvalidOperationException($"Found {matches.Count} editor children; specify a child HWND to avoid typing into the wrong control.");
    }

    private static bool IsEditorClass(string name) => name.Equals("Edit", StringComparison.OrdinalIgnoreCase)
        || name.StartsWith("RichEdit", StringComparison.OrdinalIgnoreCase);

    private static string ClassOf(IntPtr hwnd)
    {
        var name = new StringBuilder(256);
        GetClassNameW(hwnd, name, name.Capacity);
        return name.ToString();
    }

    private static string ManageWindow(JsonObject args)
    {
        var hwnd = Handle(args);
        VerifyWindow(hwnd);
        string action = Required(args, "action");
        bool allowFocus = Str(args, "focus_policy") == "allow";
        if ((action is "activate" or "minimize" or "maximize" or "restore") && !allowFocus)
            throw new InvalidOperationException("Action may affect foreground focus. Specify focus_policy=allow explicitly.");
        long before = GetForegroundWindow().ToInt64();
        if (action == "hide" && before == hwnd.ToInt64() && !allowFocus)
            throw new InvalidOperationException("Hiding the foreground window changes focus. Specify focus_policy=allow.");
        switch (action)
        {
            case "move_resize":
                int width = Int(args, "width", 0), height = Int(args, "height", 0);
                if (width <= 0 || height <= 0) throw new ArgumentException("Positive width and height required.");
                if (!SetWindowPos(hwnd, IntPtr.Zero, Int(args, "x", 0), Int(args, "y", 0),
                    width, height, 0x0004 | 0x0010)) throw new Win32Exception(Marshal.GetLastWin32Error());
                break;
            case "show_no_activate": ShowWindow(hwnd, 4); break;
            case "hide": ShowWindow(hwnd, 0); break;
            case "minimize": ShowWindow(hwnd, 6); break;
            case "maximize": ShowWindow(hwnd, 3); break;
            case "restore": ShowWindow(hwnd, 9); break;
            case "activate":
                if (!SetForegroundWindow(hwnd)) throw new InvalidOperationException("Windows denied activation.");
                break;
            default: throw new ArgumentException("Unknown manage_window action.");
        }
        long after = GetForegroundWindow().ToInt64();
        return JsonSerializer.Serialize(new { Action = action, Hwnd = hwnd.ToInt64(),
            ForegroundBefore = before, ForegroundAfter = after, FocusPreservedAtReturn = before == after });
    }

    private static object[] Monitors() => Screen.AllScreens.Select(screen =>
    {
        var b = screen.Bounds;
        IntPtr monitor = MonitorFromPoint(new POINT { X = b.Left + b.Width / 2, Y = b.Top + b.Height / 2 }, 2);
        uint dpiX = 96, dpiY = 96;
        try { GetDpiForMonitor(monitor, 0, out dpiX, out dpiY); } catch (DllNotFoundException) { }
        return (object)new
        {
            screen.DeviceName, screen.Primary,
            Bounds = new { b.Left, b.Top, b.Width, b.Height },
            WorkingArea = new { screen.WorkingArea.Left, screen.WorkingArea.Top, screen.WorkingArea.Width, screen.WorkingArea.Height },
            DpiX = dpiX, DpiY = dpiY
        };
    }).ToArray();

    private static string FindPaths(JsonObject args, CancellationToken ct)
    {
        string root = Path.GetFullPath(Environment.ExpandEnvironmentVariables(Required(args, "path")));
        string pattern = Str(args, "pattern") ?? "*";
        int max = Math.Clamp(Int(args, "max_results", 200), 1, 1000);
        var options = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true,
            AttributesToSkip = FileAttributes.ReparsePoint, ReturnSpecialDirectories = false };
        var matches = new List<string>();
        foreach (var path in Directory.EnumerateFileSystemEntries(root, "*", options))
        {
            ct.ThrowIfCancellationRequested();
            if (!System.IO.Enumeration.FileSystemName.MatchesSimpleExpression(pattern, Path.GetFileName(path), true)) continue;
            matches.Add(path);
            if (matches.Count >= max) break;
        }
        return JsonSerializer.Serialize(new { Matches = matches, Truncated = matches.Count == max });
    }

    private static string CopyPath(JsonObject args, CancellationToken ct)
    {
        string source = Path.GetFullPath(Environment.ExpandEnvironmentVariables(Required(args, "source")));
        string destination = Path.GetFullPath(Environment.ExpandEnvironmentVariables(Required(args, "destination")));
        bool overwrite = Bool(args, "overwrite", false);
        if (string.Equals(source, destination, StringComparison.OrdinalIgnoreCase)) throw new ArgumentException("Source equals destination.");
        if (File.Exists(source))
        {
            if (File.GetAttributes(source).HasFlag(FileAttributes.ReparsePoint)) throw new ArgumentException("Cannot copy reparse-point files.");
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            File.Copy(source, destination, overwrite);
            return $"copied file to {destination}";
        }
        if (!Directory.Exists(source)) throw new FileNotFoundException("Source path not found.", source);
        if (File.GetAttributes(source).HasFlag(FileAttributes.ReparsePoint)) throw new ArgumentException("Cannot copy junctions.");
        if (destination.StartsWith(source.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("Cannot copy a directory into itself.");
        if (Directory.Exists(destination) && !overwrite) throw new IOException("Destination directory already exists.");
        var options = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = false,
            AttributesToSkip = FileAttributes.ReparsePoint };
        var paths = Directory.EnumerateFileSystemEntries(source, "*", options).ToArray();
        Directory.CreateDirectory(destination);
        foreach (var path in paths)
        {
            ct.ThrowIfCancellationRequested();
            string relative = Path.GetRelativePath(source, path);
            string target = Path.Combine(destination, relative);
            if (Directory.Exists(path)) Directory.CreateDirectory(target);
            else
            {
                Directory.CreateDirectory(Path.GetDirectoryName(target)!);
                File.Copy(path, target, overwrite);
            }
        }
        return $"copied {paths.Length} entries to {destination}";
    }

    private static string TransferFile(JsonObject args)
    {
        string path = Path.GetFullPath(Environment.ExpandEnvironmentVariables(Required(args, "path")));
        const int maxBytes = 4 * 1024 * 1024;
        switch (Required(args, "mode"))
        {
            case "read":
                var info = new FileInfo(path);
                if (info.Length > maxBytes) throw new ArgumentException("File exceeds 4 MB transfer limit.");
                return JsonSerializer.Serialize(new { Path = path, Bytes = info.Length, Base64 = Convert.ToBase64String(File.ReadAllBytes(path)) });
            case "write":
                byte[] bytes = Convert.FromBase64String(Required(args, "base64"));
                if (bytes.Length > maxBytes) throw new ArgumentException("File exceeds 4 MB transfer limit.");
                if (File.Exists(path) && !Bool(args, "overwrite", false)) throw new IOException("Destination exists; specify overwrite=true.");
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                File.WriteAllBytes(path, bytes);
                return $"wrote {bytes.Length} binary bytes to {path}";
            default: throw new ArgumentException("mode must be read or write.");
        }
    }

    private static IntPtr Handle(JsonObject args) => new(args["hwnd"]?.GetValue<long>()
        ?? throw new ArgumentException("Missing hwnd."));
    private static void VerifyWindow(IntPtr hwnd)
    {
        if (hwnd == IntPtr.Zero || !IsWindow(hwnd)) throw new ArgumentException($"Invalid/stale HWND {hwnd}.");
    }
    private static void Post(IntPtr hwnd, uint message, IntPtr wp, IntPtr lp)
    {
        if (!PostMessageW(hwnd, message, wp, lp)) throw new Win32Exception(Marshal.GetLastWin32Error(), "Window message was not queued.");
    }
    private static string Required(JsonObject a, string key) => Str(a, key)
        ?? throw new ArgumentException($"Missing required {key}.");
    private static string? Str(JsonObject a, string key) => a[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;
    private static int Int(JsonObject a, string key, int fallback) => a[key] is JsonValue v && v.TryGetValue<int>(out var n) ? n : fallback;
    private static bool Bool(JsonObject a, string key, bool fallback) => a[key] is JsonValue v && v.TryGetValue<bool>(out var b) ? b : fallback;
    private static string TextOf(IntPtr hwnd)
    {
        int length = GetWindowTextLengthW(hwnd);
        var sb = new StringBuilder(Math.Max(1, Math.Min(length + 1, 4096)));
        GetWindowTextW(hwnd, sb, sb.Capacity);
        return sb.ToString();
    }

    private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr param);
    [StructLayout(LayoutKind.Sequential)] private struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] private struct POINT { public int X, Y; }
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr param);
    [DllImport("user32.dll")] private static extern bool EnumChildWindows(IntPtr parent, EnumWindowsProc callback, IntPtr param);
    [DllImport("user32.dll")] private static extern IntPtr GetParent(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern bool GetCursorPos(out POINT point);
    [DllImport("user32.dll")] private static extern bool IsWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextLengthW(IntPtr hwnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextW(IntPtr hwnd, StringBuilder text, int max);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassNameW(IntPtr hwnd, StringBuilder text, int max);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr hwnd, int attribute, out int value, int size);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool PostMessageW(IntPtr hwnd, uint message, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int width, int height, uint flags);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hwnd, int command);
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern IntPtr MonitorFromPoint(POINT point, uint flags);
    [DllImport("shcore.dll")] private static extern int GetDpiForMonitor(IntPtr monitor, int dpiType, out uint dpiX, out uint dpiY);
}
