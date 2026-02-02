using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Drawing; // For Point struct
using System.Windows.Forms; // Required for Screen.PrimaryScreen.Bounds
using Microsoft.Extensions.Logging; // Add for ILogger
using System.Diagnostics; // For Process
using System.Text; // For StringBuilder

namespace OmniSync.Hub.Infrastructure.Services
{
    public class InputService : IDisposable
    {
        private readonly ILogger<InputService> _logger;
        private readonly KeyboardHook _keyboardHook;

        // P/Invoke Declarations
        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        private static extern uint MapVirtualKey(uint uCode, uint uMapType);

        [DllImport("user32.dll")]
        private static extern IntPtr GetMessageExtraInfo();

        [DllImport("user32.dll")]
        private static extern int GetSystemMetrics(int nIndex);

        private const int SM_CXSCREEN = 0;
        private const int SM_CYSCREEN = 1;

        // Struct Definitions
        [StructLayout(LayoutKind.Sequential)]
        public struct INPUT
        {
            public uint type;
            public InputUnion U;
            public static int Size => Marshal.SizeOf(typeof(INPUT));
        }

        [StructLayout(LayoutKind.Explicit)]
        public struct InputUnion
        {
            [FieldOffset(0)] public MOUSEINPUT mi;
            [FieldOffset(0)] public KEYBDINPUT ki;
            [FieldOffset(0)] public HARDWAREINPUT hi;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct MOUSEINPUT
        {
            public int dx;
            public int dy;
            public uint mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        public struct HARDWAREINPUT
        {
            public uint uMsg;
            public ushort wParamL;
            public ushort wParamH;
        }

        // Constants
        private const int INPUT_MOUSE = 0;
        private const int INPUT_KEYBOARD = 1;
        
        // Mouse Flags
        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        private const uint MOUSEEVENTF_WHEEL = 0x0800;
        private const uint MOUSEEVENTF_HWHEEL = 0x01000;

        // Keyboard Flags
        private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const uint KEYEVENTF_UNICODE = 0x0004;
        private const uint KEYEVENTF_SCANCODE = 0x0008;

        // Modifier Key States (Internal)
        private bool _localShift, _remoteShift;
        private bool _localCtrl, _remoteCtrl;
        private bool _localAlt, _remoteAlt;
        private bool _localWin, _remoteWin;

        private bool _effectiveShift;
        private bool _effectiveCtrl;
        private bool _effectiveAlt;
        private bool _effectiveWin;

        // Public Properties for Effective Modifier Key States (Combined Local & Remote)
        public bool IsShiftPressed
        {
            get => _effectiveShift;
            private set
            {
                if (_effectiveShift != value)
                {
                    _effectiveShift = value;
                    ModifierStateChanged?.Invoke(this, new ModifierStateEventArgs(ModifierKey.Shift, value));
                }
            }
        }
        public bool IsCtrlPressed
        {
            get => _effectiveCtrl;
            private set
            {
                if (_effectiveCtrl != value)
                {
                    _effectiveCtrl = value;
                    ModifierStateChanged?.Invoke(this, new ModifierStateEventArgs(ModifierKey.Ctrl, value));
                }
            }
        }
        public bool IsAltPressed
        {
            get => _effectiveAlt;
            private set
            {
                if (_effectiveAlt != value)
                {
                    _effectiveAlt = value;
                    ModifierStateChanged?.Invoke(this, new ModifierStateEventArgs(ModifierKey.Alt, value));
                }
            }
        }
        public bool IsWinPressed
        {
            get => _effectiveWin;
            private set
            {
                if (_effectiveWin != value)
                {
                    _effectiveWin = value;
                    ModifierStateChanged?.Invoke(this, new ModifierStateEventArgs(ModifierKey.Win, value));
                }
            }
        }

        private void UpdateEffectiveStates()
        {
            IsShiftPressed = _localShift || _remoteShift;
            IsCtrlPressed = _localCtrl || _remoteCtrl;
            IsAltPressed = _localAlt || _remoteAlt;
            IsWinPressed = _localWin || _remoteWin;
        }

        // Event for notifying about modifier state changes
        public event EventHandler<ModifierStateEventArgs>? ModifierStateChanged;

        // Mouse Interpolation Fields
        private Point _currentMouseDeltaTarget = new Point(0, 0); // Stores accumulated delta
        private readonly object _mouseTargetLock = new object();
        private System.Threading.Timer _mouseUpdateTimer;
        private const int _interpolationIntervalMs = 10; // How often the timer ticks (approx 60Hz)
        // private const int _maxMovementPerTick = 10; // Max pixels moved per tick - REMOVED

        public InputService(ILogger<InputService> logger, KeyboardHook keyboardHook)
        {
            _logger = logger;
            _keyboardHook = keyboardHook;
            _keyboardHook.KeyActionOccurred += OnKeyActionOccurred;

            // Start the timer to periodically update mouse position
            _mouseUpdateTimer = new System.Threading.Timer(MouseUpdateTimer_Tick, null, 0, _interpolationIntervalMs);
        }

        private void OnKeyActionOccurred(object? sender, KeyHookEventArgs e)
        {
            // Only update our internal LOCAL state from the hook if the key that changed IS a modifier key,
            // AND the event is NOT injected (simulated by software). 
            if (!e.IsInjected && IsModifierKey(e.Key))
            {
                _localShift = e.Shift;
                _localCtrl = e.Control;
                _localAlt = e.Alt;
                _localWin = e.Win;
                UpdateEffectiveStates();
            }
        }

        private bool IsModifierKey(System.Windows.Forms.Keys key)
        {
            return key == System.Windows.Forms.Keys.ShiftKey || key == System.Windows.Forms.Keys.LShiftKey || key == System.Windows.Forms.Keys.RShiftKey ||
                   key == System.Windows.Forms.Keys.ControlKey || key == System.Windows.Forms.Keys.LControlKey || key == System.Windows.Forms.Keys.RControlKey ||
                   key == System.Windows.Forms.Keys.Menu || key == System.Windows.Forms.Keys.LMenu || key == System.Windows.Forms.Keys.RMenu ||
                   key == System.Windows.Forms.Keys.LWin || key == System.Windows.Forms.Keys.RWin;
        }
        public void LeftClick()
        {
            INPUT[] inputs = new INPUT[2];
            inputs[0].type = INPUT_MOUSE;
            inputs[0].U.mi = new MOUSEINPUT { dx = 0, dy = 0, dwFlags = MOUSEEVENTF_LEFTDOWN };
            inputs[1].type = INPUT_MOUSE;
            inputs[1].U.mi = new MOUSEINPUT { dx = 0, dy = 0, dwFlags = MOUSEEVENTF_LEFTUP };
            SendInputWithLogging(inputs);
        }

        public void RightClick()
        {
            INPUT[] inputs = new INPUT[2];
            inputs[0].type = INPUT_MOUSE;
            inputs[0].U.mi = new MOUSEINPUT { dx = 0, dy = 0, dwFlags = MOUSEEVENTF_RIGHTDOWN };
            inputs[1].type = INPUT_MOUSE;
            inputs[1].U.mi = new MOUSEINPUT { dx = 0, dy = 0, dwFlags = MOUSEEVENTF_RIGHTUP };
            SendInputWithLogging(inputs);
        }

        public void MouseScroll(int delta)
        {
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_MOUSE;
            // delta should be multiplied by WHEEL_DELTA (120), but since we are getting small increments 
            // from the trackpad, we can use them directly or scale them.
            // Negative delta in Win32 means scroll down.
            inputs[0].U.mi = new MOUSEINPUT { mouseData = (uint)-delta, dwFlags = MOUSEEVENTF_WHEEL };
            SendInputWithLogging(inputs);
        }

        public void MouseDown(string button)
        {
            uint flag = button.ToLower() switch
            {
                "left" => MOUSEEVENTF_LEFTDOWN,
                "right" => MOUSEEVENTF_RIGHTDOWN,
                _ => 0
            };
            if (flag == 0) return;
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_MOUSE;
            inputs[0].U.mi = new MOUSEINPUT { dwFlags = flag };
            SendInputWithLogging(inputs);
        }

        public void MouseUp(string button)
        {
            uint flag = button.ToLower() switch
            {
                "left" => MOUSEEVENTF_LEFTUP,
                "right" => MOUSEEVENTF_RIGHTUP,
                _ => 0
            };
            if (flag == 0) return;
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_MOUSE;
            inputs[0].U.mi = new MOUSEINPUT { dwFlags = flag };
            SendInputWithLogging(inputs);
        }

        public void MoveMouse(int dx, int dy)
        {
            lock (_mouseTargetLock)
            {
                _currentMouseDeltaTarget = new Point(_currentMouseDeltaTarget.X + dx, _currentMouseDeltaTarget.Y + dy);
            }
        }

        private void MouseUpdateTimer_Tick(object? state)
        {
            int moveX = 0;
            int moveY = 0;

            const double interpolationFactor = 0.4; // Move 50% of the remaining distance per tick
            const int minPixelMove = 1; // Ensure at least 1 pixel is moved if target is not zero

            lock (_mouseTargetLock)
            {
                if (_currentMouseDeltaTarget.X != 0 || _currentMouseDeltaTarget.Y != 0)
                {
                    moveX = (int)(_currentMouseDeltaTarget.X * interpolationFactor);
                    moveY = (int)(_currentMouseDeltaTarget.Y * interpolationFactor);

                    // Ensure at least 1 pixel is moved if there's remaining target, unless target is tiny
                    if (_currentMouseDeltaTarget.X != 0 && moveX == 0) moveX = Math.Sign(_currentMouseDeltaTarget.X) * minPixelMove;
                    if (_currentMouseDeltaTarget.Y != 0 && moveY == 0) moveY = Math.Sign(_currentMouseDeltaTarget.Y) * minPixelMove;

                    // If after ensuring minPixelMove, the movement would overshoot the target, cap it.
                    if (Math.Abs(moveX) > Math.Abs(_currentMouseDeltaTarget.X)) moveX = _currentMouseDeltaTarget.X;
                    if (Math.Abs(moveY) > Math.Abs(_currentMouseDeltaTarget.Y)) moveY = _currentMouseDeltaTarget.Y;
                    
                    _currentMouseDeltaTarget = new Point(_currentMouseDeltaTarget.X - moveX, _currentMouseDeltaTarget.Y - moveY);
                }
            }

            if (moveX != 0 || moveY != 0)
            {
                INPUT[] inputs = new INPUT[1];
                inputs[0].type = INPUT_MOUSE;
                inputs[0].U.mi = new MOUSEINPUT
                {
                    dx = moveX,
                    dy = moveY,
                    dwFlags = MOUSEEVENTF_MOVE
                };
                SendInputWithLogging(inputs);
            }
        }

        // Keyboard Input Methods (These remain mostly the same, just included in the new class structure)
        public void SendKeyPress(ushort keyCode)
        {
            IntPtr extraInfo = GetMessageExtraInfo();
            var inputs = new System.Collections.Generic.List<INPUT>();
            AddKeyDown(inputs, keyCode, extraInfo);
            AddKeyUp(inputs, keyCode, extraInfo);
            SendInputWithLogging(inputs.ToArray());
        }

        private void AddKeyDown(System.Collections.Generic.List<INPUT> list, ushort keyCode, IntPtr extraInfo)
        {
            uint flags = 0;
            if (IsExtendedKey(keyCode)) flags |= KEYEVENTF_EXTENDEDKEY;
            
            list.Add(new INPUT { 
                type = INPUT_KEYBOARD, 
                U = new InputUnion { 
                    ki = new KEYBDINPUT { 
                        wVk = keyCode, 
                        wScan = (ushort)MapVirtualKey(keyCode, 0), 
                        dwFlags = flags,
                        dwExtraInfo = extraInfo
                    } 
                } 
            });
        }

        private void AddKeyUp(System.Collections.Generic.List<INPUT> list, ushort keyCode, IntPtr extraInfo)
        {
            uint flags = KEYEVENTF_KEYUP;
            if (IsExtendedKey(keyCode)) flags |= KEYEVENTF_EXTENDEDKEY;

            list.Add(new INPUT { 
                type = INPUT_KEYBOARD, 
                U = new InputUnion { 
                    ki = new KEYBDINPUT { 
                        wVk = keyCode, 
                        wScan = (ushort)MapVirtualKey(keyCode, 0), 
                        dwFlags = flags,
                        dwExtraInfo = extraInfo
                    } 
                } 
            });
        }

        private bool IsExtendedKey(ushort keyCode)
        {
            return (keyCode >= 0x21 && keyCode <= 0x2E) || // Page Up, Page Down, End, Home, Left, Up, Right, Down, Ins, Del
                   (keyCode >= 0x5B && keyCode <= 0x5C) || // Windows keys
                   (keyCode == 0x12) || // Alt
                   (keyCode == 0x11);   // Ctrl (if right ctrl)
        }
        
        public void KeyDown(ushort keyCode)
        {
            UpdateModifierState(keyCode, true);
            IntPtr extraInfo = GetMessageExtraInfo();
            var inputs = new System.Collections.Generic.List<INPUT>();
            AddKeyDown(inputs, keyCode, extraInfo);
            SendInputWithLogging(inputs.ToArray());
        }

        public void KeyUp(ushort keyCode)
        {
            UpdateModifierState(keyCode, false);
            IntPtr extraInfo = GetMessageExtraInfo();
            var inputs = new System.Collections.Generic.List<INPUT>();
            AddKeyUp(inputs, keyCode, extraInfo);
            SendInputWithLogging(inputs.ToArray());
        }

        private void UpdateModifierState(ushort keyCode, bool isPressed)
        {
            switch (keyCode)
            {
                case 0x10: // VK_SHIFT
                case 0xA0: // VK_LSHIFT
                case 0xA1: // VK_RSHIFT
                    _remoteShift = isPressed;
                    break;
                case 0x11: // VK_CONTROL
                case 0xA2: // VK_LCONTROL
                case 0xA3: // VK_RCONTROL
                    _remoteCtrl = isPressed;
                    break;
                case 0x12: // VK_MENU (ALT)
                case 0xA4: // VK_LMENU
                case 0xA5: // VK_RMENU
                    _remoteAlt = isPressed;
                    break;
                case 0x5B: // VK_LWIN
                case 0x5C: // VK_RWIN
                    _remoteWin = isPressed;
                    break;
            }
            UpdateEffectiveStates();
        }

        public void SendText(string text)
        {
            if (string.IsNullOrEmpty(text)) return;
            IntPtr extraInfo = GetMessageExtraInfo();
            var inputs = new System.Collections.Generic.List<INPUT>();

            // If modifiers are active and we have a single character, 
            // try to send it as a Virtual Key so shortcuts work.
            // VK events are combined with current keyboard state (toggled modifiers).
            bool useVkFallback = text.Length == 1 && (IsCtrlPressed || IsAltPressed || IsWinPressed || IsShiftPressed);

            foreach (char c in text)
            {
                ushort vk = 0;
                if (useVkFallback)
                {
                    // Map common letters and numbers to VK
                    char upper = char.ToUpperInvariant(c);
                    if (upper >= 'A' && upper <= 'Z') vk = (ushort)upper;
                    else if (upper >= '0' && upper <= '9') vk = (ushort)upper;
                }

                if (vk != 0)
                {
                    AddKeyDown(inputs, vk, extraInfo);
                    AddKeyUp(inputs, vk, extraInfo);
                }
                else
                {
                    inputs.Add(new INPUT { type = INPUT_KEYBOARD, U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE, dwExtraInfo = extraInfo } } });
                    inputs.Add(new INPUT { type = INPUT_KEYBOARD, U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP, dwExtraInfo = extraInfo } } });
                }
            }

            SendInputWithLogging(inputs.ToArray());
        }

        public virtual void SendKeys(string keys)
        {
            if (string.IsNullOrEmpty(keys)) return;
            
            _logger.LogInformation($"[InputService] Sending keys: {keys}");
            
            // SendKeys.SendWait needs to run on STA thread if possible, or just call it and hope for the best
            // since we are in a console/background app but using WinForms libraries.
            var thread = new Thread(() =>
            {
                try
                {
                    System.Windows.Forms.SendKeys.SendWait(keys);
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "Error in SendKeys");
                }
            });
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            thread.Join(2000); // Wait up to 2 seconds
        }

        public void SendVolumeKey(ushort volumeKeyCode)
        {
            SendKeyPress(volumeKeyCode);
        }

        public virtual string GetActiveWindowTitle()
        {
            // P/Invoke to get the handle of the foreground window
            IntPtr foregroundWindowHandle = GetForegroundWindow();

            if (foregroundWindowHandle == IntPtr.Zero)
            {
                return "N/A";
            }

            // Get the process ID of the foreground window
            uint processId;
            GetWindowThreadProcessId(foregroundWindowHandle, out processId);

            // Open the process
            Process foregroundProcess = null;
            try
            {
                foregroundProcess = Process.GetProcessById((int)processId);
            }
            catch (ArgumentException)
            {
                // Process might have exited
                return "N/A";
            }

            // Get the window title
            // Use a StringBuilder to get the window text
            StringBuilder windowTitle = new StringBuilder(256);
            if (GetWindowText(foregroundWindowHandle, windowTitle, windowTitle.Capacity) > 0)
            {
                return windowTitle.ToString();
            }
            return "N/A";
        }

        // P/Invoke for GetForegroundWindow
        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        // P/Invoke for GetWindowThreadProcessId
        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

        // P/Invoke for GetWindowText
        [DllImport("user32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);


        public void SetZoom(bool zoomIn)
        {
            const ushort VK_CONTROL = 0x11;
            const int WHEEL_DELTA = 120;
            // Inverted delta because MouseScroll negates it, and we want 
            // positive delta to Windows for Zoom In (Ctrl + Scroll Up)
            int delta = zoomIn ? -WHEEL_DELTA : WHEEL_DELTA;

            _logger.LogInformation($"[InputService] Performing Zoom {(zoomIn ? "In" : "Out")} (delta: {delta})");

            KeyDown(VK_CONTROL);
            // Small delay to ensure CTRL is registered
            Thread.Sleep(50);
            
            // Perform 5 "notches" of zoom for a noticeable effect
            for (int i = 0; i < 5; i++)
            {
                MouseScroll(delta);
                Thread.Sleep(10);
            }
            
            Thread.Sleep(50);
            KeyUp(VK_CONTROL);
        }

        private void SendInputWithLogging(INPUT[] inputs)
        {
            uint successfulEvents = SendInput((uint)inputs.Length, inputs, INPUT.Size);
            if (successfulEvents == 0)
            {
                int errorCode = Marshal.GetLastWin32Error();
                Console.WriteLine($"[InputService] FAILED. Error: {errorCode}. (5 = Access Denied. RUN AS ADMIN)");
            }
        }

        public void Dispose()
        {
            _mouseUpdateTimer?.Dispose();
            _keyboardHook?.Dispose(); // Dispose the keyboard hook
        }
    }

    public enum ModifierKey
    {
        Shift,
        Ctrl,
        Alt,
        Win
    }

    public class ModifierStateEventArgs : EventArgs
    {
        public ModifierKey Modifier { get; }
        public bool IsPressed { get; }

        public ModifierStateEventArgs(ModifierKey modifier, bool isPressed)
        {
            Modifier = modifier;
            IsPressed = isPressed;
        }
    }
}
