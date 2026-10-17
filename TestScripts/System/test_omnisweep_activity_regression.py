"""Source regressions for inline OmniSweep rename and setup window activation.

Run from repo root: python TestScripts/System/test_omnisweep_activity_regression.py
The Hub build separately validates C#/XAML compilation.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HUB = ROOT / "OmniSync.Hub" / "src" / "OmniSync.Hub"
WINDOW = (HUB / "Presentation" / "OmniSweepWindow.xaml.cs").read_text(encoding="utf-8-sig")
XAML = (HUB / "Presentation" / "OmniSweepWindow.xaml").read_text(encoding="utf-8-sig")
CAPTURE = (HUB / "Logic" / "LayoutCaptureService.cs").read_text(encoding="utf-8-sig")
LAUNCHER = (HUB / "Logic" / "ProjectLauncherService.cs").read_text(encoding="utf-8-sig")
SETTINGS = (HUB / "Infrastructure" / "Services" / "HubSettingsService.cs").read_text(encoding="utf-8-sig")


def check(name, ok):
    print(f"[{'PASS' if ok else 'FAIL'}] {name}")
    return ok


def main():
    results = [
        check("Rename opens the inline editor instead of a dialog",
              'Add("Rename", () => BeginRename(setup, tile));' in WINDOW),
        check("Inline editor is part of the tile template",
              'x:Name="TileNameEditor"' in XAML and 'x:Name="TileNameLabel"' in XAML),
        check("Rename supports Enter, Escape and losing focus",
              'FinishRename(save: true, restoreGridFocus: true)' in WINDOW
              and 'FinishRename(save: false, restoreGridFocus: true)' in WINDOW
              and 'TileNameEditor_LostKeyboardFocus' in WINDOW),
        check("Rename protects the popup from context menu deactivation",
              'if (_renamingSetup != null) return;' in WINDOW
              and 'if (_renameEditor != null) FinishRename(save: true' in WINDOW),
        check("Blank names are rejected inline",
              'string.IsNullOrWhiteSpace(editor.Text)' in WINDOW),
        check("Tile launch suppressed while renaming",
              'if (_renamingSetup != null) { e.Handled = true; return; }' in WINDOW),
        check("Snapshot Z-order is optional for older setups",
              'public int? SnapshotZOrder { get; set; }' in SETTINGS),
        check("Captured windows have front-to-back rank",
              'SnapshotZOrder = zOrder++' in CAPTURE
              and 'SnapshotZOrder = w.SnapshotZOrder' in CAPTURE),
        check("Launcher restores captured Z-order independently of action list order",
              '.OrderBy(w => w.SnapshotZOrder.HasValue ? 0 : 1)' in LAUNCHER
              and '.ThenBy(w => w.SnapshotZOrder ?? w.ActionIndex)' in LAUNCHER),
        check("Unrelated windows are minimized only after setup windows are resolved",
              'if (used.Count > 0) Log("Minimized " + MinimizeUnrelatedWindows(used)' in LAUNCHER),
        check("Setup's own windows are never minimized",
              'setupWindows.Contains(window.Handle)' in LAUNCHER),
        check("Restored front window is activated",
              'WindowTools.Activate(orderedForeground[0].Handle)' in LAUNCHER),
    ]
    return 0 if all(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
