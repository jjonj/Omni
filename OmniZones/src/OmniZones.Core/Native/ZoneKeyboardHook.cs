using System.Runtime.InteropServices;

namespace OmniZones.Native;

public readonly record struct KeyPress(int Vk, bool Win, bool Ctrl, bool Alt, bool Shift);

/// <summary>
/// Minimal low-level keyboard hook for Win-key combos. The handler returns true to swallow the combo.
/// A swallowed combo also swallows the Start menu that would otherwise open when Win is released.
/// Must be created on a thread that pumps messages.
/// </summary>
public sealed class ZoneKeyboardHook : IDisposable
{
    private const int WH_KEYBOARD_LL = 13, WM_KEYDOWN = 0x100, WM_KEYUP = 0x101, WM_SYSKEYDOWN = 0x104, WM_SYSKEYUP = 0x105;
    private const uint LLKHF_INJECTED = 0x10;
    private const byte VK_UNASSIGNED = 0xE8;

    private readonly Func<KeyPress, bool> _handler;
    private readonly Win32.LowLevelKeyboardProc _proc;
    private readonly HashSet<int> _swallowed = new();
    private IntPtr _hook;
    private bool _winUsed;

    public ZoneKeyboardHook(Func<KeyPress, bool> handler)
    {
        _handler = handler;
        _proc = Callback;
        _hook = Win32.SetWindowsHookEx(WH_KEYBOARD_LL, _proc, Win32.GetModuleHandle(null), 0);
    }

    public bool Installed => _hook != IntPtr.Zero;

    private static bool IsWin(int vk) => vk == Win32.VK_LWIN || vk == Win32.VK_RWIN;

    private IntPtr Callback(int code, IntPtr wParam, IntPtr lParam)
    {
        if (code >= 0)
        {
            var info = Marshal.PtrToStructure<Win32.KBDLLHOOKSTRUCT>(lParam);
            if ((info.flags & LLKHF_INJECTED) == 0)
            {
                var vk = (int)info.vkCode;
                var msg = wParam.ToInt32();
                var down = msg is WM_KEYDOWN or WM_SYSKEYDOWN;
                try
                {
                    if (down)
                    {
                        var win = WindowTools.IsDown(Win32.VK_LWIN) || WindowTools.IsDown(Win32.VK_RWIN);
                        if (win && !IsWin(vk) && _handler(new KeyPress(vk, true, WindowTools.IsDown(Win32.VK_CONTROL),
                                WindowTools.IsDown(Win32.VK_MENU), WindowTools.IsDown(Win32.VK_SHIFT))))
                        {
                            _winUsed = true;
                            _swallowed.Add(vk);
                            return (IntPtr)1;
                        }
                    }
                    else
                    {
                        if (_swallowed.Remove(vk)) return (IntPtr)1;
                        if (IsWin(vk) && _winUsed)
                        {
                            _winUsed = false;
                            // An unassigned key tap while Win is down stops Windows from opening Start on release.
                            Win32.keybd_event(VK_UNASSIGNED, 0, 0, UIntPtr.Zero);
                            Win32.keybd_event(VK_UNASSIGNED, 0, 2, UIntPtr.Zero);
                        }
                    }
                }
                catch { /* never let a handler bug break the user's keyboard */ }
            }
        }
        return Win32.CallNextHookEx(_hook, code, wParam, lParam);
    }

    public void Dispose()
    {
        if (_hook != IntPtr.Zero) { Win32.UnhookWindowsHookEx(_hook); _hook = IntPtr.Zero; }
    }
}
