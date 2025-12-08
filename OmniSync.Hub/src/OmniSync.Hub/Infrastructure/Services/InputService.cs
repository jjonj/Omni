using System;
using System.Runtime.InteropServices;

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
            INPUT[] inputs = new INPUT[2];
            inputs[0].type = INPUT_KEYBOARD;
            inputs[0].U.ki = new KEYBDINPUT
            {
                wVk = keyCode,
                dwFlags = 0
            };
            inputs[1].type = INPUT_KEYBOARD;
            inputs[1].U.ki = new KEYBDINPUT
            {
                wVk = keyCode,
                dwFlags = KEYEVENTF_KEYUP
            };

            SendInput(2, inputs, Marshal.SizeOf(typeof(INPUT)));
        }
    }
}
