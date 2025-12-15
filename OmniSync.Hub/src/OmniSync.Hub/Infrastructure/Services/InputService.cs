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
        private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;
        private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000; // For multi-monitor support

        // Keyboard Flags
        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const uint KEYEVENTF_UNICODE = 0x0004;

        public void MoveMouse(int x, int y)
        {
            // 1. Get Screen Resolution
            int screenWidth = GetSystemMetrics(SM_CXSCREEN);
            int screenHeight = GetSystemMetrics(SM_CYSCREEN);

            // 2. Normalize coordinates to 0-65535 range (Required for MOUSEEVENTF_ABSOLUTE)
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
                // Key Down (Unicode)
                inputList.Add(new INPUT { type = INPUT_KEYBOARD, U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE } } });
                // Key Up (Unicode)
                inputList.Add(new INPUT { type = INPUT_KEYBOARD, U = new InputUnion { ki = new KEYBDINPUT { wScan = c, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP } } });
            }
            SendInputWithLogging(inputList.ToArray());
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
    }
}