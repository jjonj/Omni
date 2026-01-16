using System;
using System.Runtime.InteropServices;
using System.Windows.Forms; // For Keys enum
using System.Diagnostics;
using Microsoft.Extensions.Logging; // Added for logging

namespace OmniSync.Hub.Infrastructure.Services
{
    public class KeyboardHook : IDisposable
    {
        private const int WH_KEYBOARD_LL = 13;
        private const int WM_KEYDOWN = 0x0100;
        private const int WM_KEYUP = 0x0101;
        private const int WM_SYSKEYDOWN = 0x0104;
        private const int WM_SYSKEYUP = 0x0105;

        private delegate int LowLevelKeyboardProc(int nCode, IntPtr wParam, IntPtr lParam);

        [StructLayout(LayoutKind.Sequential)]
        private struct KBDLLHOOKSTRUCT
        {
            public uint vkCode;
            public uint scanCode;
            public uint flags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [DllImport("user32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        private static extern IntPtr SetWindowsHookEx(int idHook, LowLevelKeyboardProc lpfn, IntPtr hMod, uint dwThreadId);

        [DllImport("user32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool UnhookWindowsHookEx(IntPtr hhk);

        [DllImport("user32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        private static extern int CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

        [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        private static extern IntPtr GetModuleHandle(string lpModuleName);

        [DllImport("user32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        private static extern short GetKeyState(int nVirtKey);

        private IntPtr _hookID = IntPtr.Zero;
        private LowLevelKeyboardProc _proc;
        private readonly ILogger<KeyboardHook> _logger; // Added for logging

        public event EventHandler<KeyHookEventArgs>? KeyActionOccurred;

        public bool IsRecording { get; set; }

        public KeyboardHook(ILogger<KeyboardHook> logger)
        {
            _logger = logger;
            _proc = HookCallback; // Keep a reference to the delegate to prevent garbage collection
        }

        public void SetHook()
        {
            using (Process curProcess = Process.GetCurrentProcess())
            using (ProcessModule? curModule = curProcess.MainModule)
            {
                if (curModule == null)
                {
                    _logger.LogError("Main module not found for current process. Cannot set keyboard hook.");
                    return;
                }
                
                uint threadId = 0; // 0 for global hook
                _hookID = SetWindowsHookEx(WH_KEYBOARD_LL, _proc, GetModuleHandle(curModule.ModuleName), threadId);
                
                if (_hookID == IntPtr.Zero)
                {
                    var lastError = Marshal.GetLastWin32Error();
                    _logger.LogError($"[KeyboardHook] FAILED to set hook. Error code: {lastError}. Thread ID: {Environment.CurrentManagedThreadId}");
                }
                else
                {
                    _logger.LogInformation($"[KeyboardHook] SUCCESS. Hook handle: {_hookID}. Thread ID: {Environment.CurrentManagedThreadId}");
                }
            }
        }

        public void Unhook()
        {
            if (_hookID != IntPtr.Zero)
            {
                bool result = UnhookWindowsHookEx(_hookID);
                if (result)
                {
                    _hookID = IntPtr.Zero;
                    _logger.LogInformation("Keyboard hook unhooked successfully.");
                }
                else
                {
                    var lastError = Marshal.GetLastWin32Error();
                    _logger.LogError($"Failed to unhook keyboard hook. Error: {lastError}");
                }
            }
        }

        private int HookCallback(int nCode, IntPtr wParam, IntPtr lParam)
        {
            if (nCode >= 0)
            {
                // Modifier keys are special; their state can change without a specific KEYDOWN/KEYUP for them
                // We always check the state of modifiers
                bool isShiftPressed = (GetKeyState((int)Keys.LShiftKey) & 0x8000) != 0 || (GetKeyState((int)Keys.RShiftKey) & 0x8000) != 0;
                bool isCtrlPressed = (GetKeyState((int)Keys.LControlKey) & 0x8000) != 0 || (GetKeyState((int)Keys.RControlKey) & 0x8000) != 0;
                bool isAltPressed = (GetKeyState((int)Keys.LMenu) & 0x8000) != 0 || (GetKeyState((int)Keys.RMenu) & 0x8000) != 0;
                bool isWinPressed = (GetKeyState((int)Keys.LWin) & 0x8000) != 0 || (GetKeyState((int)Keys.RWin) & 0x8000) != 0;

                bool isKeyDown = wParam == (IntPtr)WM_KEYDOWN || wParam == (IntPtr)WM_SYSKEYDOWN;
                bool isKeyUp = wParam == (IntPtr)WM_KEYUP || wParam == (IntPtr)WM_SYSKEYUP;

                if (isKeyDown || isKeyUp)
                {
                    KBDLLHOOKSTRUCT hookStruct = (KBDLLHOOKSTRUCT)Marshal.PtrToStructure(lParam, typeof(KBDLLHOOKSTRUCT));
                    Keys key = (Keys)hookStruct.vkCode;
                    
                    var state = isKeyDown ? KeyState.Down : KeyState.Up;
                    
                    _logger.LogDebug($"Key {state}: {key}, Shift: {isShiftPressed}, Ctrl: {isCtrlPressed}, Alt: {isAltPressed}, Win: {isWinPressed}");
                    var args = new KeyHookEventArgs(key, state, isShiftPressed, isCtrlPressed, isAltPressed, isWinPressed);
                    KeyActionOccurred?.Invoke(this, args);

                    if (args.Handled || IsRecording)
                    {
                        return 1; // Handled
                    }
                }
            }
            return CallNextHookEx(_hookID, nCode, wParam, lParam);
        }

        public void Dispose()
        {
            Unhook();
            GC.SuppressFinalize(this);
        }

        ~KeyboardHook()
        {
            Unhook();
        }
    }

    public enum KeyState { Down, Up }

    public class KeyHookEventArgs : EventArgs
    {
        public Keys Key { get; private set; }
        public KeyState State { get; private set; }
        public bool Shift { get; private set; }
        public bool Control { get; private set; }
        public bool Alt { get; private set; }
        public bool Win { get; private set; }
        public bool Handled { get; set; }

        public KeyHookEventArgs(Keys key, KeyState state, bool shift, bool control, bool alt, bool win = false)
        {
            Key = key;
            State = state;
            Shift = shift;
            Control = control;
            Alt = alt;
            Win = win;
        }
    }
}
