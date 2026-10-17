"""Static regressions for OmniSweep activation and physical-key toggling."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HUB = ROOT / "OmniSync.Hub" / "src" / "OmniSync.Hub"
WINDOW = (HUB / "Presentation" / "OmniSweepWindow.xaml.cs").read_text(encoding="utf-8-sig")
XAML = (HUB / "Presentation" / "OmniSweepWindow.xaml").read_text(encoding="utf-8-sig")
HOTKEYS = (HUB / "Logic" / "Services" / "GlobalHotkeyService.cs").read_text(encoding="utf-8-sig")


def check(name, condition):
    print(f"[{'PASS' if condition else 'FAIL'}] {name}")
    return bool(condition)


def main():
    opening = WINDOW.split("private async System.Threading.Tasks.Task FinishOpeningAsync()", 1)[1].split(
        "public OmniSweepWindow(", 1
    )[0]
    deactivation = WINDOW.split("private void Window_Deactivated(", 1)[1].split(
        "public async void CloseAfterHotkeyRelease()", 1
    )[0]
    results = [
        check("Window requests foreground activation on show", 'ShowActivated="True"' in XAML),
        check("Keyboard focus is requested before waiting for modifier release",
              opening.index("ForegroundGrid();") < opening.index("await WaitForModifiersReleasedAsync();")),
        check("Foreground checks use Win32 rather than stale WPF IsActive",
              "private bool HasForeground()" in WINDOW
              and "if (!HasForeground()) { ForegroundGrid(force: true);" in opening
              and 'if (!HasForeground()) { TraceFocus("activation-failed"); QueueClose(); }' in opening),
        check("Foreground polling handles missing WPF Deactivated events",
              "_foregroundWatch.Tick" in WINDOW and "_foregroundWatch.Start();" in opening
              and "_foregroundWatch.Stop();" in WINDOW),
        check("Lost-focus closing remains armed after opening",
              "_activationReady = true;" in opening and "CloseForLostFocus();" in deactivation),
        check("Hotkey auto-repeat is identified before key state is updated",
              HOTKEYS.index("bool repeatedKeyDown") < HOTKEYS.index("_pressedKeys.Add(e.Key)")),
        check("Active OmniSweep ignores repeated toggles",
              "if (sweep != null && MatchHotkey(sweep.Key, e))" in HOTKEYS
              and "if (!repeatedKeyDown) ExecuteHotkeyAction(sweep.Action);" in HOTKEYS),
        check("Opening OmniSweep ignores repeated toggles",
              'if (repeatedKeyDown && (hotkey.Action == "OPEN_OMNISWEEP" || hotkey.Action == "SHOW_PROJECT_SELECTOR"))' in HOTKEYS),
        check("Modifier wait cannot block auto-hide indefinitely",
              "Environment.TickCount64 + 350" in WINDOW),
        check("Foreground fallback joins and detaches input threads",
              "AttachThreadInput(currentThread, foregroundThread, true)" in WINDOW
              and "AttachThreadInput(currentThread, foregroundThread, false)" in WINDOW),
        check("Unfocused popup closes if activation still fails",
              'TraceFocus("activation-failed"); QueueClose();' in opening),
        check("Hotkey is consumed instead of leaking to the previous application",
              'if (hotkey.Action == "OPEN_OMNISWEEP" || hotkey.Action == "SHOW_PROJECT_SELECTOR") e.Handled = true;' in HOTKEYS),
        check("Focus lifecycle is logged",
              'TraceFocus("loaded")' in WINDOW and 'TraceFocus("activated")' in WINDOW
              and 'TraceFocus("deactivated")' in WINDOW and 'TraceFocus("opening-armed")' in WINDOW),
        check("Modifier release still protects focus-loss closing",
              "await WaitForModifiersReleasedAsync();" in deactivation),
    ]
    return 0 if all(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
