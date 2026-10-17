using System;

namespace OmniPower
{
    /// Decodes a WM_INPUT payload (RAWINPUT) into a wake event.
    /// Pure byte parsing with no Win32 calls, so tests can feed it synthetic buffers.
    public static class RawInputParser
    {
        private const uint RimTypeMouse = 0;
        private const uint RimTypeKeyboard = 1;

        // RAWKEYBOARD.Flags: RI_KEY_BREAK means key release.
        private const ushort RiKeyBreak = 0x01;

        // RAWMOUSE.usFlags: MOUSE_MOVE_ABSOLUTE means the coordinates are absolute (tablets, remote desktop).
        private const ushort MouseMoveAbsolute = 0x01;

        // RAWMOUSE.usButtonFlags: wheel bits.
        private const ushort MouseWheel = 0x0400;
        private const ushort MouseHorizontalWheel = 0x0800;

        private static readonly (ushort Flag, string Name)[] ButtonDownFlags =
        {
            (0x0001, "left mouse button"),
            (0x0004, "right mouse button"),
            (0x0010, "middle mouse button"),
            (0x0040, "mouse X1 button"),
            (0x0100, "mouse X2 button"),
        };

        /// RAWINPUTHEADER is dwType + dwSize + hDevice + wParam, where the two handle fields are pointer-sized.
        public static int HeaderSize(int pointerSize)
        {
            return 8 + 2 * pointerSize;
        }

        /// Returns null for reports that carry no wake signal, such as key releases and zero-length mouse reports.
        public static WakeInputEvent? Parse(byte[] buffer, int pointerSize, DateTime timestampUtc)
        {
            if (buffer == null || buffer.Length < HeaderSize(pointerSize) + 4)
            {
                return null;
            }

            uint type = BitConverter.ToUInt32(buffer, 0);
            int data = HeaderSize(pointerSize);
            return type switch
            {
                RimTypeMouse => ParseMouse(buffer, data, timestampUtc),
                RimTypeKeyboard => ParseKeyboard(buffer, data, timestampUtc),
                _ => null,
            };
        }

        private static WakeInputEvent? ParseMouse(byte[] buffer, int offset, DateTime timestampUtc)
        {
            // RAWMOUSE layout: usFlags@0, usButtonFlags@4, usButtonData@6, lLastX@12, lLastY@16.
            if (buffer.Length < offset + 20)
            {
                return null;
            }

            ushort flags = BitConverter.ToUInt16(buffer, offset);
            ushort buttonFlags = BitConverter.ToUInt16(buffer, offset + 4);
            int lastX = BitConverter.ToInt32(buffer, offset + 12);
            int lastY = BitConverter.ToInt32(buffer, offset + 16);

            foreach ((ushort flag, string name) in ButtonDownFlags)
            {
                if ((buttonFlags & flag) != 0)
                {
                    return new WakeInputEvent(WakeInputKind.MouseButton, name + " down", timestampUtc, WakeInputSource.RawInput);
                }
            }

            if ((buttonFlags & (MouseWheel | MouseHorizontalWheel)) != 0)
            {
                return new WakeInputEvent(WakeInputKind.PointerWheel, "mouse wheel", timestampUtc, WakeInputSource.RawInput);
            }

            bool absolute = (flags & MouseMoveAbsolute) != 0;
            if (absolute || lastX != 0 || lastY != 0)
            {
                return new WakeInputEvent(WakeInputKind.PointerMove, "pointer moved", timestampUtc, WakeInputSource.RawInput);
            }

            return null;
        }

        private static WakeInputEvent? ParseKeyboard(byte[] buffer, int offset, DateTime timestampUtc)
        {
            // RAWKEYBOARD layout: MakeCode@0, Flags@2, VKey@6.
            if (buffer.Length < offset + 8)
            {
                return null;
            }

            ushort flags = BitConverter.ToUInt16(buffer, offset + 2);
            ushort virtualKey = BitConverter.ToUInt16(buffer, offset + 6);
            if ((flags & RiKeyBreak) != 0)
            {
                return null;
            }

            return new WakeInputEvent(
                WakeInputKind.Key,
                $"keyboard key VK 0x{virtualKey:X2} down",
                timestampUtc,
                WakeInputSource.RawInput);
        }
    }
}
