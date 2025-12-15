using System;
using System.Runtime.InteropServices;
using System.Windows.Forms; // Required for Screen.PrimaryScreen.Bounds

namespace OmniSync.Hub.Infrastructure.Services
{
    public class InputService
    {
        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        private static extern uint MapVirtualKey(uint uCode, uint uMapType);

        [DllImport("user32.dll")]
        private static extern int GetSystemMetrics(int nIndex);

        private const int SM_CXSCREEN = 0;
        private const int SM_CYSCREEN = 1;

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
            [FieldOffset(0)]
            public MOUSEINPUT mi;
            [FieldOffset(0)]
            public KEYBDINPUT ki;
            [FieldOffset(0)]
            public HARDWAREINPUT hi;
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

        private const int INPUT_MOUSE = 0;
        private const int INPUT_KEYBOARD = 1;

        // Flags
        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;
        private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000; // Important for multi-monitor

        private const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const uint KEYEVENTF_UNICODE = 0x0004;
        private const uint KEYEVENTF_SCANCODE = 0x0008;

        public void MoveMouse(int x, int y)
        {
            // Get screen resolution
            int screenWidth = GetSystemMetrics(SM_CXSCREEN);
            int screenHeight = GetSystemMetrics(SM_CYSCREEN);

            // Convert pixels to normalized absolute coordinates (0 - 65535)
            // This is required when using MOUSEEVENTF_ABSOLUTE
            int normalizedX = (x * 65535) / screenWidth;
            int normalizedY = (y * 65535) / screenHeight;

            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_MOUSE;
            inputs[0].U.mi = new MOUSEINPUT
            {
                dx = normalizedX,
                dy = normalizedY,
                dwFlags = MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK
            };

            SendInputWithLogging(inputs);
        }

        public void SendKeyPress(ushort keyCode)
        {
            INPUT[] inputs = new INPUT[2];

            // Key Down
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].U.ki = new KEYBDINPUT
            {
                wVk = keyCode,
                // We are not setting KEYEVENTF_SCANCODE, so Windows uses wVk.
                // wScan is ignored unless that flag is set.
                wScan = 0, 
                dwFlags = 0 
            };

            // Key Up
            inputs[1].type = INPUT_KEYBOARD;
            inputs[1].U.ki = new KEYBDINPUT
            {
                wVk = keyCode,
                wScan = 0,
                dwFlags = KEYEVENTF_KEYUP
            };

            SendInputWithLogging(inputs);
        }
        
        public void KeyDown(ushort keyCode)
        {
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].U.ki = new KEYBDINPUT
            {
                wVk = keyCode,
                wScan = 0,
                dwFlags = 0
            };
            SendInputWithLogging(inputs);
        }

        public void KeyUp(ushort keyCode)
        {
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].U.ki = new KEYBDINPUT
            {
                wVk = keyCode,
                wScan = 0,
                dwFlags = KEYEVENTF_KEYUP
            };
            SendInputWithLogging(inputs);
        }

        public void SendText(string text)
        {
            if (string.IsNullOrEmpty(text)) return;

            var inputList = new System.Collections.Generic.List<INPUT>();

            foreach (char c in text)
            {
                // Key Down
                var down = new INPUT
                {
                    type = INPUT_KEYBOARD,
                    U = new InputUnion
                    {
                        ki = new KEYBDINPUT
                        {
                            wVk = 0,
                            wScan = c,
                            dwFlags = KEYEVENTF_UNICODE
                        }
                    }
                };
                inputList.Add(down);

                // Key Up
                var up = new INPUT
                {
                    type = INPUT_KEYBOARD,
                    U = new InputUnion
                    {
                        ki = new KEYBDINPUT
                        {
                            wVk = 0,
                            wScan = c,
                            dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP
                        }
                    }
                };
                inputList.Add(up);
            }

            SendInputWithLogging(inputList.ToArray());
        }

        private void SendInputWithLogging(INPUT[] inputs)
        {
            uint successfulEvents = SendInput((uint)inputs.Length, inputs, INPUT.Size);
            
            if (successfulEvents == 0)
            {
                int errorCode = Marshal.GetLastWin32Error();
                Console.WriteLine($"[InputService] FAILED. Error Code: {errorCode}. (Error 5 = Access Denied/Run as Admin)");
            }
            else
            {
                // Console.WriteLine($"[InputService] Success. Processed {successfulEvents} events.");
            }
        }
    }
}
