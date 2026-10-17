using System;
using System.Collections.Generic;
using System.Threading;
using OmniPower.Native;

namespace OmniPower
{
    /// Result of one sleep attempt: what kept the PC awake (or not), plus Windows' own wake report for diagnostics.
    public sealed record SleepAttempt(WakeOutcome Outcome, string WindowsWakeReport);

    /// Sleeps the PC and decides what the wake means.
    /// Any click or key press after resume keeps it awake, whatever Windows reports as the wake reason.
    /// Movement alone sends it back to sleep. Nothing at all keeps it awake (WOL or a swallowed input).
    /// Windows' wake reason is logged for diagnostics only and never used for the decision.
    public sealed class SleepGuard : IDisposable
    {
        private const int PollMilliseconds = 25;

        private readonly ILog _log;
        private readonly TimeSpan _window;
        private readonly Func<int> _suspend;
        private readonly RawInputMonitor _rawInput;
        private readonly KeyStatePoller _keyState = new KeyStatePoller();
        private readonly object _gate = new object();

        // Only the first event of each kind is kept for classification. Counts are logged so the log stays readable.
        private readonly Dictionary<WakeInputKind, WakeInputEvent> _firstByKind = new Dictionary<WakeInputKind, WakeInputEvent>();
        private readonly Dictionary<WakeInputKind, int> _countByKind = new Dictionary<WakeInputKind, int>();
        private DateTime _resumedUtc;
        private int _attempt;
        private bool _started;

        /// suspend defaults to a real sleep. The input test passes a fake to exercise the flow without sleeping.
        public SleepGuard(ILog log, TimeSpan wakeConfirmationWindow, Func<int>? suspend = null)
        {
            _log = log;
            _window = wakeConfirmationWindow;
            _suspend = suspend ?? PowerActions.SuspendToSleep;
            _rawInput = new RawInputMonitor(Record);
        }

        /// Blocks until the PC stays awake. Returns the outcome that kept it awake.
        public WakeOutcome Run()
        {
            while (true)
            {
                SleepAttempt attempt = RunOnce();
                if (attempt.Outcome != WakeOutcome.PointerOnly)
                {
                    return attempt.Outcome;
                }

                _log.Write($"Mouse movement without a click or key press within {_window.TotalSeconds:0} seconds; returning to sleep.");
            }
        }

        /// One sleep, one wake window, and one decision. Does not loop. Returns PointerOnly when it would go back to sleep.
        public SleepAttempt RunOnce()
        {
            EnsureStarted();
            _attempt++;
            _keyState.Reset();
            ClearEvents();
            POINT cursorBefore = GetCursor();

            _log.Write($"Starting sleep attempt {_attempt}.");
            int exitCode = _suspend();
            lock (_gate)
            {
                _resumedUtc = DateTime.UtcNow;
            }

            _log.Write($"Resumed; sleep command exit code: {exitCode}.");
            string wakeReport = PowerActions.LastWakeReport();
            _log.Write("Windows last-wake report (diagnostic only, not used for the decision):" + Environment.NewLine + wakeReport);

            WaitForWakeInput(cursorBefore);

            // Last look, only now that the decision is about to be "back to sleep". Catches input that arrived late.
            WakeOutcome outcome = FinalDecision();
            WriteWindowSummary();

            switch (outcome)
            {
                case WakeOutcome.IntentionalInput:
                    _log.Write("Click or key press detected; remaining awake.");
                    break;
                case WakeOutcome.NoInput:
                    _log.Write("No mouse movement or key/button press; treating wake as WOL and remaining awake.");
                    break;
            }

            return new SleepAttempt(outcome, wakeReport);
        }

        private void WaitForWakeInput(POINT cursorBefore)
        {
            DateTime deadline = DateTime.UtcNow + _window;
            bool cursorMoved = false;
            while (DateTime.UtcNow < deadline)
            {
                DateTime now = DateTime.UtcNow;
                foreach (WakeInputEvent wakeEvent in _keyState.Poll(now))
                {
                    Record(wakeEvent);
                }

                POINT cursor = GetCursor();
                if (!cursorMoved && (cursor.X != cursorBefore.X || cursor.Y != cursorBefore.Y))
                {
                    cursorMoved = true;
                    Record(new WakeInputEvent(WakeInputKind.PointerMove, $"cursor moved to {cursor.X},{cursor.Y}", now, WakeInputSource.CursorPosition));
                }

                if (HasIntentionalInput())
                {
                    return;
                }

                Thread.Sleep(PollMilliseconds);
            }
        }

        private WakeOutcome FinalDecision()
        {
            foreach (WakeInputEvent wakeEvent in _keyState.Poll(DateTime.UtcNow))
            {
                Record(wakeEvent);
            }

            lock (_gate)
            {
                return WakeClassifier.Classify(_firstByKind.Values);
            }
        }

        private void EnsureStarted()
        {
            if (!_started)
            {
                _rawInput.Start();
                _started = true;
            }
        }

        private void Record(WakeInputEvent wakeEvent)
        {
            lock (_gate)
            {
                _countByKind.TryGetValue(wakeEvent.Kind, out int count);
                _countByKind[wakeEvent.Kind] = count + 1;

                if (_firstByKind.ContainsKey(wakeEvent.Kind))
                {
                    return;
                }

                _firstByKind[wakeEvent.Kind] = wakeEvent;
                double millisecondsAfterResume = (wakeEvent.TimestampUtc - _resumedUtc).TotalMilliseconds;
                _log.Write($"Input: {wakeEvent.Description} via {wakeEvent.Source} ({millisecondsAfterResume:+0;-0;0} ms after resume).");
            }
        }

        private bool HasIntentionalInput()
        {
            lock (_gate)
            {
                foreach (WakeInputEvent wakeEvent in _firstByKind.Values)
                {
                    if (WakeClassifier.IsIntentional(wakeEvent))
                    {
                        return true;
                    }
                }

                return false;
            }
        }

        private void ClearEvents()
        {
            lock (_gate)
            {
                _firstByKind.Clear();
                _countByKind.Clear();
            }
        }

        private void WriteWindowSummary()
        {
            lock (_gate)
            {
                if (_countByKind.Count == 0)
                {
                    _log.Write("Wake window summary: no input events.");
                    return;
                }

                var parts = new List<string>();
                foreach (KeyValuePair<WakeInputKind, int> pair in _countByKind)
                {
                    parts.Add($"{pair.Key}={pair.Value}");
                }

                _log.Write("Wake window summary: " + string.Join(", ", parts) + ".");
            }
        }

        private static POINT GetCursor()
        {
            if (!NativeMethods.GetCursorPos(out POINT point))
            {
                throw new InvalidOperationException("Windows did not return the cursor position.");
            }

            return point;
        }

        public void Dispose()
        {
            _rawInput.Dispose();
        }
    }
}
