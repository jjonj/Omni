using System.Text.Json.Nodes;
using OmniSync.Hub.Logic.Services.Mcp;
using OmniSync.Hub.Infrastructure.Services;
using OmniSync.Hub.Logic.Monitoring;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging.Abstractions;
using System.Runtime.InteropServices;

if (args.Length == 0)
{
    Console.Error.WriteLine("Usage: McpDesktopProbe <tool> [json arguments]. Calls newly built tool implementation without restarting Hub.");
    return 2;
}

try
{
    var name = args[0];
    if (name == "replace_editor_text")
    {
        var editor = new IntPtr(long.Parse(args[1]));
        var before = ProbeWin32.GetForegroundWindow();
        if (ProbeWin32.SendMessageTimeout(editor, 0x00B1, IntPtr.Zero, new IntPtr(-1), 0x0002,
                3000, out _) == IntPtr.Zero)
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(), "Could not select all editor text.");
        var tool = new McpWindowTools();
        var input = new JsonObject { ["hwnd"] = editor.ToInt64(), ["kind"] = "text",
            ["target"] = "hwnd", ["text"] = string.Join(" ", args.Skip(2)) };
        Console.WriteLine((await tool.InvokeAsync("send_window_input", input, CancellationToken.None)).Text);
        var after = ProbeWin32.GetForegroundWindow();
        Console.WriteLine($"foreground preserved: {before == after}");
        return 0;
    }
    if (name == "restore_window")
    {
        var previous = ProbeWin32.SetThreadDpiAwarenessContext(new IntPtr(-4));
        try
        {
            if (!ProbeWin32.SetWindowPos(new IntPtr(long.Parse(args[1])), IntPtr.Zero,
                int.Parse(args[2]), int.Parse(args[3]), int.Parse(args[4]), int.Parse(args[5]), 0x0004 | 0x0010))
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            ProbeWin32.GetWindowRect(new IntPtr(long.Parse(args[1])), out var bounds);
            Console.WriteLine($"restored HWND {args[1]} to {bounds.Left},{bounds.Top} {bounds.Right - bounds.Left}x{bounds.Bottom - bounds.Top}");
        }
        finally { if (previous != IntPtr.Zero) ProbeWin32.SetThreadDpiAwarenessContext(previous); }
        return 0;
    }
    if (name is "cursor_move_test" or "cursor_click_test" or "cursor_drag_test")
    {
        var monitor = new HubMonitorService(new ProbeLifetime(), NullLogger<HubMonitorService>.Instance);
        using var cursor = new AgentCursorService(monitor);
        long hwnd = long.Parse(args[1]);
        int x = int.Parse(args[2]), y = int.Parse(args[3]);
        int desktop = int.Parse(args[4]);
        var before = await cursor.StatusAsync();
        Console.WriteLine($"BEFORE: {System.Text.Json.JsonSerializer.Serialize(before)}");
        var moved = await cursor.MoveAsync(x, y, hwnd, desktop);
        Console.WriteLine($"MOVED: {System.Text.Json.JsonSerializer.Serialize(moved)}");
        if (name == "cursor_drag_test")
        {
            if (!moved.BackgroundTarget || moved.BoundWindowHwnd != hwnd)
                throw new InvalidOperationException("Refusing drag: target desktop or HWND is wrong.");
            var dragged = await cursor.DragWindowAsync(int.Parse(args[5]), int.Parse(args[6]));
            Console.WriteLine($"DRAGGED: {System.Text.Json.JsonSerializer.Serialize(dragged)}");
        }
        if (name == "cursor_click_test")
        {
            if (!moved.BackgroundTarget || moved.Target?.ClassName != "RichEditD2DPT")
                throw new InvalidOperationException("Refusing test click: target was not the background Notepad RichEdit child.");
            var clicked = await cursor.ClickAsync("left", 1, "messages");
            Console.WriteLine($"CLICKED: {System.Text.Json.JsonSerializer.Serialize(clicked)}");
        }
        Console.WriteLine($"HIDDEN: {System.Text.Json.JsonSerializer.Serialize(await cursor.HideAsync())}");
        return 0;
    }
    if (name is "desktop_status" or "desktop_create" or "desktop_move" or "desktop_window")
    {
        using var desktop = new VirtualDesktopService();
        if (!desktop.Available) throw new InvalidOperationException("VirtualDesktopAccessor unavailable");
        if (name == "desktop_create") Console.WriteLine($"created desktop {desktop.Create()}");
        else if (name == "desktop_status") Console.WriteLine($"current {desktop.Current}, count {desktop.Count}");
        else if (name == "desktop_window") Console.WriteLine($"window desktop {desktop.DesktopOf(new IntPtr(long.Parse(args[1])))}");
        else { desktop.MoveWindow(new IntPtr(long.Parse(args[1])), int.Parse(args[2])); Console.WriteLine($"moved window to desktop {args[2]}"); }
        return 0;
    }
    if (name == "send")
    {
        var t = new McpWindowTools();
        var a = new JsonObject { ["hwnd"] = long.Parse(args[1]), ["kind"] = "text", ["text"] = string.Join(" ", args.Skip(2)) };
        Console.WriteLine((await t.InvokeAsync("send_window_input", a, CancellationToken.None)).Text);
        return 0;
    }
    if (name == "children")
    {
        var t = new McpWindowTools();
        var a = new JsonObject { ["hwnd"] = long.Parse(args[1]) };
        Console.WriteLine((await t.InvokeAsync("list_child_windows", a, CancellationToken.None)).Text);
        return 0;
    }
    if (name == "show")
    {
        var t = new McpWindowTools();
        var a = new JsonObject { ["hwnd"] = long.Parse(args[1]), ["action"] = "show_no_activate", ["focus_policy"] = "never" };
        Console.WriteLine((await t.InvokeAsync("manage_window", a, CancellationToken.None)).Text);
        return 0;
    }
    if (name == "inspect")
    {
        Console.WriteLine(new UiInspector().Inspect(new IntPtr(long.Parse(args[1])), 7, 400));
        return 0;
    }
    if (name == "set")
    {
        Console.WriteLine(new UiInspector().Act(new IntPtr(long.Parse(args[1])), args[2], "set_value", args[3], false));
        return 0;
    }
    var arguments = args.Length >= 2 && args[1].StartsWith("{", StringComparison.Ordinal)
        ? JsonNode.Parse(args[1]) as JsonObject ?? new JsonObject()
        : name == "list_windows" && args.Length >= 2
            ? new JsonObject { ["process_name"] = args[1], ["include_hidden"] = true }
            : new JsonObject();
    var tools = new McpWindowTools();
    if (!tools.Contains(name))
    {
        Console.Error.WriteLine($"Tool '{name}' unavailable.");
        return 2;
    }

    var result = await tools.InvokeAsync(name, arguments, CancellationToken.None);
    Console.WriteLine(result.Text);
    if (result.Image != null) Console.WriteLine($"image_bytes={result.Image.Length} mime={result.ImageMime}");
    return result.IsError ? 1 : 0;
}
catch (Exception e)
{
    Console.Error.WriteLine($"{e.GetType().Name}: {e.Message}");
    return 1;
}

static class ProbeWin32
{
    [StructLayout(LayoutKind.Sequential)] internal struct Rect { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] internal static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
    [DllImport("user32.dll")] internal static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", EntryPoint = "SendMessageTimeoutW", SetLastError = true)]
    internal static extern IntPtr SendMessageTimeout(IntPtr hwnd, uint msg, IntPtr wp, IntPtr lp,
        uint flags, uint timeout, out IntPtr result);
    [DllImport("user32.dll", SetLastError = true)] internal static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int width, int height, uint flags);
    [DllImport("user32.dll", SetLastError = true)] internal static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
}

sealed class ProbeLifetime : IHostApplicationLifetime
{
    public CancellationToken ApplicationStarted => CancellationToken.None;
    public CancellationToken ApplicationStopping => CancellationToken.None;
    public CancellationToken ApplicationStopped => CancellationToken.None;
    public void StopApplication() { }
}
