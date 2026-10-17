using System;
using System.Collections.Generic;
using Xunit;

namespace OmniPower.Tests
{
    public class WakeDecisionTests
    {
        private static readonly DateTime Now = new DateTime(2026, 10, 8, 12, 0, 0, DateTimeKind.Utc);

        private static WakeInputEvent Event(WakeInputKind kind) =>
            new WakeInputEvent(kind, kind.ToString(), Now, WakeInputSource.RawInput);

        [Fact]
        public void MouseButtonAfterWakeKeepsPcAwake()
        {
            var events = new[] { Event(WakeInputKind.MouseButton) };
            Assert.Equal(WakeOutcome.IntentionalInput, WakeClassifier.Classify(events));
        }

        [Fact]
        public void KeyPressAfterWakeKeepsPcAwake()
        {
            var events = new[] { Event(WakeInputKind.Key) };
            Assert.Equal(WakeOutcome.IntentionalInput, WakeClassifier.Classify(events));
        }

        [Fact]
        public void ClickAfterMouseMoveStillKeepsPcAwake()
        {
            // The usual case: the pointer moves onto a target, then it's clicked.
            var events = new[] { Event(WakeInputKind.PointerMove), Event(WakeInputKind.MouseButton) };
            Assert.Equal(WakeOutcome.IntentionalInput, WakeClassifier.Classify(events));
        }

        [Fact]
        public void MouseMovementAloneReturnsToSleep()
        {
            var events = new[] { Event(WakeInputKind.PointerMove) };
            Assert.Equal(WakeOutcome.PointerOnly, WakeClassifier.Classify(events));
        }

        [Fact]
        public void WheelAloneIsNotTreatedAsMovementOrInput()
        {
            var events = new[] { Event(WakeInputKind.PointerWheel) };
            Assert.Equal(WakeOutcome.NoInput, WakeClassifier.Classify(events));
        }

        [Fact]
        public void NoEventsMeansNoInput()
        {
            Assert.Equal(WakeOutcome.NoInput, WakeClassifier.Classify(new List<WakeInputEvent>()));
        }
    }
}
