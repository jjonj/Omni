using System;
using System.Collections.Generic;
using OmniPower.Native;

namespace OmniPower
{
    /// Fallback detector that polls GetAsyncKeyState. Catches presses that Raw Input misses.
    public sealed class KeyStatePoller
    {
        private const int FirstVirtualKey = 1;
        private const int LastVirtualKey = 254;

        // Virtual-key codes 1-6 are mouse buttons and control-break. Everything from 7 up is a keyboard key.
        private static readonly Dictionary<int, string> MouseButtons = new Dictionary<int, string>
        {
            { 0x01, "left mouse button" },
            { 0x02, "right mouse button" },
            { 0x04, "middle mouse button" },
            { 0x05, "mouse X1 button" },
            { 0x06, "mouse X2 button" },
        };

        private readonly bool[] _wasDown = new bool[256];

        /// Records the current state and clears "pressed since last call" bits, so earlier presses don't count as wake input.
        public void Reset()
        {
            for (int virtualKey = FirstVirtualKey; virtualKey <= LastVirtualKey; virtualKey++)
            {
                short state = NativeMethods.GetAsyncKeyState(virtualKey);
                _wasDown[virtualKey] = (state & 0x8000) != 0;
            }
        }

        /// Returns one event per key or button that went down since the previous call.
        public IReadOnlyList<WakeInputEvent> Poll(DateTime timestampUtc)
        {
            var events = new List<WakeInputEvent>();
            for (int virtualKey = FirstVirtualKey; virtualKey <= LastVirtualKey; virtualKey++)
            {
                short state = NativeMethods.GetAsyncKeyState(virtualKey);
                bool isDown = (state & 0x8000) != 0;
                bool pressedSinceLastCall = (state & 0x0001) != 0;
                bool wentDown = pressedSinceLastCall || (isDown && !_wasDown[virtualKey]);
                _wasDown[virtualKey] = isDown;

                if (!wentDown)
                {
                    continue;
                }

                if (MouseButtons.TryGetValue(virtualKey, out string? buttonName))
                {
                    events.Add(new WakeInputEvent(WakeInputKind.MouseButton, buttonName + " down", timestampUtc, WakeInputSource.KeyStatePoll));
                }
                else if (virtualKey >= 7)
                {
                    events.Add(new WakeInputEvent(WakeInputKind.Key, $"keyboard key VK 0x{virtualKey:X2} down", timestampUtc, WakeInputSource.KeyStatePoll));
                }
            }

            return events;
        }
    }
}
