using System;
using System.Collections.Generic;

namespace OmniPower
{
    public enum WakeInputKind
    {
        PointerMove,
        PointerWheel,
        MouseButton,
        Key,
    }

    public enum WakeInputSource
    {
        RawInput,
        KeyStatePoll,
        CursorPosition,
    }

    public sealed record WakeInputEvent(
        WakeInputKind Kind,
        string Description,
        DateTime TimestampUtc,
        WakeInputSource Source);

    public enum WakeOutcome
    {
        /// A mouse button or key was pressed after wake. Stay awake.
        IntentionalInput,

        /// Only pointer movement after wake. Go back to sleep; movement alone is treated as a bump.
        PointerOnly,

        /// Nothing seen after wake, e.g. a network wake (WOL) or an input that Windows swallowed. Stay awake.
        NoInput,
    }

    public static class WakeClassifier
    {
        public static WakeOutcome Classify(IEnumerable<WakeInputEvent> events)
        {
            bool pointerMoved = false;
            foreach (WakeInputEvent wakeEvent in events)
            {
                if (IsIntentional(wakeEvent))
                {
                    return WakeOutcome.IntentionalInput;
                }

                if (wakeEvent.Kind == WakeInputKind.PointerMove)
                {
                    pointerMoved = true;
                }
            }

            return pointerMoved ? WakeOutcome.PointerOnly : WakeOutcome.NoInput;
        }

        public static bool IsIntentional(WakeInputEvent wakeEvent)
        {
            return wakeEvent.Kind == WakeInputKind.MouseButton || wakeEvent.Kind == WakeInputKind.Key;
        }
    }
}
