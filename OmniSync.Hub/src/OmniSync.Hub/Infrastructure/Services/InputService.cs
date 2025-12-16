using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Drawing; // For Point struct
using System.Windows.Forms; // Required for Screen.PrimaryScreen.Bounds

namespace OmniSync.Hub.Infrastructure.Services
{
    public class InputService : IDisposable
    {
        // P/Invoke Declarations
        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        private static extern uint MapVirtualKey(uint uCode, uint uMapType);

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

        // Keyboard Flags
        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const uint KEYEVENTF_UNICODE = 0x0004;

        // Mouse Interpolation Fields
        private Point _currentMouseDeltaTarget = new Point(0, 0); // Stores accumulated delta
        private readonly object _mouseTargetLock = new object();
        private System.Threading.Timer _mouseUpdateTimer;
        private const int _interpolationIntervalMs = 10; // How often the timer ticks (approx 60Hz)
        // private const int _maxMovementPerTick = 10; // Max pixels moved per tick - REMOVED

        public InputService()
        {
            // Start the timer to periodically update mouse position
            _mouseUpdateTimer = new System.Threading.Timer(MouseUpdateTimer_Tick, null, 0, _interpolationIntervalMs);
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
            INPUT[] inputs = new INPUT[2];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].U.ki = new KEYBDINPUT { wVk = keyCode, wScan = 0, dwFlags = 0 };
            inputs[1].type = INPUT_KEYBOARD;
            inputs[1].U.ki = new KEYBDINPUT { wVk = keyCode, wScan = 0, dwFlags = KEYEVENTF_KEYUP };
            SendInputWithLogging(inputs);
        }
        
        public void KeyDown(ushort keyCode)
        {
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].U.ki = new KEYBDINPUT { wVk = keyCode, wScan = 0, dwFlags = 0 };
            SendInputWithLogging(inputs);
        }

        public void KeyUp(ushort keyCode)
        {
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].U.ki = new KEYBDINPUT { wVk = keyCode, wScan = 0, dwFlags = KEYEVENTF_KEYUP };
            SendInputWithLogging(inputs);
        }

        public void SendText(string text)
        {
            if (string.IsNullOrEmpty(text)) return;
            var inputList = new System.Collections.Generic.List<INPUT>();
            foreach (char c in text)
            {
                inputList.Add(new INPUT { type = INPUT_KEYBOARD, U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE } } });
                inputList.Add(new INPUT { type = INPUT_KEYBOARD, U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP } } });
            }
            SendInputWithLogging(inputList.ToArray());
        }

        public void SendVolumeKey(ushort volumeKeyCode)
        {
            SendKeyPress(volumeKeyCode);
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
        }
    }
}
