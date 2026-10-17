using System.Drawing;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Windows.Forms;
using Wpf = System.Windows;

internal static class Program
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(15) };
    private static string _url = "http://127.0.0.1:8770/mcp";
    private static int _id;

    [STAThread]
    private static void Main(string[] args)
    {
        if (args.Length > 0) _url = args[0];
        Native.SetThreadDpiAwarenessContext(new IntPtr(-4));
        using var form = new TestWindow();
        form.Shown += async (_, _) =>
        {
            var wpfCount = 0;
            var wpfButton = new System.Windows.Controls.Button { Content = "WPF background click", Width = 230, Height = 70 };
            var wpf = new Wpf.Window
            {
                Title = "Omni AI cursor - WPF test", Width = 310, Height = 180,
                Left = form.Left + form.Width + 10, Top = form.Top,
                ShowActivated = false, Topmost = true, Content = wpfButton
            };
            wpfButton.Click += (_, _) => { wpfButton.Content = $"WPF clicks: {++wpfCount}"; };
            wpf.Show();
            try
            {
                await Task.Delay(300); // Let the initial non-activating windows finish positioning.
                Native.SetWindowPos(form.Handle, new IntPtr(-1), 0, 0, 0, 0, 0x0010 | 0x0001 | 0x0002 | 0x0040);
                if (!args.Contains("--demo")) await Test(form, wpfButton, () => wpfCount);
                if (args.Contains("--demo"))
                {
                    form.Text = "Omni AI cursor - demo (close to dismiss)";
                    form.Caption.Text = "The blue AI pointer clicked the button. Your mouse is independent. Close this window to hide it.";
                    var point = form.NativeButton.PointToScreen(new Point(20, 20));
                    await Call("agent_cursor_move", new { x = point.X, y = point.Y });
                    await Call("agent_cursor_click", new { mode = "messages" });
                    Console.WriteLine("DEMO: blue AI cursor clicked the native button. Close the native test window to hide the cursor and dismiss the demo.");
                    var dismissing = false;
                    form.FormClosing += async (_, closing) =>
                    {
                        if (dismissing) return;
                        closing.Cancel = true;
                        dismissing = true;
                        try { await Call("agent_cursor_hide", new { }); } catch { }
                        wpf.Close();
                        form.Close();
                    };
                    return;
                }
                Environment.ExitCode = 0;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine(ex);
                Environment.ExitCode = 1;
            }
            finally
            {
                if (!args.Contains("--demo") || Environment.ExitCode != 0)
                {
                    try { await Call("agent_cursor_hide", new { }); } catch { }
                    wpf.Close();
                    form.Close();
                }
            }
        };
        System.Windows.Forms.Application.Run(form);
    }

    private static async Task Test(TestWindow form, System.Windows.Controls.Button wpfButton, Func<int> wpfCount)
    {
        var list = await Rpc("tools/list", new { });
        var names = list.GetProperty("tools").EnumerateArray().Select(x => x.GetProperty("name").GetString()).ToArray();
        Check(new[] { "agent_cursor_status", "agent_cursor_move", "agent_cursor_click", "agent_cursor_hide" }.All(names.Contains), "MCP advertises four cursor tools");
        await Call("agent_cursor_hide", new { });
        await ExpectError("agent_cursor_click", new { }, "hidden cursor rejects clicks");
        await ExpectError("agent_cursor_move", new { y = 1 }, "missing coordinates rejected");
        await ExpectError("agent_cursor_move", new { x = int.MaxValue, y = int.MaxValue }, "off-monitor position rejected");

        var target = form.NativeButton.PointToScreen(new Point(form.NativeButton.Width / 2, form.NativeButton.Height / 2));
        await MoveUnchanged(target);
        var state = await Call("agent_cursor_status", new { });
        Check(state.GetProperty("Visible").GetBoolean(), "overlay is visible");
        Check(state.GetProperty("Target").GetProperty("WindowHandle").GetString() == form.NativeButton.Handle.ToInt64().ToString(), "hit test skips overlay and finds native button");
        Check(Native.WindowFromPoint(new Point(target.X + 5, target.Y + 8)) == form.NativeButton.Handle,
            "visible arrow passes normal Windows hit testing through to the user's target");
        await ClickUnchanged(new { mode = "messages" });
        await WaitFor(() => form.Clicks == 1, "native WinForms button actually clicked");

        await Task.Delay(3000);
        await Call("agent_cursor_status", new { }); // Reading status must not extend visibility.
        await Task.Delay(2500);
        var idle = await Call("agent_cursor_status", new { });
        Check(!idle.GetProperty("Visible").GetBoolean(), "cursor auto-hides after five seconds idle");
        Check(idle.GetProperty("Ready").GetBoolean(), "idle hiding preserves readiness to click");
        Check(idle.GetProperty("X").GetInt32() == target.X && idle.GetProperty("Y").GetInt32() == target.Y,
            "idle hiding retains agent coordinates");
        await ClickUnchanged(new { mode = "messages" });
        await WaitFor(() => form.Clicks == 2, "click after idle hiding works without another move");
        var resumed = await Call("agent_cursor_status", new { });
        Check(resumed.GetProperty("Visible").GetBoolean(), "resumed click shows the cursor again");
        await Task.Delay(3000);
        await MoveUnchanged(target);
        await Task.Delay(3000);
        Check((await Call("agent_cursor_status", new { })).GetProperty("Visible").GetBoolean(),
            "movement restarts the idle timeout");

        target = form.Surface.PointToScreen(new Point(40, 35));
        await MoveUnchanged(target);
        await ClickUnchanged(new { button = "right" });
        await WaitFor(() => form.Surface.RightUps == 1, "right-button messages received at agent position");
        Check(form.Surface.LastPoint == new Point(40, 35), "screen coordinates converted to target client coordinates");
        await ClickUnchanged(new { count = 2 });
        await WaitFor(() => form.Surface.LeftUps == 2 && form.Surface.DoubleClicks == 1, "double-click sequence delivered");
        await ExpectError("agent_cursor_click", new { button = "middle" }, "unsupported button rejected");
        await ExpectError("agent_cursor_click", new { count = 3 }, "invalid click count rejected");
        await ExpectError("agent_cursor_click", new { mode = "unknown" }, "invalid click mode rejected");
        await ExpectError("agent_cursor_click", new { mode = "uia", button = "right" }, "UIA right-click rejected");

        form.NativeButton.Enabled = false;
        var originalClicks = form.Clicks;
        target = form.NativeButton.PointToScreen(new Point(20, 20));
        await MoveUnchanged(target);
        // An enabled parent may be the hit-test target; the disabled button must not run.
        await Rpc("tools/call", new { name = "agent_cursor_click", arguments = new { } });
        await Task.Delay(80);
        Check(form.Clicks == originalClicks, "disabled button does not execute");
        form.NativeButton.Enabled = true;

        var wpfPoint = wpfButton.PointToScreen(new Wpf.Point(wpfButton.ActualWidth / 2, wpfButton.ActualHeight / 2));
        await MoveUnchanged(new Point((int)wpfPoint.X, (int)wpfPoint.Y));
        await ClickUnchanged(new { }, expectForegroundUnchanged: false);
        await WaitFor(() => wpfCount() == 1, "UI Automation actually clicks the WPF button without moving the physical pointer");

        // Every attached display, including negative origins, uses physical pixels.
        foreach (var monitor in state.GetProperty("Monitors").EnumerateArray())
        {
            var point = new Point(monitor.GetProperty("X").GetInt32() + monitor.GetProperty("Width").GetInt32() / 2,
                monitor.GetProperty("Y").GetInt32() + monitor.GetProperty("Height").GetInt32() / 2);
            await MoveUnchanged(point);
            var moved = await Call("agent_cursor_status", new { });
            Check(moved.GetProperty("X").GetInt32() == point.X && moved.GetProperty("Y").GetInt32() == point.Y, $"monitor coordinates round-trip at {point}");
        }
        await MoveUnchanged(new Point(form.Left + 90, form.Top + 8));
        await ExpectError("agent_cursor_click", new { }, "title-bar click rejected");
        var hidden = await Call("agent_cursor_hide", new { });
        Check(!hidden.GetProperty("Visible").GetBoolean(), "hide removes the overlay");
        Check(!hidden.GetProperty("Ready").GetBoolean(), "explicit hide disarms clicks");
        Console.WriteLine("All agent cursor integration checks passed.");
    }

    private static async Task MoveUnchanged(Point point)
    {
        Native.GetCursorPos(out var mouse);
        var foreground = Native.GetForegroundWindow();
        await Call("agent_cursor_move", new { x = point.X, y = point.Y });
        Native.GetCursorPos(out var after);
        Check(mouse == after, "move preserves physical mouse position");
        Check(foreground == Native.GetForegroundWindow(), "move preserves foreground window");
    }

    private static async Task ClickUnchanged(object args, bool expectForegroundUnchanged = true)
    {
        Native.GetCursorPos(out var mouse);
        var foreground = Native.GetForegroundWindow();
        var result = await Call("agent_cursor_click", args);
        await Task.Delay(80); // Wait for the recipient to process queued input.
        Native.GetCursorPos(out var after);
        Check(result.GetProperty("Delivery").GetString() is "posted" or "invoked", $"click reports delivery method: {result.GetProperty("Method").GetString()}");
        Check(mouse == after, "click preserves physical mouse position after target processing");
        if (expectForegroundUnchanged)
            Check(foreground == Native.GetForegroundWindow(), "click preserves foreground window after target processing");
        else
            Console.WriteLine($"OBSERVED: UIA foreground changed: {foreground != Native.GetForegroundWindow()} (app/provider behavior; pointer unchanged).");
    }

    private static async Task WaitFor(Func<bool> predicate, string label)
    {
        for (var attempt = 0; attempt < 30 && !predicate(); attempt++) await Task.Delay(30);
        Check(predicate(), label);
    }

    private static async Task<JsonElement> Rpc(string method, object parameters)
    {
        var data = JsonSerializer.Serialize(new { jsonrpc = "2.0", id = ++_id, method, @params = parameters });
        using var response = await Http.PostAsync(_url, new StringContent(data, Encoding.UTF8, "application/json"));
        response.EnsureSuccessStatusCode();
        using var parsed = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
        var root = parsed.RootElement;
        if (root.TryGetProperty("error", out var error)) throw new Exception(error.ToString());
        return root.GetProperty("result").Clone();
    }

    private static async Task<JsonElement> Call(string name, object args)
    {
        var result = await Rpc("tools/call", new { name, arguments = args });
        var text = result.GetProperty("content")[0].GetProperty("text").GetString()!;
        if (result.GetProperty("isError").GetBoolean()) throw new Exception($"{name}: {text}");
        using var parsed = JsonDocument.Parse(text);
        return parsed.RootElement.Clone();
    }

    private static async Task ExpectError(string name, object args, string label)
    {
        var result = await Rpc("tools/call", new { name, arguments = args });
        Check(result.GetProperty("isError").GetBoolean(), label);
    }

    private static void Check(bool value, string label)
    {
        if (!value) throw new Exception($"FAIL: {label}");
        Console.WriteLine($"PASS: {label}");
    }

    private sealed class TestWindow : Form
    {
        public readonly Label Caption = new() { Location = new Point(20, 15), Size = new Size(360, 55), Text = "AI cursor test: your mouse stays independent." };
        public readonly Button NativeButton = new() { Location = new Point(25, 80), Size = new Size(250, 65), Text = "Native background click" };
        public readonly ClickSurface Surface = new() { Location = new Point(25, 165), Size = new Size(250, 90), BackColor = Color.LightSkyBlue };
        public int Clicks;
        public TestWindow()
        {
            Text = "Omni AI cursor - native test";
            AutoScaleMode = AutoScaleMode.None;
            Size = new Size(410, 340);
            StartPosition = FormStartPosition.Manual;
            var screen = Screen.PrimaryScreen!.WorkingArea;
            Location = new Point(screen.Left + 80, screen.Top + 80);
            TopMost = true;
            Controls.AddRange([Caption, NativeButton, Surface]);
            NativeButton.Click += (_, _) => NativeButton.Text = $"Native clicks: {++Clicks}";
        }
        protected override bool ShowWithoutActivation => true;
    }

    private sealed class ClickSurface : Control
    {
        public int RightUps, LeftUps, DoubleClicks;
        public Point LastPoint;
        protected override CreateParams CreateParams
        {
            get { var cp = base.CreateParams; cp.ClassStyle |= 8; return cp; }
        }
        protected override void WndProc(ref Message m)
        {
            if (m.Msg is 0x0202 or 0x0203 or 0x0205)
            {
                if (m.Msg == 0x0202) LeftUps++;
                if (m.Msg == 0x0203) DoubleClicks++;
                if (m.Msg == 0x0205) RightUps++;
                LastPoint = new Point((short)m.LParam.ToInt64(), (short)(m.LParam.ToInt64() >> 16));
                Invalidate();
                return; // No capture/focus: this surface records raw message semantics.
            }
            base.WndProc(ref m);
        }
        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            TextRenderer.DrawText(e.Graphics, $"Right: {RightUps}  Left: {LeftUps}\nDouble: {DoubleClicks}", Font, new Point(10, 10), Color.Black);
        }
    }

    private static class Native
    {
        [DllImport("user32.dll")] internal static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int width, int height, uint flags);
        [DllImport("user32.dll")] internal static extern IntPtr WindowFromPoint(Point point);
        [DllImport("user32.dll")] internal static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
        [DllImport("user32.dll")] internal static extern bool GetCursorPos(out Point point);
        [DllImport("user32.dll")] internal static extern IntPtr GetForegroundWindow();
    }
}
