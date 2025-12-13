using System;
using System.Runtime.InteropServices;
using System.Text; // For Encoding.Unicode

namespace OmniSync.Hub.Infrastructure.Services
{
    public class InputService
    {
        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [StructLayout(LayoutKind.Sequential)]
        public struct INPUT
        {
            public uint type;
            public InputUnion U;
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

        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const uint KEYEVENTF_UNICODE = 0x0004;
        private const uint KEYEVENTF_SCANCODE = 0x0008; // Not directly used but good to know

        // Virtual Key Codes (common ones, can expand as needed)
        public const ushort VK_SHIFT = 0x10;
        public const ushort VK_CONTROL = 0x11;
        public const ushort VK_MENU = 0x12; // Alt key
        public const ushort VK_LWIN = 0x5B; // Left Windows key
        public const ushort VK_RWIN = 0x5C; // Right Windows key
        public const ushort VK_RETURN = 0x0D; // Enter key
        public const ushort VK_BACK = 0x08; // Backspace key
        public const ushort VK_TAB = 0x09; // Tab key
        public const ushort VK_ESCAPE = 0x1B; // Esc key
        public const ushort VK_LEFT = 0x25; // Left arrow key
        public const ushort VK_UP = 0x26; // Up arrow key
        public const ushort VK_RIGHT = 0x27; // Right arrow key
        public const ushort VK_DOWN = 0x28; // Down arrow key
        public const ushort VK_DELETE = 0x2B; // Delete key
        public const ushort VK_HOME = 0x24; // Home key
        public const ushort VK_END = 0x23; // End key
        public const ushort VK_PRIOR = 0x21; // Page Up
        public const ushort VK_NEXT = 0x22; // Page Down


        public void MoveMouse(int dx, int dy)
        {
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_MOUSE;
            inputs[0].U.mi = new MOUSEINPUT
            {
                dx = dx,
                dy = dy,
                dwFlags = MOUSEEVENTF_MOVE
            };

            SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));
        }

        public void SendKeyPress(ushort keyCode)
        {
            // Key Down
            INPUT[] inputs = new INPUT[2];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].U.ki = new KEYBDINPUT
            {
                wVk = keyCode,
                dwFlags = 0 // Key down
            };
            // Key Up
            inputs[1].type = INPUT_KEYBOARD;
            inputs[1].U.ki = new KEYBDINPUT
            {
                wVk = keyCode,
                dwFlags = KEYEVENTF_KEYUP
            };

            SendInput(2, inputs, Marshal.SizeOf(typeof(INPUT)));
        }
        
        public void KeyDown(ushort keyCode)
        {
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].U.ki = new KEYBDINPUT
            {
                wVk = keyCode,
                dwFlags = 0 // Key down
            };
            SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));
        }

        public void KeyUp(ushort keyCode)
        {
            INPUT[] inputs = new INPUT[1];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].U.ki = new KEYBDINPUT
            {
                wVk = keyCode,
                dwFlags = KEYEVENTF_KEYUP
            };
            SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));
        }

        public void SendText(string text)
        {
            if (string.IsNullOrEmpty(text)) return;

            // Using KEYEVENTF_UNICODE to send characters
            INPUT[] inputs = new INPUT[text.Length * 2]; // Each char needs a down and up event
            int i = 0;
            foreach (char c in text)
            {
                // Key down
                inputs[i].type = INPUT_KEYBOARD;
                inputs[i].U.ki = new KEYBDINPUT
                {
                    wVk = 0, // No virtual key for unicode characters
                    wScan = (ushort)c, // Unicode character
                    dwFlags = KEYEVENTF_UNICODE // Indicate Unicode char
                };
                i++;

                // Key up
                inputs[i].type = INPUT_KEYBOARD;
                inputs[i].U.ki = new KEYBDINPUT
                {
                    wVk = 0, // No virtual key for unicode characters
                    wScan = (ushort)c, // Unicode character
                    dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP // Indicate Unicode char and key up
                };
                i++;
            }
            SendInput((uint)inputs.Length, inputs, Marshal.SizeOf(typeof(INPUT)));
        }
    }
}
