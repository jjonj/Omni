# OmniPower

Sleep and shutdown launchers that replace `sleep.bat` and `shutdown.bat` from `B:\GDrive\Tools\05 Automation`.

## Layout

- `src/OmniPower.Core` - shared logic: TV-off step, sleep loop, shutdown, Explorer restart, wake-input detection. No Omni Hub dependency.
- `src/OmniSleep` - `OmniSleep.exe`. Windowless, so it can be pinned.
- `src/OmniShutdown` - `OmniShutdown.exe`. Windowless, so it can be pinned.
- `tests/OmniPower.Core.Tests` - unit tests for the wake decision and the raw-input parser.
- `publish.ps1` - builds both exes into `dist/`.

## Build and pin

```powershell
powershell -File Omni.Other\OmniPower\publish.ps1
```

Pin these two files to the taskbar: `OmniPower\dist\OmniSleep.exe` and `OmniPower\dist\OmniShutdown.exe`.
Once they work, unpin the old `sleep.bat`/`shutdown.bat` shortcuts.

## What each one does

**OmniShutdown.exe**: starts `tv_off.bat`, shows a shared cancelable 10-second countdown, then runs `shutdown.exe /f /s /t 0` only if not canceled.

**OmniSleep.exe**: starts `tv_off.bat`, shows the same cancelable 10-second countdown, then sleeps the PC only if not canceled. The TV control begins while the countdown is visible. After resume:

| Wake input (within 10 s) | Result |
| :--- | :--- |
| Mouse click or key press | Stays awake, restarts Explorer |
| Mouse movement only | Goes back to sleep |
| Nothing at all (WOL) | Stays awake, restarts Explorer |

Mouse wheel is logged but doesn't count as either input or movement.

Settings: the TV script defaults to `B:\GDrive\Tools\05 Automation\TVActive3\tv_off.bat` and is skipped if it doesn't exist. Running from a terminal accepts `--no-tv` or `--tv-script "<path>"`. Pinned shortcuts run with no arguments, so to change the defaults edit `PowerOptions.DefaultTvOffScript`.

Logs: `%LOCALAPPDATA%\OmniPower\logs\sleep.log`, `shutdown.log`, `input-test.log`.

## Wake detection

The old guard polled `GetAsyncKeyState`. That often never saw the input that woke the PC, so a click could be missed and the PC went back to sleep.

The new guard uses **Raw Input** (a hidden message-only window with `RIDEV_INPUTSINK`). It queues input that arrives during resume. It also keeps `GetAsyncKeyState` polling as a fallback. Each log line names the source (`RawInput`, `KeyStatePoll`, or `CursorPosition`), so the logs show which detector saw each wake.

## Testing wake detection

### Step 1: Input check (no sleep, about 30 s)

In PowerShell (do **not** double-click, since that would sleep the PC):

```powershell
& "D:\SSDProjects\Omni\Omni.Other\OmniPower\dist\OmniSleep.exe" --input-test
```

For 30 seconds, move the mouse, left-click, right-click, scroll, and press a few keys. The console prints each click and key, and the summary should end with `PASS: raw input saw clicks/keys.`

Send me the summary block, or the `input-test.log` file if the console is blank.

### Step 2: Real sleep cycles

Start each cycle by clicking the pinned `OmniSleep.exe`. Wait until the screen is off, then wake the PC as the case says:

| # | Wake with | Expected in `sleep.log` | Expected on screen |
| :- | :--- | :--- | :--- |
| A | Mouse click | `Input: left mouse button down via RawInput` and `remaining awake` | Stays on, Explorer restarts ~10 s later |
| B | Keyboard key | `Input: keyboard key VK 0x.. down via RawInput` and `remaining awake` | Stays on |
| C | Mouse movement only, no click for 10 s | `returning to sleep` | Goes back to sleep |
| D | Move the mouse, then click within 10 s | `Input: left mouse button down` and `remaining awake` | Stays on |

Run cycles Aâ€“D in order, and note any where the result differed from expected. Only case C should return to sleep.

After the cycles, send me `%LOCALAPPDATA%\OmniPower\logs\sleep.log`. Each cycle includes the `Windows last-wake report` from `powercfg`, which shows what woke the PC.

### What I'll look for in the logs

- If the click/key shows up with `RawInput` in the first few hundred milliseconds after resume, the fix works.
- If the log says `no input events` even though you clicked, Windows swallowed the wake input before any app saw it. In that case the guard can't tell a wake click from a bump, and we'd need to change the rule. Tell me which cycle that happened in.
- If only `KeyStatePoll` sees the input, Raw Input isn't receiving it and the fallback is doing the work.

## Tests

```powershell
dotnet test Omni.Other\OmniPower\tests\OmniPower.Core.Tests\OmniPower.Core.Tests.csproj
```

