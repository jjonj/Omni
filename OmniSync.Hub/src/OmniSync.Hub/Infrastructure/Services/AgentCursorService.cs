using System.ComponentModel;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;
using System.Windows.Automation;
using OmniSync.Hub.Logic.Monitoring;
using OmniSync.Hub.Logic.Services.Mcp;

namespace OmniSync.Hub.Infrastructure.Services;

/// <summary>
/// A visible, independent pointer. UIA and native HWND messages do not move the system mouse.
/// Native mouse messages (including right-click and wheel) are preferred for Slate/UMG;
/// Windows touch and physical SendInput are explicit alternative methods, never automatic fallbacks.
/// Message delivery does not prove an app completed its action.
/// </summary>
public sealed class AgentCursorService : IDisposable
{
    private readonly HubMonitorService _monitor;
    private readonly Thread _thread;
    private readonly TaskCompletionSource<CursorOverlay> _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private Point _position;
    private bool _hasPosition;
    private IntPtr _boundWindow;
    private int? _boundDesktop;
    private int _disposed;
    private Task<string?>? _automationTask;

    public AgentCursorService(HubMonitorService monitor)
    {
        _monitor = monitor;
        _thread = new Thread(Run) { IsBackground = true, Name = "Omni agent cursor" };
        _thread.SetApartmentState(ApartmentState.STA);
        _thread.Start();
    }

    private void Run()
    {
        try
        {
            // Coordinates are physical desktop pixels, including negative monitor origins.
            Native.SetThreadDpiAwarenessContext(new IntPtr(-4)); // PER_MONITOR_AWARE_V2
            using var overlay = new CursorOverlay();
            _ = overlay.Handle; // Create the HWND without showing or activating it.
            var primary = Screen.PrimaryScreen!.Bounds;
            _position = new Point(primary.Left + primary.Width / 2, primary.Top + primary.Height / 2);
            _ready.TrySetResult(overlay);
            if (Volatile.Read(ref _disposed) != 0) return;
            System.Windows.Forms.Application.Run();
        }
        catch (Exception ex)
        {
            _ready.TrySetException(ex);
        }
    }

    public Task<AgentCursorState> StatusAsync(CancellationToken ct = default) => OnThread(overlay => State(overlay), ct);

    public Task<AgentCursorState> MoveAsync(int x, int y, CancellationToken ct = default) =>
        MoveAsync(x, y, null, null, ct);

    /// <summary>
    /// Bind the logical cursor to a window on any Windows virtual desktop. A background
    /// binding stays invisible on the user's desktop; input uses native child HWNDs,
    /// never screen hit testing of the user's foreground workspace.
    /// </summary>
    public Task<AgentCursorState> MoveAsync(int x, int y, long? targetHwnd, int? desktopNumber,
        CancellationToken ct = default) => OnThread(overlay =>
    {
        var point = new Point(x, y);
        if (!Screen.AllScreens.Any(screen => screen.Bounds.Contains(point)))
            throw new ArgumentException("Coordinates must lie on a connected monitor (physical desktop pixels).");
        using var desktops = new VirtualDesktopService();
        int? current = desktops.Available ? desktops.Current : null;
        IntPtr bound = IntPtr.Zero;
        int? destination = null;
        if (targetHwnd.HasValue)
        {
            bound = new IntPtr(targetHwnd.Value);
            if (bound == IntPtr.Zero || !Native.IsWindow(bound))
                throw new ArgumentException($"Invalid/stale target_hwnd {targetHwnd.Value}.");
            if (!desktops.Available)
                throw new InvalidOperationException("Virtual desktop lookup is unavailable; cannot bind the cursor to a background window.");
            destination = desktops.DesktopOf(Native.GetAncestor(bound, 2));
            if (!destination.HasValue)
                throw new InvalidOperationException("The target HWND has no recognized virtual desktop.");
            if (desktopNumber.HasValue && desktopNumber.Value != destination.Value)
                throw new ArgumentException($"target_hwnd is on desktop {destination}, not desktop {desktopNumber}.");
            if (!ContainsWindowPoint(bound, point))
            {
                Native.GetWindowRect(bound, out var targetBounds);
                throw new ArgumentException($"Cursor {x},{y} is outside target HWND bounds " +
                    $"({targetBounds.Left},{targetBounds.Top})-({targetBounds.Right},{targetBounds.Bottom}) in this DPI context.");
            }
        }
        else if (desktopNumber.HasValue && desktopNumber != current)
        {
            throw new ArgumentException("A target_hwnd is required for an inactive virtual desktop; screen hit testing only sees the active desktop.");
        }
        _position = point;
        _hasPosition = true;
        _boundWindow = bound;
        _boundDesktop = destination;
        if (destination.HasValue && destination != current) overlay.Hide();
        else overlay.MoveTo(point);
        return State(overlay);
    }, ct);

    public Task<AgentCursorState> HideAsync(CancellationToken ct = default) => OnThread(overlay =>
    {
        overlay.Hide();
        _hasPosition = false;
        _boundWindow = IntPtr.Zero;
        _boundDesktop = null;
        _monitor.AddLogMessage("[AgentCursor] Hidden.");
        return State(overlay);
    }, ct);

    public Task<AgentCursorClickResult> ClickAsync(string button = "left", int count = 1, string mode = "auto", CancellationToken ct = default) => OnThread(overlay =>
    {
        button = button?.ToLowerInvariant() ?? "";
        mode = mode?.ToLowerInvariant() ?? "";
        if (button is not ("left" or "right")) throw new ArgumentException("button must be left or right.");
        if (count is not (1 or 2)) throw new ArgumentException("count must be 1 or 2.");
        if (mode is not ("auto" or "messages" or "uia" or "touch" or "physical")) throw new ArgumentException("mode must be auto, messages, uia, touch or physical.");
        if (mode == "touch" && button != "left") throw new ArgumentException("Synthetic touch supports left/tap only.");
        if (mode == "uia" && (button != "left" || count != 1))
            throw new ArgumentException("UI Automation supports single left clicks only.");
        if (!_hasPosition) throw new InvalidOperationException("Move the agent cursor before clicking; it has no active position.");
        if (_automationTask is { IsCompleted: false })
            throw new InvalidOperationException("A previous UI Automation request is still pending. No additional click was sent.");

        // Never reveal a background-desktop pointer on the user's active desktop.
        // Bound targets never use UIA FromPoint, which would hit-test the active desktop.
        bool bound = _boundWindow != IntPtr.Zero;
        if (bound)
        {
            if (mode == "uia")
                throw new InvalidOperationException("UIA FromPoint is unavailable for explicit window bindings. Use mode=messages or auto.");
            if (!Native.IsWindow(_boundWindow))
                throw new InvalidOperationException("The bound HWND has closed. Move the cursor to a valid window before clicking.");
            using var desktops = new VirtualDesktopService();
            if (!desktops.Available || desktops.DesktopOf(Native.GetAncestor(_boundWindow, 2)) != _boundDesktop)
                throw new InvalidOperationException("The bound HWND's desktop changed or is unavailable. Move the cursor again before clicking.");
            if (_boundDesktop != desktops.Current) overlay.Hide();
            else overlay.MoveTo(_position);
        }
        else overlay.MoveTo(_position);

        var target = FindTarget(overlay);
        if (target == IntPtr.Zero) throw new InvalidOperationException("No enabled window under the agent cursor.");
        var client = _position;
        if (!Native.ScreenToClient(target, ref client) || !Native.GetClientRect(target, out var rect))
            throw new Win32Exception(Marshal.GetLastWin32Error());
        if (client.X < rect.Left || client.X >= rect.Right || client.Y < rect.Top || client.Y >= rect.Bottom)
            throw new InvalidOperationException("This POC clicks client areas only; title bars and window borders are unsupported.");
        if (client.X > short.MaxValue || client.Y > short.MaxValue)
            throw new InvalidOperationException("Target client coordinates exceed the Win32 mouse-message range.");

        var before = Snapshot();

        // Proper DPI-aware window coordinates fixed the earlier Unreal click failures:
        // the working LIVE click used "window-messages" rather than Windows touch.
        // Prefer exact native mouse messages for Slate/UMG so left/right buttons work
        // and target windows can remain in the background. Touch is opt-in only.
        // Touch is screen-routed: NEVER inject it into a covered window or another desktop.
        bool isUnreal = DescribeTarget(target).ClassName.Equals("UnrealWindow", StringComparison.OrdinalIgnoreCase);
        if (mode == "uia" && isUnreal)
            throw new InvalidOperationException("Unreal Slate widgets do not expose UI Automation invokable children. Use messages, physical or touch.");
        if (mode == "physical")
        {
            EnsureExposedOnCurrentDesktop(target, bound);
            ct.ThrowIfCancellationRequested();
            InjectPhysicalMouseClick(_position, button, count, ct);
            overlay.Pulse();
            var physicallyClicked = DescribeTarget(target);
            return new AgentCursorClickResult("injected", "sendinput-mouse", button, count, physicallyClicked,
                before, Snapshot(), State(overlay),
                "Explicit physical mouse alternative used SendInput, briefly moved the OS cursor, then restored its position unless another input moved it. May activate the window.");
        }
        bool touchRequested = mode == "touch";
        if (touchRequested)
        {
            using var desktops = new VirtualDesktopService();
            bool sameDesktop = !bound || (desktops.Available && _boundDesktop == desktops.Current);
            IntPtr underPoint = Native.WindowFromPoint(_position);
            bool targetExposed = underPoint != IntPtr.Zero &&
                                 Native.GetAncestor(underPoint, 2) == Native.GetAncestor(target, 2);
            if (sameDesktop && targetExposed)
            {
                ct.ThrowIfCancellationRequested();
                InjectTouchTap(_position, count, ct);
                overlay.Pulse();
                var touched = DescribeTarget(target);
                _monitor.AddLogMessage($"[AgentCursor] Synthetic touch x{count} at {_position.X},{_position.Y} to {touched.ClassName} ({touched.WindowHandle}).");
                return new AgentCursorClickResult("injected", "windows-touch", button, count, touched,
                    before, Snapshot(), State(overlay),
                    "Synthetic Windows touch (WM_POINTER) was injected at the AI cursor. Physical mouse position is unchanged; the target may activate or interpret touch differently from a mouse. Confirm the app reacted.");
            }
            if (mode == "touch")
                throw new InvalidOperationException("Synthetic touch requires the target to be exposed under the AI cursor on the active Windows virtual desktop. It cannot tap covered or background-desktop windows.");
            // Only the explicit touch mode reaches here on an ineligible target.
        }
        string? method = null;
        if (!bound && !isUnreal && mode != "messages" && button == "left" && count == 1)
        {
            // UIA calls run on an MTA thread owning no windows, per Microsoft's guidance.
            // Do not leave our overlay available to FromPoint's accessibility hit testing.
            Native.ShowWindow(overlay.Handle, 0);
            try { method = TryAutomation(_position, target, ct); }
            finally { Native.ShowWindow(overlay.Handle, 4); }
            if (method == null && mode == "uia")
                throw new InvalidOperationException("The target exposes no supported UI Automation action (Invoke, Toggle or SelectionItem).");
        }
        if (method != null)
        {
            overlay.Pulse();
            var automationTarget = DescribeTarget(target);
            _monitor.AddLogMessage($"[AgentCursor] {method} at {_position.X},{_position.Y} ({automationTarget.WindowHandle}).");
            return new AgentCursorClickResult("invoked", method, button, count, automationTarget,
                before, Snapshot(), State(overlay), "Accessibility action returned. The target app may still be processing it or may change focus itself.");
        }
        ct.ThrowIfCancellationRequested();
        var coordinates = new IntPtr((client.X & 0xffff) | ((client.Y & 0xffff) << 16));
        var down = button == "left" ? 0x0201u : 0x0204u;
        var up = button == "left" ? 0x0202u : 0x0205u;
        var held = new IntPtr(button == "left" ? 1 : 2);
        Post(target, 0x0200, IntPtr.Zero, coordinates); // WM_MOUSEMOVE at agent position
        Post(target, down, held, coordinates);
        Post(target, up, IntPtr.Zero, coordinates);
        if (count == 2)
        {
            // Windows only generates DBLCLK for classes opting into CS_DBLCLKS.
            var doubleClick = (Native.GetClassLong(target, -26) & 0x0008) != 0;
            Post(target, doubleClick ? down + 2 : down, held, coordinates);
            Post(target, up, IntPtr.Zero, coordinates);
        }
        overlay.Pulse();
        var info = DescribeTarget(target);
        _monitor.AddLogMessage($"[AgentCursor] Posted {button} click x{count} at {_position.X},{_position.Y} to {info.ClassName} ({info.WindowHandle}).");
        return new AgentCursorClickResult("posted", "window-messages", button, count, info,
            before, Snapshot(), State(overlay),
            "Messages were queued, not an acknowledgement of an app action. Some apps ignore background clicks or may change focus themselves.");
    }, ct);

    /// <summary>
    /// Native WM_MOUSEWHEEL at the agent's screen position. The OS cursor is untouched.
    /// Positive detents scroll up, negative detents scroll down. The screen-coordinate
    /// LPARAM is important: Slate reads the wheel event position directly from it.
    /// </summary>
    public Task<AgentCursorScrollResult> ScrollAsync(int detents, string mode = "messages", CancellationToken ct = default) =>
        OnThread(overlay =>
        {
            if (detents is < -20 or > 20 or 0)
                throw new ArgumentException("detents must be between -20 and 20 and not zero.");
            mode = mode?.ToLowerInvariant() ?? "";
            if (mode is not ("messages" or "physical"))
                throw new ArgumentException("scroll mode must be messages or physical.");
            if (!_hasPosition)
                throw new InvalidOperationException("Move the agent cursor before scrolling.");
            bool bound = _boundWindow != IntPtr.Zero;
            if (bound)
            {
                if (!Native.IsWindow(_boundWindow))
                    throw new InvalidOperationException("The bound window has closed.");
                using var desktops = new VirtualDesktopService();
                if (!desktops.Available || desktops.DesktopOf(Native.GetAncestor(_boundWindow, 2)) != _boundDesktop)
                    throw new InvalidOperationException("The bound HWND's desktop changed. Move again.");
                if (_boundDesktop != desktops.Current) overlay.Hide();
                else overlay.MoveTo(_position);
            }
            else overlay.MoveTo(_position);

            var target = FindTarget(overlay);
            if (target == IntPtr.Zero) throw new InvalidOperationException("No enabled target under AI cursor.");
            var client = _position;
            if (!Native.ScreenToClient(target, ref client) || !Native.GetClientRect(target, out var rect)
                || client.X < rect.Left || client.X >= rect.Right || client.Y < rect.Top || client.Y >= rect.Bottom)
                throw new InvalidOperationException("Wheel target is outside the window client area.");
            // This LPARAM is a signed 16-bit pair of SCREEN, not client, coordinates.
            if (_position.X < short.MinValue || _position.X > short.MaxValue
                || _position.Y < short.MinValue || _position.Y > short.MaxValue)
                throw new InvalidOperationException("Physical pointer exceeds Win32 WM_MOUSEWHEEL coordinates.");
            var before = Snapshot();
            ct.ThrowIfCancellationRequested();
            if (mode == "physical")
            {
                EnsureExposedOnCurrentDesktop(target, bound);
                InjectPhysicalMouseWheel(_position, detents, ct);
                overlay.Pulse();
                var wheelTarget = DescribeTarget(target);
                return new AgentCursorScrollResult("injected", "sendinput-mouse", detents, wheelTarget,
                    before, Snapshot(), State(overlay),
                    "Explicit physical mouse wheel; the OS cursor was briefly moved then conditionally restored. May activate the window.");
            }
            var screen = new IntPtr((_position.X & 0xFFFF) | ((_position.Y & 0xFFFF) << 16));
            int delta = checked(detents * 120); // WHEEL_DELTA
            Post(target, 0x020Au, new IntPtr((delta & 0xFFFF) << 16), screen);
            overlay.Pulse();
            var info = DescribeTarget(target);
            _monitor.AddLogMessage($"[AgentCursor] Posted wheel {detents} detent(s) at {_position.X},{_position.Y} to {info.ClassName} ({info.WindowHandle}).");
            return new AgentCursorScrollResult("posted", "window-messages", detents, info,
                before, Snapshot(), State(overlay),
                "WM_MOUSEWHEEL posted at screen coordinates; target may ignore it. Physical mouse was not moved.");
        }, ct);

    /// <summary>Move the explicitly bound top-level window by the AI cursor delta, without activation.</summary>
    public Task<AgentCursorWindowDragResult> DragWindowAsync(int toX, int toY, CancellationToken ct = default) =>
        OnThread(overlay =>
        {
            if (!_hasPosition || _boundWindow == IntPtr.Zero || !Native.IsWindow(_boundWindow))
                throw new InvalidOperationException("Bind the cursor to a valid target_hwnd before dragging.");
            var destination = new Point(toX, toY);
            if (!Screen.AllScreens.Any(screen => screen.Bounds.Contains(destination)))
                throw new ArgumentException("Drag destination must lie on a connected monitor.");
            using var desktops = new VirtualDesktopService();
            var root = Native.GetAncestor(_boundWindow, 2);
            if (!desktops.Available || desktops.DesktopOf(root) != _boundDesktop)
                throw new InvalidOperationException("Bound window changed desktops. Bind again before dragging.");
            if (!Native.GetWindowRect(root, out var oldRect) || !ContainsWindowPoint(root, _position))
                throw new InvalidOperationException("Drag must start inside the bound window.");
            var before = Snapshot();
            int dx = checked(toX - _position.X), dy = checked(toY - _position.Y);
            if (!Native.SetWindowPos(root, IntPtr.Zero, checked(oldRect.Left + dx), checked(oldRect.Top + dy),
                    0, 0, 0x0001 | 0x0004 | 0x0010 | 0x0200))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Could not move bound window.");
            if (!Native.GetWindowRect(root, out var newRect))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            _position = destination;
            if (_boundDesktop != desktops.Current) overlay.Hide();
            else overlay.MoveTo(destination);
            var after = Snapshot();
            _monitor.AddLogMessage($"[AgentCursor] Dragged window {root} on desktop {_boundDesktop} by {dx},{dy}.");
            return new AgentCursorWindowDragResult(root.ToInt64(), _boundDesktop!.Value,
                new AgentCursorBounds(oldRect.Left, oldRect.Top, oldRect.Right - oldRect.Left, oldRect.Bottom - oldRect.Top),
                new AgentCursorBounds(newRect.Left, newRect.Top, newRect.Right - newRect.Left, newRect.Bottom - newRect.Top),
                before, after, State(overlay), before.ForegroundWindowHandle == after.ForegroundWindowHandle);
        }, ct);

    private string? TryAutomation(Point point, IntPtr target, CancellationToken ct)
    {
        var attempt = new AutomationAttempt();
        Native.GetWindowThreadProcessId(target, out var pid);
        _automationTask = Task.Run(() =>
        {
            // FromPoint and provider calls use physical screen coordinates on this thread too.
            var previousDpi = Native.SetThreadDpiAwarenessContext(new IntPtr(-4));
            try
            {
                var screenPoint = new System.Windows.Point(point.X, point.Y);
                var element = AutomationElement.FromPoint(screenPoint);
                for (var depth = 0; element != null && depth < 12; depth++)
                {
                    ct.ThrowIfCancellationRequested();
                    var current = element.Current;
                    if (current.ProcessId != pid || current.ControlType == ControlType.Window || current.ControlType == ControlType.Pane)
                        return null;
                    if (!current.IsEnabled || current.IsOffscreen || !current.BoundingRectangle.Contains(screenPoint)) return null;
                    Action? action = null;
                    string? method = null;
                    if (element.TryGetCurrentPattern(InvokePattern.Pattern, out var invoke))
                    { action = ((InvokePattern)invoke).Invoke; method = "uia-invoke"; }
                    else if (element.TryGetCurrentPattern(TogglePattern.Pattern, out var toggle))
                    { action = ((TogglePattern)toggle).Toggle; method = "uia-toggle"; }
                    else if (element.TryGetCurrentPattern(SelectionItemPattern.Pattern, out var selection))
                    { action = ((SelectionItemPattern)selection).Select; method = "uia-select"; }
                    if (action != null)
                    {
                        lock (attempt)
                        {
                            // A slow provider must not start an action after the caller timed out.
                            if (attempt.Abandoned || ct.IsCancellationRequested) return null;
                            attempt.Started = true;
                        }
                        action();
                        return method;
                    }
                    element = TreeWalker.ControlViewWalker.GetParent(element);
                }
                return null;
            }
            finally { if (previousDpi != IntPtr.Zero) Native.SetThreadDpiAwarenessContext(previousDpi); }
        }, ct);
        // Observe late provider failures even if the request has already timed out.
        _ = _automationTask.ContinueWith(task => { _ = task.Exception; },
            CancellationToken.None, TaskContinuationOptions.OnlyOnFaulted, TaskScheduler.Default);
        try { return _automationTask.WaitAsync(TimeSpan.FromSeconds(3), ct).GetAwaiter().GetResult(); }
        catch (Exception ex) when (ex is TimeoutException or OperationCanceledException)
        {
            lock (attempt)
            {
                attempt.Abandoned = true;
                if (attempt.Started)
                    throw new InvalidOperationException("UI Automation did not return in time; an action may have started. No fallback click was sent. Check the target before retrying.", ex);
            }
            if (ex is OperationCanceledException) throw;
            throw new InvalidOperationException("UI Automation target lookup timed out. No action or fallback click was sent.", ex);
        }
    }

    private sealed class AutomationAttempt { public bool Abandoned, Started; }

    private void EnsureExposedOnCurrentDesktop(IntPtr target, bool bound)
    {
        using var desktops = new VirtualDesktopService();
        if (bound && (!desktops.Available || _boundDesktop != desktops.Current))
            throw new InvalidOperationException("Physical SendInput cannot target an inactive virtual desktop.");
        var top = Native.GetAncestor(target, 2);
        var atPoint = Native.WindowFromPoint(_position);
        if (atPoint == IntPtr.Zero || Native.GetAncestor(atPoint, 2) != top)
            throw new InvalidOperationException("Physical SendInput refused: a different window covers the requested point.");
    }

    // This is explicitly opt-in, not a fallback: unlike targeted messages and
    // synthetic touch, physical SendInput changes the shared OS mouse position.
    // Restore only when it is still at our injection point, so a user's concurrent
    // mouse movement isn't undone. Up is always sent even if cancellation arrives.
    private static void WithPhysicalCursor(Point at, CancellationToken ct, Action inject)
    {
        if (!Native.GetCursorPos(out var original))
            throw new Win32Exception(Marshal.GetLastWin32Error());
        ct.ThrowIfCancellationRequested();
        if (!Native.SetCursorPos(at.X, at.Y))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "SetCursorPos failed.");
        // Some applications clamp/warp the cursor immediately. Restore based on
        // its effective injection position, not necessarily the requested point.
        Native.GetCursorPos(out var injectedAt);
        try { inject(); }
        finally
        {
            if (Native.GetCursorPos(out var current) &&
                Math.Abs(current.X - injectedAt.X) <= 3 && Math.Abs(current.Y - injectedAt.Y) <= 3)
                Native.SetCursorPos(original.X, original.Y);
        }
    }

    private static void SendMouse(uint flags, uint data = 0)
    {
        var events = new[] { new Native.Input { Type = 0, Mouse = new Native.MouseInput { Flags = flags, Data = data } } };
        if (Native.SendInput(1, events, Marshal.SizeOf<Native.Input>()) != 1)
            throw new Win32Exception(Marshal.GetLastWin32Error(), "SendInput failed.");
    }

    private static void InjectPhysicalMouseClick(Point at, string button, int count, CancellationToken ct) =>
        WithPhysicalCursor(at, ct, () =>
        {
            uint down = button == "right" ? 0x0008u : 0x0002u;
            uint up = button == "right" ? 0x0010u : 0x0004u;
            for (int n = 0; n < count; ++n)
            {
                ct.ThrowIfCancellationRequested();
                SendMouse(down);
                try { Thread.Sleep(75); }
                finally { SendMouse(up); }
                if (n + 1 < count) Thread.Sleep(85);
            }
        });

    private static void InjectPhysicalMouseWheel(Point at, int detents, CancellationToken ct) =>
        WithPhysicalCursor(at, ct, () =>
        {
            SendMouse(0x0800u, unchecked((uint)(detents * 120)));
        });

    private static void InjectTouchTap(Point screenPosition, int count, CancellationToken ct)
    {
        // Touch injection routes via Windows hit testing, unlike PostMessage. Never move
        // or restore the system mouse here: a person may be using it simultaneously.
        if (!Native.InitializeTouchInjection(1, 3)) // TOUCH_FEEDBACK_NONE
            throw new Win32Exception(Marshal.GetLastWin32Error(), "InitializeTouchInjection failed.");
        for (int tap = 0; tap < count; tap++)
        {
            ct.ThrowIfCancellationRequested();
            var touch = new Native.PointerTouchInfo
            {
                PointerInfo = new Native.PointerInfo
                {
                    PointerType = 2, // PT_TOUCH
                    PointerId = 0,
                    PointerFlags = 0x00010006, // DOWN | INRANGE | INCONTACT
                    PtPixelLocation = screenPosition
                },
                TouchMask = 0x1, // CONTACTAREA; omitting unused orientation/pressure fields matters.
                RcContact = new Native.Rect
                {
                    Left = screenPosition.X - 4, Top = screenPosition.Y - 4,
                    Right = screenPosition.X + 4, Bottom = screenPosition.Y + 4
                }
            };
            if (!Native.InjectTouchInput(1, ref touch))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "Synthetic touch down failed.");
            try { Thread.Sleep(120); } // Slate must see a pressed frame before release.
            finally
            {
                touch.PointerInfo.PointerFlags = 0x00040000; // UP
                if (!Native.InjectTouchInput(1, ref touch))
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "Synthetic touch release failed.");
            }
            if (tap + 1 < count) Thread.Sleep(100);
        }
    }

    private static void Post(IntPtr target, uint message, IntPtr wParam, IntPtr lParam)
    {
        if (!Native.PostMessage(target, message, wParam, lParam))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Mouse-message delivery failed (the target may be elevated or closed).");
    }

    private AgentCursorState State(CursorOverlay overlay)
    {
        var target = _hasPosition ? FindTarget(overlay) : IntPtr.Zero;
        var desktop = SystemInformation.VirtualScreen;
        using var desktops = new VirtualDesktopService();
        int? current = desktops.Available ? desktops.Current : null;
        return new AgentCursorState(overlay.Visible, _position.X, _position.Y, Snapshot(),
            new AgentCursorBounds(desktop.Left, desktop.Top, desktop.Width, desktop.Height),
            Screen.AllScreens.Select(s => new AgentCursorBounds(s.Bounds.Left, s.Bounds.Top, s.Bounds.Width, s.Bounds.Height)).ToArray(),
            target == IntPtr.Zero ? null : DescribeTarget(target), _hasPosition,
            _boundWindow == IntPtr.Zero ? null : _boundWindow.ToInt64(), _boundDesktop, current,
            _boundDesktop.HasValue && _boundDesktop != current);
    }

    private static bool ContainsWindowPoint(IntPtr hwnd, Point point) =>
        Native.GetWindowRect(hwnd, out var r) && point.X >= r.Left && point.X < r.Right
            && point.Y >= r.Top && point.Y < r.Bottom;

    private IntPtr FindTarget(CursorOverlay overlay)
    {
        if (_boundWindow != IntPtr.Zero)
        {
            if (!Native.IsWindow(_boundWindow) || !ContainsWindowPoint(_boundWindow, _position)) return IntPtr.Zero;
            return DescendToNativeChild(_boundWindow);
        }
        // HTTRANSPARENT alone only forwards hit testing to windows in the same thread.
        // Briefly hide our HWND so WindowFromPoint sees another process reliably.
        var visible = overlay.Visible;
        if (visible) Native.ShowWindow(overlay.Handle, 0);
        try
        {
            var target = Native.WindowFromPoint(_position);
            if (target == overlay.Handle || target == IntPtr.Zero) return IntPtr.Zero;
            return DescendToNativeChild(target);
        }
        finally
        {
            if (visible) Native.ShowWindow(overlay.Handle, 4); // SW_SHOWNOACTIVATE
        }
    }

    private IntPtr DescendToNativeChild(IntPtr root)
    {
        var target = root;
        for (var depth = 0; depth < 32; depth++)
        {
            var local = _position;
            if (!Native.ScreenToClient(target, ref local)) break;
            var child = Native.ChildWindowFromPointEx(target, local, 0x0001 | 0x0002 | 0x0004);
            if (child == IntPtr.Zero || child == target) break;
            target = child;
        }
        return Native.IsWindowEnabled(target) ? target : IntPtr.Zero;
    }

    private static AgentCursorTarget DescribeTarget(IntPtr hwnd)
    {
        var className = new StringBuilder(256);
        Native.GetClassName(hwnd, className, className.Capacity);
        Native.GetWindowThreadProcessId(hwnd, out var pid);
        return new AgentCursorTarget(hwnd.ToInt64().ToString(), pid, className.ToString());
    }

    private static AgentCursorDesktopState Snapshot()
    {
        if (!Native.GetCursorPos(out var point)) throw new Win32Exception(Marshal.GetLastWin32Error());
        return new AgentCursorDesktopState(point.X, point.Y, Native.GetForegroundWindow().ToInt64().ToString());
    }

    private async Task<T> OnThread<T>(Func<CursorOverlay, T> operation, CancellationToken ct)
    {
        ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
        var overlay = await _ready.Task.WaitAsync(ct);
        var completion = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        overlay.BeginInvoke((Action)(() =>
        {
            // A cancelled queued click must never execute later.
            if (ct.IsCancellationRequested) { completion.TrySetCanceled(ct); return; }
            try
            {
                ObjectDisposedException.ThrowIf(Volatile.Read(ref _disposed) != 0, this);
                completion.TrySetResult(operation(overlay));
            }
            catch (Exception ex) { completion.TrySetException(ex); }
        }));
        return await completion.Task;
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
        if (_ready.Task.IsCompletedSuccessfully)
        {
            var overlay = _ready.Task.Result;
            overlay.BeginInvoke((Action)(() => { overlay.Close(); System.Windows.Forms.Application.ExitThread(); }));
            _thread.Join(TimeSpan.FromSeconds(2));
        }
    }

    private sealed class CursorOverlay : Form
    {
        private readonly System.Windows.Forms.Timer _pulse = new() { Interval = 200 };
        private readonly System.Windows.Forms.Timer _idle = new() { Interval = 5_000 };
        private bool _clicking;

        public CursorOverlay()
        {
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            AutoScaleMode = AutoScaleMode.None;
            StartPosition = FormStartPosition.Manual;
            Size = new Size(122, 64);
            BackColor = Color.Magenta;
            TransparencyKey = Color.Magenta;
            DoubleBuffered = true;
            Text = "Omni agent cursor";
            _pulse.Tick += (_, _) => { _pulse.Stop(); _clicking = false; Invalidate(); };
            _idle.Tick += (_, _) => { _idle.Stop(); Hide(); };
        }

        protected override bool ShowWithoutActivation => true;
        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= 0x08000000 | 0x00000080 | 0x00000020; // NOACTIVATE | TOOLWINDOW | TRANSPARENT
                return cp;
            }
        }

        public void MoveTo(Point point)
        {
            if (!Visible) Show();
            // Use physical coordinates even when the Hub's other UI is DPI virtualised.
            Native.SetWindowPos(Handle, new IntPtr(-1), point.X, point.Y, Width, Height, 0x0010); // NOACTIVATE
            _idle.Stop();
            _idle.Start();
        }

        public void Pulse()
        {
            _clicking = true;
            _pulse.Stop();
            _pulse.Start();
            _idle.Stop();
            _idle.Start();
            Invalidate();
        }

        protected override void WndProc(ref Message message)
        {
            if (message.Msg == 0x0021) { message.Result = new IntPtr(3); return; } // MA_NOACTIVATE
            if (message.Msg == 0x0084) { message.Result = new IntPtr(-1); return; } // HTTRANSPARENT
            base.WndProc(ref message);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            // Colour-key transparency needs hard edges to avoid magenta fringes.
            e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.None;
            Point[] arrow = [new(1, 1), new(1, 29), new(8, 22), new(14, 35), new(20, 32), new(14, 19), new(25, 19)];
            using var fill = new SolidBrush(_clicking ? Color.Gold : Color.DeepSkyBlue);
            using var outline = new Pen(Color.White, 2);
            e.Graphics.FillPolygon(fill, arrow);
            e.Graphics.DrawPolygon(outline, arrow);
            using var background = new SolidBrush(Color.FromArgb(25, 38, 55));
            e.Graphics.FillRectangle(background, 25, 30, 88, 22);
            TextRenderer.DrawText(e.Graphics, "OMNI AI", Font, new Point(31, 33), Color.White);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) { _pulse.Dispose(); _idle.Dispose(); }
            base.Dispose(disposing);
        }
    }

    private static class Native
    {
        [StructLayout(LayoutKind.Sequential)] internal struct Rect { public int Left, Top, Right, Bottom; }
        [StructLayout(LayoutKind.Sequential)]
        internal struct PointerInfo
        {
            public uint PointerType, PointerId, FrameId, PointerFlags;
            public IntPtr SourceDevice, HwndTarget;
            public Point PtPixelLocation, PtHimetricLocation, PtPixelLocationRaw, PtHimetricLocationRaw;
            public uint DwTime, HistoryCount;
            public int InputData;
            public uint DwKeyStates;
            public ulong PerformanceCount;
            public uint ButtonChangeType;
        }
        [StructLayout(LayoutKind.Sequential)]
        internal struct PointerTouchInfo
        {
            public PointerInfo PointerInfo;
            public uint TouchFlags, TouchMask;
            public Rect RcContact, RcContactRaw;
            public uint Orientation, Pressure;
        }
        [DllImport("user32.dll", SetLastError = true)] internal static extern bool InitializeTouchInjection(uint maxCount, uint mode);
        [DllImport("user32.dll", SetLastError = true)] internal static extern bool InjectTouchInput(uint count, ref PointerTouchInfo contacts);
        [DllImport("user32.dll")] internal static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
        [StructLayout(LayoutKind.Sequential)]
        internal struct MouseInput
        {
            public uint Dx, Dy, Data, Flags, Time;
            public UIntPtr Extra;
        }
        [StructLayout(LayoutKind.Sequential)]
        internal struct Input
        {
            public uint Type;
            public MouseInput Mouse;
        }
        [DllImport("user32.dll", SetLastError = true)] internal static extern uint SendInput(uint count, [In] Input[] inputs, int cbSize);
        [DllImport("user32.dll", SetLastError = true)] internal static extern bool SetCursorPos(int x, int y);
        [DllImport("user32.dll", SetLastError = true)] internal static extern bool GetCursorPos(out Point point);
        [DllImport("user32.dll")] internal static extern IntPtr GetForegroundWindow();
        [DllImport("user32.dll")] internal static extern IntPtr WindowFromPoint(Point point);
        [DllImport("user32.dll")] internal static extern bool IsWindow(IntPtr hwnd);
        [DllImport("user32.dll")] internal static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);
        [DllImport("user32.dll", SetLastError = true)] internal static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
        [DllImport("user32.dll")] internal static extern IntPtr ChildWindowFromPointEx(IntPtr parent, Point point, uint flags);
        [DllImport("user32.dll", SetLastError = true)] internal static extern bool ScreenToClient(IntPtr hwnd, ref Point point);
        [DllImport("user32.dll", SetLastError = true)] internal static extern bool GetClientRect(IntPtr hwnd, out Rect rect);
        [DllImport("user32.dll")] internal static extern bool IsWindowEnabled(IntPtr hwnd);
        [DllImport("user32.dll", SetLastError = true)] internal static extern bool PostMessage(IntPtr hwnd, uint message, IntPtr wParam, IntPtr lParam);
        [DllImport("user32.dll", CharSet = CharSet.Unicode)] internal static extern int GetClassName(IntPtr hwnd, StringBuilder text, int count);
        [DllImport("user32.dll")] internal static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
        [DllImport("user32.dll", EntryPoint = "GetClassLongW")] internal static extern uint GetClassLong(IntPtr hwnd, int index);
        [DllImport("user32.dll")] internal static extern bool ShowWindow(IntPtr hwnd, int command);
        [DllImport("user32.dll")] internal static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int width, int height, uint flags);
    }
}

public sealed record AgentCursorBounds(int X, int Y, int Width, int Height);
public sealed record AgentCursorDesktopState(int MouseX, int MouseY, string ForegroundWindowHandle);
public sealed record AgentCursorTarget(string WindowHandle, uint ProcessId, string ClassName);
public sealed record AgentCursorState(bool Visible, int X, int Y, AgentCursorDesktopState Desktop,
    AgentCursorBounds VirtualDesktop, AgentCursorBounds[] Monitors, AgentCursorTarget? Target, bool Ready,
    long? BoundWindowHwnd = null, int? TargetDesktopNumber = null, int? CurrentDesktopNumber = null,
    bool BackgroundTarget = false);
public sealed record AgentCursorClickResult(string Delivery, string Method, string Button, int Count, AgentCursorTarget Target,
    AgentCursorDesktopState Before, AgentCursorDesktopState After, AgentCursorState Cursor, string Note);
public sealed record AgentCursorScrollResult(string Delivery, string Method, int Detents, AgentCursorTarget Target,
    AgentCursorDesktopState Before, AgentCursorDesktopState After, AgentCursorState Cursor, string Note);
public sealed record AgentCursorWindowDragResult(long WindowHwnd, int DesktopNumber,
    AgentCursorBounds BeforeBounds, AgentCursorBounds AfterBounds,
    AgentCursorDesktopState Before, AgentCursorDesktopState After, AgentCursorState Cursor,
    bool FocusPreservedAtReturn);
