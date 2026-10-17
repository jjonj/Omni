using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace OmniSync.Hub.Logic.Services.Mcp
{
    /// Window inspection (UI Automation) and virtual desktop tools, ported from DeskMCP. Kept apart from McpTools
    /// so the two tool sets can change independently; McpEndpoint merges their lists.
    public sealed class McpDesktopTools
    {
        private sealed record Tool(string Name, string Description, string SchemaJson, bool ReadOnly,
            Func<JsonObject, CancellationToken, Task<string>> Run);

        private readonly UiInspector _ui;
        private readonly VirtualDesktopService _virtualDesktops;
        private readonly Dictionary<string, Tool> _tools;

        public McpDesktopTools(UiInspector ui, VirtualDesktopService virtualDesktops)
        {
            _ui = ui;
            _virtualDesktops = virtualDesktops;
            var tools = new List<Tool>
            {
                new("ui_windows",
                    "List visible top-level windows with their hwnd, process, class, title and screen rectangle (x,y,width,height). Use the hwnd with ui_inspect and ui_action.",
                    """{"type":"object","properties":{}}""",
                    true, (_, _) => Task.FromResult(ListWindows())),
                new("ui_inspect",
                    "Dump the UI Automation element tree of a window. Each line shows a path such as 0/2/1 that ui_action accepts, plus the control type, name, automation id, value, enabled state, supported patterns and rectangle. Inspect again after any change to the window.",
                    """{"type":"object","properties":{"hwnd":{"type":"integer"},"max_depth":{"type":"integer","minimum":0,"maximum":12,"default":4},"max_elements":{"type":"integer","minimum":1,"maximum":2000,"default":300}},"required":["hwnd"]}""",
                    true, (args, _) => Task.FromResult(_ui.Inspect(Hwnd(args), OptInt(args, "max_depth", 4), OptInt(args, "max_elements", 300)))),
                new("ui_action",
                    "Act on one element from a ui_inspect dump. Prefer invoke, toggle, select, set_value or focus, which use UI Automation and do not move the mouse. click moves the physical mouse to the element centre and activates the window. send_keys focuses the element and types the keys using SendKeys syntax (for example ^s for Ctrl+S, {ENTER}).",
                    """{"type":"object","properties":{"hwnd":{"type":"integer"},"path":{"type":"string","description":"Element path from ui_inspect; empty string is the window itself."},"action":{"type":"string","enum":["invoke","toggle","select","set_value","focus","click","send_keys"]},"value":{"type":"string","description":"Text for set_value or keys for send_keys."},"focus_policy":{"type":"string","enum":["never","allow"],"default":"never"}},"required":["hwnd","action"]}""",
                    false, (args, _) => Task.FromResult(_ui.Act(Hwnd(args), Opt(args, "path") ?? "", Req(args, "action"), Opt(args, "value"), Opt(args, "focus_policy") == "allow"))),
                new("inspect_ui",
                    "Inspect a window's UI Automation tree. Alias of ui_inspect; use paths in interact_ui.",
                    """{"type":"object","properties":{"hwnd":{"type":"integer"},"max_depth":{"type":"integer","minimum":0,"maximum":12,"default":4},"max_elements":{"type":"integer","minimum":1,"maximum":2000,"default":300}},"required":["hwnd"]}""",
                    true, (args, _) => Task.FromResult(_ui.Inspect(Hwnd(args), OptInt(args, "max_depth", 4), OptInt(args, "max_elements", 300)))),
                new("interact_ui",
                    "UI Automation action on a specific inspected element. Focus-changing actions are denied unless focus_policy=allow. Provider-side focus changes are still possible.",
                    """{"type":"object","properties":{"hwnd":{"type":"integer"},"path":{"type":"string"},"action":{"type":"string","enum":["invoke","toggle","select","set_value","focus","click","send_keys"]},"value":{"type":"string"},"focus_policy":{"type":"string","enum":["never","allow"],"default":"never"}},"required":["hwnd","action"]}""",
                    false, (args, _) => Task.FromResult(_ui.Act(Hwnd(args), Opt(args, "path") ?? "", Req(args, "action"), Opt(args, "value"), Opt(args, "focus_policy") == "allow"))),
                new("list_virtual_desktops",
                    "Return the Windows native virtual desktop count and currently viewed desktop number; missing helper library reported explicitly.",
                    """{"type":"object","properties":{}}""",
                    true, (_, _) => Task.FromResult(DesktopStatus())),
                new("virtual_desktop_status",
                    "Report the Windows virtual desktop count and the 1-based number of the desktop the user is viewing. Requires VirtualDesktopAccessor.dll (see the Omni documentation).",
                    """{"type":"object","properties":{}}""",
                    true, (_, _) => Task.FromResult(DesktopStatus())),
                new("virtual_desktop_create",
                    "Create a new virtual desktop at the end of the list and return its number. Use it as a separate desktop for agent windows; the user's view does not switch.",
                    """{"type":"object","properties":{}}""",
                    false, (_, _) => Task.FromResult($"created virtual desktop {_virtualDesktops.Create()}")),
                new("virtual_desktop_move_window",
                    "Move a window to a virtual desktop by its 1-based number. Use virtual_desktop_status to see the count first.",
                    """{"type":"object","properties":{"hwnd":{"type":"integer"},"desktop_number":{"type":"integer","minimum":1}},"required":["hwnd","desktop_number"]}""",
                    false, (args, _) =>
                    {
                        var hwnd = Hwnd(args);
                        int number = RequiredInt(args, "desktop_number");
                        _virtualDesktops.MoveWindow(hwnd, number);
                        return Task.FromResult($"moved window {hwnd} to desktop {number}");
                    }),
                new("virtual_desktop_window_desktop",
                    "Report which virtual desktop a window is on, as a 1-based number.",
                    """{"type":"object","properties":{"hwnd":{"type":"integer"}},"required":["hwnd"]}""",
                    true, (args, _) =>
                    {
                        var hwnd = Hwnd(args);
                        int? number = _virtualDesktops.DesktopOf(hwnd);
                        return Task.FromResult(number is int n ? $"window {hwnd} is on desktop {n}" : $"Windows does not report a desktop for window {hwnd}");
                    }),
                new("get_available_monitor",
                    "Run once when starting work. Reports which monitor is least disruptive to use for agent work: the one without the focused window and with the fewest visible windows on it. Agents should work there rather than on the monitor the user is using. Re-run only if monitors are plugged in or removed, or the user says they changed screens; focus can move at any time, so do not treat the result as permanent.",
                    """{"type":"object","properties":{}}""",
                    true, (_, _) => Task.FromResult(AvailableMonitor())),
            };
            _tools = tools.ToDictionary(t => t.Name);
        }

        public bool Contains(string name) => _tools.ContainsKey(name);

        public JsonArray DescribeTools() => new(_tools.Values.Select(t => (JsonNode)new JsonObject
        {
            ["name"] = t.Name,
            ["description"] = t.Description,
            ["inputSchema"] = JsonNode.Parse(t.SchemaJson),
            ["annotations"] = new JsonObject { ["readOnlyHint"] = t.ReadOnly },
        }).ToArray());

        public async Task<McpToolResult> InvokeAsync(string name, JsonObject arguments, CancellationToken ct) =>
            McpToolResult.Ok(await _tools[name].Run(arguments, ct));

        private string ListWindows()
        {
            var sb = new StringBuilder();
            foreach (var w in _ui.ListWindows())
                sb.Append($"hwnd={w.Hwnd} pid={w.ProcessId} process={w.ProcessName} class={w.ClassName} rect={w.Rect} title={w.Title}\n");
            return sb.Length == 0 ? "no visible windows with titles" : sb.ToString();
        }

        private string DesktopStatus()
        {
            if (!_virtualDesktops.Available)
                return $"virtual desktop accessor not loaded (looked for {_virtualDesktops.Path})";
            return $"virtual desktops: {_virtualDesktops.Count}, viewing desktop {_virtualDesktops.Current}";
        }

        /// Picks the monitor the user is not looking at, and of those the one with the fewest visible windows on it.
        private static string AvailableMonitor()
        {
            var screens = Screen.AllScreens;
            var focusHandle = NativeWindows.GetForegroundWindow();
            var focused = focusHandle == IntPtr.Zero ? Screen.PrimaryScreen : Screen.FromHandle(focusHandle);

            var counts = new Dictionary<string, int>();
            foreach (var screen in screens) counts[screen.DeviceName] = 0;
            NativeWindows.EnumWindows((hwnd, _) =>
            {
                if (!NativeWindows.IsWindowVisible(hwnd) || NativeWindows.IsCloaked(hwnd) || NativeWindows.TitleLength(hwnd) == 0) return true;
                if (!NativeWindows.GetWindowRect(hwnd, out var r) || r.Right <= r.Left) return true;
                int cx = (r.Left + r.Right) / 2, cy = (r.Top + r.Bottom) / 2;
                foreach (var screen in screens)
                {
                    var b = screen.Bounds;
                    if (cx >= b.Left && cx < b.Right && cy >= b.Top && cy < b.Bottom) { counts[screen.DeviceName]++; break; }
                }
                return true;
            }, IntPtr.Zero);

            var candidates = screens
                .Where(s => s.DeviceName != focused.DeviceName)
                .Select(s => new { Screen = s, Windows = counts[s.DeviceName] })
                .OrderBy(c => c.Windows)
                .ThenBy(c => c.Screen.Primary) // equal load: prefer the non-primary screen, which the user usually keeps for other things
                .ToList();

            var recommended = candidates.FirstOrDefault();
            var summary = screens.Select(s => new
            {
                Device = s.DeviceName,
                Primary = s.Primary,
                Bounds = new { s.Bounds.Left, s.Bounds.Top, s.Bounds.Width, s.Bounds.Height },
                VisibleWindows = counts[s.DeviceName],
                HasFocus = s.DeviceName == focused.DeviceName,
            }).ToArray();

            return JsonSerializer.Serialize(new
            {
                MonitorCount = screens.Length,
                FocusedMonitor = focused.DeviceName,
                Recommended = recommended == null ? null : new
                {
                    Device = recommended.Screen.DeviceName,
                    Bounds = new { recommended.Screen.Bounds.Left, recommended.Screen.Bounds.Top, recommended.Screen.Bounds.Width, recommended.Screen.Bounds.Height },
                    VisibleWindows = recommended.Windows,
                },
                Reason = recommended == null
                    ? "Only one monitor is connected, so agent work would share the monitor the user is using."
                    : $"{recommended.Screen.DeviceName} does not hold the focused window and has {recommended.Windows} visible window(s), the fewest of the other monitors.",
                Monitors = summary,
            }, new JsonSerializerOptions { WriteIndented = true });
        }

        private static class NativeWindows
        {
            public delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

            [StructLayout(LayoutKind.Sequential)]
            public struct RECT { public int Left, Top, Right, Bottom; }

            [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
            [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
            [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
            [DllImport("user32.dll", SetLastError = true)] public static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
            [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextLengthW(IntPtr hwnd);
            [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr hwnd, int attribute, out int value, int size);

            public static int TitleLength(IntPtr hwnd) => GetWindowTextLengthW(hwnd);

            /// DWM cloaking hides windows on other virtual desktops and suspended UWP windows; they are not on any monitor the user sees.
            public static bool IsCloaked(IntPtr hwnd)
            {
                try { return DwmGetWindowAttribute(hwnd, 14, out int cloak, sizeof(int)) == 0 && cloak != 0; }
                catch (DllNotFoundException) { return false; }
            }
        }

        private static IntPtr Hwnd(JsonObject args) => new(RequiredLong(args, "hwnd"));

        private static string Req(JsonObject args, string key) =>
            Opt(args, key) ?? throw new ArgumentException($"Missing required argument '{key}'.");

        private static string? Opt(JsonObject args, string key) =>
            args[key] is JsonValue v && v.TryGetValue(out string? s) ? s : args[key]?.ToString();

        private static int OptInt(JsonObject args, string key, int fallback) =>
            args[key] is JsonValue v && v.TryGetValue(out int i) ? i : fallback;

        private static int RequiredInt(JsonObject args, string key) =>
            args[key] is JsonValue v && v.TryGetValue(out int i) ? i : throw new ArgumentException($"Missing required integer argument '{key}'.");

        private static long RequiredLong(JsonObject args, string key) =>
            args[key] is JsonValue v && v.TryGetValue(out long l) ? l : throw new ArgumentException($"Missing required integer argument '{key}'.");
    }
}
