using System;
using Xunit;

namespace OmniPower.Tests
{
    /// Builds RAWINPUT buffers by hand, so the offsets are checked against the Win32 struct layout.
    public class RawInputParserTests
    {
        private const int PointerSize = 8; // x64 layout: header is 24 bytes.
        private static readonly DateTime Now = new DateTime(2026, 10, 8, 12, 0, 0, DateTimeKind.Utc);

        private static byte[] Mouse(ushort flags = 0, ushort buttonFlags = 0, int lastX = 0, int lastY = 0)
        {
            var buffer = new byte[RawInputParser.HeaderSize(PointerSize) + 24];
            BitConverter.GetBytes(0u).CopyTo(buffer, 0); // RIM_TYPEMOUSE
            int d = RawInputParser.HeaderSize(PointerSize);
            BitConverter.GetBytes(flags).CopyTo(buffer, d);
            BitConverter.GetBytes(buttonFlags).CopyTo(buffer, d + 4);
            BitConverter.GetBytes(lastX).CopyTo(buffer, d + 12);
            BitConverter.GetBytes(lastY).CopyTo(buffer, d + 16);
            return buffer;
        }

        private static byte[] Keyboard(ushort flags, ushort virtualKey)
        {
            var buffer = new byte[RawInputParser.HeaderSize(PointerSize) + 16];
            BitConverter.GetBytes(1u).CopyTo(buffer, 0); // RIM_TYPEKEYBOARD
            int d = RawInputParser.HeaderSize(PointerSize);
            BitConverter.GetBytes(flags).CopyTo(buffer, d + 2);
            BitConverter.GetBytes(virtualKey).CopyTo(buffer, d + 6);
            return buffer;
        }

        [Fact]
        public void HeaderIsTwentyFourBytesOnX64AndSixteenOnX86()
        {
            Assert.Equal(24, RawInputParser.HeaderSize(8));
            Assert.Equal(16, RawInputParser.HeaderSize(4));
        }

        [Fact]
        public void LeftButtonDownIsMouseButtonEvent()
        {
            var wakeEvent = RawInputParser.Parse(Mouse(buttonFlags: 0x0001), PointerSize, Now);
            Assert.NotNull(wakeEvent);
            Assert.Equal(WakeInputKind.MouseButton, wakeEvent!.Kind);
            Assert.Equal(WakeInputSource.RawInput, wakeEvent.Source);
        }

        [Fact]
        public void ButtonUpIsIgnored()
        {
            Assert.Null(RawInputParser.Parse(Mouse(buttonFlags: 0x0002), PointerSize, Now)); // RI_MOUSE_LEFT_BUTTON_UP
        }

        [Fact]
        public void RelativeMoveIsPointerMove()
        {
            var wakeEvent = RawInputParser.Parse(Mouse(lastX: 3, lastY: 0), PointerSize, Now);
            Assert.Equal(WakeInputKind.PointerMove, wakeEvent!.Kind);
        }

        [Fact]
        public void ZeroRelativeReportIsIgnored()
        {
            Assert.Null(RawInputParser.Parse(Mouse(), PointerSize, Now));
        }

        [Fact]
        public void WheelIsPointerWheel()
        {
            var wakeEvent = RawInputParser.Parse(Mouse(buttonFlags: 0x0400), PointerSize, Now);
            Assert.Equal(WakeInputKind.PointerWheel, wakeEvent!.Kind);
        }

        [Fact]
        public void KeyDownIsKeyEvent()
        {
            var wakeEvent = RawInputParser.Parse(Keyboard(flags: 0, virtualKey: 0x41), PointerSize, Now);
            Assert.Equal(WakeInputKind.Key, wakeEvent!.Kind);
            Assert.Contains("0x41", wakeEvent.Description);
        }

        [Fact]
        public void KeyUpIsIgnored()
        {
            Assert.Null(RawInputParser.Parse(Keyboard(flags: 0x01, virtualKey: 0x41), PointerSize, Now)); // RI_KEY_BREAK
        }

        [Fact]
        public void TruncatedBufferIsIgnored()
        {
            Assert.Null(RawInputParser.Parse(new byte[10], PointerSize, Now));
        }
    }
}
