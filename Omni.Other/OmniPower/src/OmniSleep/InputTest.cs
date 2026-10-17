using System;
using System.IO;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using OmniPower;

namespace OmniSleep
{
    /// Runs `OmniSleep.exe --input-test`. Puts the PC to sleep once per scenario and tells you how to wake it.
    /// Each scenario has an expected result. The real guard decides, and the test reports PASS or FAIL.
    internal static class InputTest
    {
        private const int CountdownSeconds = 5;

        private static readonly Scenario[] Scenarios =
        {
            new Scenario(
                "Mouse movement only",
                "Wake it by MOVING THE MOUSE only. Do not click or press any key.",
                WakeOutcome.PointerOnly,
                "PC goes back to sleep"),
            new Scenario(
                "Mouse click",
                "Wake it with a MOUSE CLICK. Do not move the mouse first.",
                WakeOutcome.IntentionalInput,
                "PC stays awake"),
            new Scenario(
                "Keyboard",
                "Wake it with a KEY PRESS (for example Shift). Do not move the mouse.",
                WakeOutcome.IntentionalInput,
                "PC stays awake"),
            new Scenario(
                "Wake on LAN",
                "Wake it with WAKE ON LAN (magic packet from another device). Do NOT touch the mouse or keyboard.",
                WakeOutcome.NoInput,
                "PC stays awake"),
            new Scenario(
                "Movement, then click",
                "Wake it by moving the mouse, then CLICK within 10 seconds.",
                WakeOutcome.IntentionalInput,
                "PC stays awake, even though the wake started with movement"),
        };

        public static int Run(bool simulate)
        {
            ConsoleWindow.Attach();
            var fileLog = new FileLog("input-test");
            ILog log = new TeeLog(new ConsoleLog(), fileLog);

            TrySetTitle("OmniPower wake test");
            Console.WriteLine("OmniPower wake test");
            Console.WriteLine("====================");
            Console.WriteLine($"This puts the PC to sleep {Scenarios.Length} times, one test after another.");
            Console.WriteLine("Each test tells you how to wake the PC. Do exactly that, and nothing else.");
            Console.WriteLine("After each wake, the result is printed here. Stay on this console window.");
            if (simulate)
            {
                Console.WriteLine("SIMULATION: the PC will NOT sleep. The sleep is faked with a 3 second pause.");
            }

            Console.WriteLine();
            Console.WriteLine("Log: " + fileLog.FilePath);
            Console.WriteLine();
            Console.WriteLine("Press Enter to start.");
            Console.ReadLine();

            Func<int>? suspend = simulate ? () => { Thread.Sleep(3000); return 0; } : (Func<int>?)null;
            var results = new List<(Scenario Scenario, WakeOutcome Outcome)>();

            using (var guard = new SleepGuard(log, PowerOptions.Parse(Array.Empty<string>()).WakeConfirmationWindow, suspend))
            {
                for (int i = 0; i < Scenarios.Length; i++)
                {
                    Scenario scenario = Scenarios[i];
                    ClearScreen();
                    Console.WriteLine($"TEST {i + 1} of {Scenarios.Length}: {scenario.Name}");
                    Console.WriteLine(new string('-', 60));
                    Console.WriteLine("WHEN THE PC IS ASLEEP: " + scenario.Action);
                    Console.WriteLine("EXPECTED: " + scenario.ExpectedText + ".");
                    Console.WriteLine();

                    for (int second = CountdownSeconds; second >= 1; second--)
                    {
                        Console.WriteLine($"PC sleeping in {second}...");
                        Thread.Sleep(1000);
                    }

                    log.Write($"Test {i + 1} ({scenario.Name}): sleeping.");
                    SleepAttempt attempt = guard.RunOnce();
                    results.Add((scenario, attempt.Outcome));

                    Console.WriteLine();
                    Console.WriteLine("Windows wake report (informational only):");
                    foreach (string line in attempt.WindowsWakeReport.Split('\n').Select(l => l.TrimEnd('\r', ' ')).Where(l => l.Length > 0))
                    {
                        Console.WriteLine("    " + line);
                    }

                    Console.WriteLine();
                    Console.WriteLine("Guard decided: " + Describe(attempt.Outcome));
                    bool passed = attempt.Outcome == scenario.Expected;
                    Console.WriteLine(passed ? "RESULT: PASS" : "RESULT: FAIL - " + FailureHint(scenario, attempt.Outcome));
                    log.Write($"Test {i + 1} ({scenario.Name}): decided {attempt.Outcome}; {(passed ? "PASS" : "FAIL")}.");

                    Console.WriteLine();
                    Console.WriteLine("Next test starts shortly. You can leave the PC as it is.");
                    Thread.Sleep(5000);
                }
            }

            ClearScreen();
            Console.WriteLine("SUMMARY");
            Console.WriteLine(new string('-', 60));
            foreach ((Scenario scenario, WakeOutcome outcome) in results)
            {
                Console.WriteLine($"{(outcome == scenario.Expected ? "PASS" : "FAIL")}  {scenario.Name,-22} decided: {Describe(outcome)}");
            }

            int passCount = results.Count(r => r.Outcome == r.Scenario.Expected);
            Console.WriteLine();
            Console.WriteLine($"{passCount} of {results.Count} passed.");
            Console.WriteLine("Log: " + fileLog.FilePath);
            Console.WriteLine();
            Console.WriteLine("Press Enter to close.");
            Console.ReadLine();
            return passCount == results.Count ? 0 : 1;
        }

        private static void ClearScreen()
        {
            // Console.Clear throws when stdout isn't a real console, so blank lines are used instead.
            Console.WriteLine(new string('\n', 40));
        }

        private static void TrySetTitle(string title)
        {
            try
            {
                Console.Title = title;
            }
            catch (IOException)
            {
                // Cosmetic only.
            }
        }

        private static string Describe(WakeOutcome outcome)
        {
            switch (outcome)
            {
                case WakeOutcome.PointerOnly:
                    return "back to sleep (movement only)";
                case WakeOutcome.IntentionalInput:
                    return "stay awake (click or key)";
                default:
                    return "stay awake (no input seen, WOL)";
            }
        }

        private static string FailureHint(Scenario scenario, WakeOutcome outcome)
        {
            if (outcome == WakeOutcome.PointerOnly)
            {
                return "PC went back to sleep, but it should have stayed awake.";
            }

            if (outcome == WakeOutcome.NoInput && scenario.Expected == WakeOutcome.IntentionalInput)
            {
                return "no click or key was seen by either detector. Windows may have swallowed the wake input. Check the log.";
            }

            return "PC stayed awake, but it should have gone back to sleep.";
        }

        private sealed record Scenario(string Name, string Action, WakeOutcome Expected, string ExpectedText);

        private sealed class ConsoleLog : ILog
        {
            public void Write(string message)
            {
                Console.WriteLine($"{DateTime.Now:HH:mm:ss} | {message}");
            }
        }
    }
}
