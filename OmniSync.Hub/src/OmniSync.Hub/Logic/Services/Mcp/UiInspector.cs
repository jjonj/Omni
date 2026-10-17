using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Automation;

namespace OmniSync.Hub.Logic.Services.Mcp
{
    /// Windows UI Automation for inspecting and driving app windows. Elements are addressed by a child-index path
    /// from the window root ("" is the window, "0/2" is the third child of the first child), so a snapshot taken
    /// with Inspect is enough to act on the same tree.
    public sealed class UiInspector
    {
        private const int MaxNameChars = 200;

        public sealed record WindowInfo(long Hwnd, int ProcessId, string ProcessName, string Title, string ClassName, string Rect);

        private delegate bool EnumWindowsProc(IntPtr hwnd, IntPtr lParam);

        [DllImport("user32.dll")] private static extern bool EnumWindows(EnumWindowsProc callback, IntPtr lParam);
        [DllImport("user32.dll")] private static extern bool IsWindowVisible(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hwnd);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextLengthW(IntPtr hwnd);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextW(IntPtr hwnd, StringBuilder text, int maxCount);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassNameW(IntPtr hwnd, StringBuilder className, int maxCount);
        [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint processId);
        [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hwnd);
        [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
        [DllImport("user32.dll")] private static extern void mouse_event(uint flags, uint dx, uint dy, uint data, UIntPtr extraInfo);

        private const uint MouseLeftDown = 0x0002;
        private const uint MouseLeftUp = 0x0004;

        public IReadOnlyList<WindowInfo> ListWindows()
        {
            var windows = new List<WindowInfo>();
            EnumWindows((hwnd, _) =>
            {
                if (!IsWindowVisible(hwnd)) return true;
                int length = GetWindowTextLengthW(hwnd);
                if (length == 0) return true;
                var title = new StringBuilder(length + 1);
                GetWindowTextW(hwnd, title, title.Capacity);
                var className = new StringBuilder(256);
                GetClassNameW(hwnd, className, className.Capacity);
                GetWindowThreadProcessId(hwnd, out uint pid);
                string processName = SafeProcessName((int)pid);
                string rect = "";
                try
                {
                    var bounds = AutomationElement.FromHandle(hwnd).Current.BoundingRectangle;
                    rect = $"{(int)bounds.X},{(int)bounds.Y},{(int)bounds.Width},{(int)bounds.Height}";
                }
                catch (ElementNotAvailableException) { }
                windows.Add(new WindowInfo(hwnd.ToInt64(), (int)pid, processName, title.ToString(), className.ToString(), rect));
                return true;
            }, IntPtr.Zero);
            return windows;
        }

        /// Text dump of the element tree under the window, stopping at maxDepth and maxElements.
        public string Inspect(IntPtr hwnd, int maxDepth, int maxElements)
        {
            var root = AutomationElement.FromHandle(hwnd);
            var sb = new StringBuilder();
            int count = 0;
            bool truncated = false;
            Dump(root, "", 0, maxDepth, maxElements, sb, ref count, ref truncated);
            if (truncated) sb.Append($"... truncated at {maxElements} elements; raise max_elements or lower max_depth to see more\n");
            return sb.ToString();
        }

        private static void Dump(AutomationElement element, string path, int depth, int maxDepth, int maxElements,
            StringBuilder sb, ref int count, ref bool truncated)
        {
            if (count >= maxElements) { truncated = true; return; }
            count++;
            string indent = new(' ', depth * 2);
            sb.Append(indent).Append('[').Append(path.Length == 0 ? "root" : path).Append("] ");
            sb.Append(ControlTypeName(element));
            string name = Trim(element.Current.Name);
            if (name.Length > 0) sb.Append(" \"").Append(name).Append('"');
            string automationId = element.Current.AutomationId;
            if (!string.IsNullOrEmpty(automationId)) sb.Append(" id=").Append(automationId);
            string value = CurrentValue(element);
            if (value.Length > 0) sb.Append(" value=\"").Append(Trim(value)).Append('"');
            if (!element.Current.IsEnabled) sb.Append(" disabled");
            var patterns = SupportedPatterns(element);
            if (patterns.Length > 0) sb.Append(" patterns=").Append(patterns);
            var rect = element.Current.BoundingRectangle;
            if (!rect.IsEmpty) sb.Append(" rect=").Append((int)rect.X).Append(',').Append((int)rect.Y).Append(',').Append((int)rect.Width).Append(',').Append((int)rect.Height);
            sb.Append('\n');

            if (depth >= maxDepth) return;
            var walker = TreeWalker.ControlViewWalker;
            var child = SafeFirstChild(walker, element);
            int index = 0;
            while (child != null)
            {
                if (count >= maxElements) { truncated = true; return; }
                string childPath = path.Length == 0 ? index.ToString() : $"{path}/{index}";
                Dump(child, childPath, depth + 1, maxDepth, maxElements, sb, ref count, ref truncated);
                child = SafeNextSibling(walker, child);
                index++;
            }
        }

        /// Runs one action on the element at path. Actions: invoke, toggle, select, set_value, focus, click, send_keys.
        public string Act(IntPtr hwnd, string path, string action, string? value, bool allowFocusChange = false)
        {
            if (!allowFocusChange && action is "focus" or "click" or "send_keys")
                throw new InvalidOperationException("This action may steal keyboard focus. Specify focus_policy=allow explicitly, or use invoke/set_value for supported controls.");
            var element = Resolve(AutomationElement.FromHandle(hwnd), path)
                ?? throw new ArgumentException($"No element at path '{path}'. Run ui_inspect again; the tree may have changed.");

            switch (action)
            {
                case "invoke":
                    Pattern(element, InvokePattern.Pattern, (InvokePattern p) => p.Invoke());
                    return "invoked";
                case "toggle":
                    Pattern(element, TogglePattern.Pattern, (TogglePattern p) => p.Toggle());
                    return "toggled";
                case "select":
                    Pattern(element, SelectionItemPattern.Pattern, (SelectionItemPattern p) => p.Select());
                    return "selected";
                case "set_value":
                    if (value == null) throw new ArgumentException("set_value requires value.");
                    Pattern(element, ValuePattern.Pattern, (ValuePattern p) => p.SetValue(value));
                    return "value set";
                case "focus":
                    element.SetFocus();
                    return "focused";
                case "click":
                    ClickCenter(hwnd, element);
                    return "clicked at element centre (physical mouse moved)";
                case "send_keys":
                    if (string.IsNullOrEmpty(value)) throw new ArgumentException("send_keys requires value.");
                    element.SetFocus();
                    SendKeysOnStaThread(value);
                    return "keys sent to the focused element";
                default:
                    throw new ArgumentException($"Unknown action '{action}'. Use invoke, toggle, select, set_value, focus, click or send_keys.");
            }
        }

        private static void Pattern<T>(AutomationElement element, AutomationPattern pattern, Action<T> run) where T : BasePattern
        {
            if (!element.TryGetCurrentPattern(pattern, out object? raw) || raw is not T typed)
                throw new InvalidOperationException($"Element does not support {pattern.ProgrammaticName}. A focus-changing click/send_keys requires explicit permission.");
            run(typed);
        }

        private static void ClickCenter(IntPtr hwnd, AutomationElement element)
        {
            var rect = element.Current.BoundingRectangle;
            if (rect.IsEmpty) throw new InvalidOperationException("Element has no on-screen position to click.");
            SetForegroundWindow(hwnd);
            SetCursorPos((int)(rect.X + rect.Width / 2), (int)(rect.Y + rect.Height / 2));
            mouse_event(MouseLeftDown, 0, 0, 0, UIntPtr.Zero);
            mouse_event(MouseLeftUp, 0, 0, 0, UIntPtr.Zero);
        }

        private static void SendKeysOnStaThread(string keys)
        {
            // SendKeys must run on an STA thread; the MCP request runs on the thread pool.
            Exception? failure = null;
            var thread = new Thread(() =>
            {
                try { System.Windows.Forms.SendKeys.SendWait(keys); }
                catch (Exception ex) { failure = ex; }
            });
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            thread.Join();
            if (failure != null) throw failure;
        }

        private static AutomationElement? Resolve(AutomationElement root, string path)
        {
            var current = root;
            if (string.IsNullOrEmpty(path)) return current;
            foreach (var segment in path.Split('/'))
            {
                if (!int.TryParse(segment, out int index) || index < 0) return null;
                var walker = TreeWalker.ControlViewWalker;
                var child = SafeFirstChild(walker, current);
                for (int i = 0; i < index && child != null; i++) child = SafeNextSibling(walker, child);
                if (child == null) return null;
                current = child;
            }
            return current;
        }

        private static AutomationElement? SafeFirstChild(TreeWalker walker, AutomationElement element)
        {
            try { return walker.GetFirstChild(element); }
            catch (ElementNotAvailableException) { return null; }
        }

        private static AutomationElement? SafeNextSibling(TreeWalker walker, AutomationElement element)
        {
            try { return walker.GetNextSibling(element); }
            catch (ElementNotAvailableException) { return null; }
        }

        private static string ControlTypeName(AutomationElement element)
        {
            string name = element.Current.ControlType.ProgrammaticName;
            return name.StartsWith("ControlType.", StringComparison.Ordinal) ? name["ControlType.".Length..] : name;
        }

        private static string CurrentValue(AutomationElement element)
        {
            if (!element.TryGetCurrentPattern(ValuePattern.Pattern, out object? raw) || raw is not ValuePattern value) return "";
            try { return value.Current.Value ?? ""; }
            catch (ElementNotAvailableException) { return ""; }
        }

        private static string SupportedPatterns(AutomationElement element)
        {
            var names = new List<string>();
            foreach (var pattern in element.GetSupportedPatterns())
            {
                // ProgrammaticName looks like "InvokePatternIdentifiers.Pattern"; keep "Invoke".
                string name = pattern.ProgrammaticName;
                int cut = name.IndexOf("PatternIdentifiers", StringComparison.Ordinal);
                names.Add(cut > 0 ? name[..cut] : name);
            }
            return string.Join(",", names);
        }

        private static string Trim(string text) =>
            text.Length <= MaxNameChars ? text : text[..MaxNameChars] + "...";

        private static string SafeProcessName(int pid)
        {
            try { return Process.GetProcessById(pid).ProcessName; }
            catch (ArgumentException) { return ""; }
            catch (InvalidOperationException) { return ""; }
        }
    }
}
