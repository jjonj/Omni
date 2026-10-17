# OmniSync Android Safe Mode and Crash Recovery

Status: implementation design only. This document does not change runtime behavior.

## 1. Objective

OmniSync should be able to detect that its previous runs crashed, start a minimal recovery environment that avoids the failing normal code path, collect useful diagnostics, and install a corrected APK from the Hub without requiring ADB.

The recovery path must remain usable when normal application initialization, Compose UI, SignalR, WebView rendering, foreground services, notification layouts, configuration parsing, or restored session state are broken.

The intended result is:

1. A normal launch behaves as it does today.
2. One unexpected crash produces a durable local report and permits one automatic normal retry.
3. Repeated crashes from the same APK enter Safe Mode instead of restarting indefinitely.
4. Safe Mode can explain the detected failure, upload diagnostics, check the Hub for an APK, validate it, and hand it to Android's package installer.
5. Deployment tools can confirm that an update remained alive after installation instead of treating installation alone as success.

## 2. Motivating failure

On 2026-09-04, the Samsung device repeatedly terminated OmniSync with:

```text
android.app.RemoteServiceException$BadForegroundServiceNotificationException
Couldn't inflate contentViews
android.content.res.Resources$NotFoundException: Resource ID #0x7f0a000f
```

The resource was `R.layout.notification_layout`, used by the foreground service's custom `RemoteViews`. The exception was raised asynchronously by Android after `startForeground()` returned. It therefore could not be caught around notification construction. The application crashed three times within seconds.

ADB allowed the crash buffer to be read and a repaired APK to be installed because ADB and Android's package manager operate independently of the OmniSync process. Safe Mode should provide an application-owned recovery route for this class of failure.

This incident also exposes a gap in the current `CrashReportingTree`: it writes reports only for `Throwable` instances explicitly passed through Timber. It is not a process-wide uncaught exception handler and did not capture the fatal `RemoteServiceException`.

## 3. Recovery guarantees and limitations

### 3.1 Required guarantees

Safe Mode must:

- initialize before `MainViewModel`, `SignalRClient`, `ClipboardWatcher`, foreground services, alarms, overlays, Compose, or WebViews;
- use only small Android platform primitives for its initial screen;
- store crash-loop state in private internal storage using atomic updates;
- avoid network activity inside an uncaught exception handler;
- work while the Hub is unavailable and retry uploads later;
- authenticate downloads and reports with the existing Hub API key;
- validate APK hash, package name, version, and signer before installation;
- preserve user configuration unless the user explicitly chooses a targeted reset;
- call Android's original uncaught exception handler after recording a crash so normal system crash handling remains intact;
- avoid a recovery loop if Safe Mode itself fails.

### 3.2 Limits that must be explicit

Safe Mode can only help if the installed APK already contains the recovery implementation. It cannot retroactively rescue an older build that predates it.

It cannot guarantee recovery from failures that occur before any application code can execute, including an unreadable APK, missing native library required by the runtime, invalid manifest, signature rejection, or catastrophic resource-table corruption. ADB or manual package installation remains the final fallback.

Android may require user confirmation before installing an APK. Safe Mode can prepare and validate the update, open the system installer, and explain the required action, but it must not claim silent installation is always possible.

Installing a lower `versionCode` is normally rejected. A stored old APK is therefore not a dependable rollback mechanism unless Android's managed rollback facilities are deliberately integrated. The initial design should prefer installing a forward-fixed build.

## 4. Current architecture constraints

The existing startup path must be changed before Safe Mode can be reliable:

- `OmniSyncApplication.onCreate()` currently constructs `MainViewModel`, `AndroidUpdateManager`, `SignalRClient`, and `ClipboardWatcher`, then initializes alarm state.
- `MainActivity.onCreate()` immediately starts SignalR, optionally starts the overlay, starts `ForegroundService`, and then creates the Compose hierarchy.
- `AndroidUpdateReceiver` expects a fully initialized `OmniSyncApplication` and calls its updater and SignalR client.
- `CrashReportingTree` observes Timber calls but not all uncaught process exceptions.
- `AndroidUpdateManager` already contains useful metadata download, SHA-256, package-name, version, signer, and `PackageInstaller` validation logic.
- The Hub already exposes authenticated metadata and APK endpoints under `/api/android-update`.

The recovery implementation should reuse the updater's validation behavior, but it must not require the normal SignalR runtime or normal Application object graph.

## 5. Proposed architecture

### 5.1 Components

Add the following narrowly scoped components:

| Component | Responsibility | May depend on |
| --- | --- | --- |
| `RecoveryStateStore` | Atomically persist launch markers, failure counters, health state, and recovery override | Android context, private files or SharedPreferences |
| `CrashRecorder` | Install the uncaught handler and write bounded crash reports | Android/JDK APIs only |
| `ExitReasonReader` | Read `ApplicationExitInfo` on API 30+ | Android framework only |
| `CrashLoopDetector` | Decide normal launch versus Safe Mode | `RecoveryStateStore`, `ExitReasonReader` |
| `NormalRuntime` | Lazily construct the current normal object graph | Existing application classes |
| `BootstrapActivity` | Launcher entry point that routes to normal or recovery UI | Detector only |
| `RecoveryActivity` | Minimal programmatic Safe Mode screen | Recovery-only components |
| `RecoveryUpdateClient` | Direct authenticated metadata/download/validation/install path | `HttpURLConnection`, package manager, package installer |
| `CrashReportUploader` | Upload saved reports when explicitly requested or when recovery networking is available | Direct HTTP client |
| `RecoveryInstallReceiver` | Persist installer status without requiring normal runtime initialization | Recovery state store only |

### 5.2 Process isolation

Declare `RecoveryActivity` and its installer-status receiver in a `:recovery` process. Android still instantiates the configured `Application` class in that process, so `OmniSyncApplication` must check the current process name before initializing anything else.

Conceptually:

```kotlin
override fun onCreate() {
    super.onCreate()
    RecoveryBootstrap.installMinimalCrashHandling(this)

    if (ProcessRole.current(this) == ProcessRole.RECOVERY) {
        return
    }

    // NormalRuntime is initialized only after BootstrapActivity approves it.
}
```

The process check must use `Application.getProcessName()` on supported Android versions, with a compatible fallback if needed. Do not infer the process from mutable state.

Process isolation protects recovery from static state and background work in the normal process. It does not protect recovery from code placed unconditionally in `OmniSyncApplication`, which is why the Application must become minimal.

### 5.3 Launcher routing

Make `BootstrapActivity` the exported launcher Activity. It should have no Compose content and perform only this routing:

```text
BootstrapActivity starts
  -> load durable recovery state
  -> reconcile the previous launch with ApplicationExitInfo
  -> if crash-loop threshold reached or Safe Mode forced:
       launch RecoveryActivity in :recovery and finish
     else:
       mark a new normal launch as STARTING
       initialize NormalRuntime
       launch MainActivity and finish
```

`MainActivity` should cease being the launcher and should only be reachable after bootstrap approval. Explicit intents such as notification navigation must route through bootstrap or perform the same recovery check.

If `BootstrapActivity` itself crashes twice for the same version, a tiny manifest-declared recovery notification or a direct recovery launch attempt should be used. The implementation must cap attempts so it does not create another loop.

## 6. Crash-loop state machine

### 6.1 States

Use these durable states:

- `HEALTHY`: the last normal run passed all health checks.
- `STARTING`: normal startup began but has not yet been declared healthy.
- `RUNNING`: the first frame was rendered and mandatory service startup completed, but the stability window has not elapsed.
- `CRASH_SUSPECTED`: a previous `STARTING` or `RUNNING` marker was found without a healthy shutdown or matching benign exit reason.
- `SAFE_MODE`: automatic normal initialization is disabled.
- `UPDATE_PENDING`: a recovery update was handed to `PackageInstaller`.
- `RECOVERY_FAILED`: Safe Mode itself failed; avoid automatic relaunch loops and leave a basic system notification if possible.

### 6.2 Recommended thresholds

Recommended defaults:

- Enter Safe Mode after two suspected crashes from the same APK hash within five minutes.
- Allow one automatic normal retry after the first crash.
- Reset the consecutive count after a healthy run lasting at least 30 seconds.
- Do not count user force-stop, normal package replacement, device shutdown, reboot, or a deliberate app exit as a crash when Android provides a trustworthy exit reason.
- Keep failures separate per APK SHA-256. Installing a different APK gets one normal recovery attempt even if the previous APK was crash-looping.
- Retain the previous version's report after an update so the fixed build can upload it.

The APK hash is important because the project currently uses `versionCode = 1` and `versionName = 1.0` for development builds. Version code alone cannot distinguish two locally built APKs.

### 6.3 Health milestones

Connectivity must not be required for health; being offline is not a crash.

Normal startup should report milestones:

1. `APPLICATION_READY`: minimal Application initialization completed.
2. `RUNTIME_READY`: normal dependencies were constructed.
3. `FIRST_FRAME`: `MainActivity` rendered its first frame.
4. `FOREGROUND_SERVICE_READY`: the foreground service called `startForeground()` and remained alive long enough for asynchronous notification validation.
5. `STABLE`: the process remained alive for 30 seconds after the first frame.

Only `STABLE` clears the crash counter and sets `HEALTHY`. This delay would have caught the Samsung notification failure, which occurred shortly after the service posted its notification.

## 7. Durable state format

Store recovery state in private internal storage, preferably under `noBackupFilesDir`, so device backup does not restore stale crash-loop state onto another installation. Use an atomic temporary-file-and-rename write, or synchronous `SharedPreferences.commit()` for the small marker.

Suggested schema:

```json
{
  "schemaVersion": 1,
  "apkSha256": "...",
  "versionCode": 2,
  "versionName": "1.1",
  "launchId": "random-uuid",
  "launchState": "STARTING",
  "startedAtUtc": "2026-09-04T16:13:20Z",
  "lastMilestone": "RUNTIME_READY",
  "lastMilestoneAtUtc": "2026-09-04T16:13:21Z",
  "consecutiveFailures": 1,
  "failureWindowStartedAtUtc": "2026-09-04T16:13:20Z",
  "safeModeForced": false,
  "lastHealthyAtUtc": "2026-09-03T20:10:00Z",
  "pendingInstallSessionId": null,
  "pendingDeploymentRequestId": null
}
```

Do not store the API key, user clipboard, terminal contents, AI prompts, or arbitrary configuration in crash-loop state.

## 8. Crash recording

### 8.1 Global uncaught handler

Install a default uncaught exception handler at the very beginning of `OmniSyncApplication.onCreate()` and in recovery-process startup.

The handler must:

- capture timestamp, process, thread, exception class/message/stack, app version, APK hash, Android version, device manufacturer/model, launch ID, and latest milestone;
- copy a bounded in-memory ring buffer of recent OmniSync log events;
- write to a temporary file, flush it, and atomically rename it;
- set the launch state to `CRASH_SUSPECTED` using a synchronous durable write;
- never initialize SignalR, make network requests, parse full app configuration, render UI, or call Timber;
- guard against re-entry with an atomic boolean;
- impose strict size and time limits;
- invoke the previously installed handler after the local write attempt.

Calling Timber from the crash writer risks recursive failure because `CrashReportingTree` itself writes files for logged throwables. The crash recorder should use direct file I/O and, at most, `android.util.Log` without a throwable.

### 8.2 Android exit history

On API 30 and newer, query `ActivityManager.getHistoricalProcessExitReasons()` before starting normal runtime. Record:

- exit reason and status;
- timestamp;
- process importance;
- description;
- available ANR trace input stream;
- whether the exit matches the launch ID/time window already recorded by the uncaught handler.

Relevant crash-like reasons include Java crash, native crash, ANR, excessive resource use, and dependency death when appropriate. User-requested exits, package updates, permission changes, and device reboot must be classified separately.

`ApplicationExitInfo` is necessary because the process cannot always write its own report. It also distinguishes a genuine crash from the normal process termination caused by installing an APK.

### 8.3 Report files and retention

Store reports under:

```text
files/crash_reports/pending/
files/crash_reports/sent/
```

Use JSON metadata plus an optional bounded text trace. Suggested retention:

- maximum 10 pending reports;
- maximum 20 sent summaries;
- maximum 512 KiB per report;
- maximum 5 MiB total;
- delete oldest sent reports first;
- deduplicate by APK hash, exception signature, and a short time bucket.

## 9. Minimal Recovery UI

`RecoveryActivity` should use programmatically created platform `LinearLayout`, `TextView`, `Button`, `ProgressBar`, and `ScrollView` instances. Avoid Compose, fragments, WebView, custom fonts, custom drawables, and custom notification layouts.

Recommended screen contents:

```text
OmniSync Safe Mode

The previous version failed 3 times during FOREGROUND_SERVICE_READY.
Last failure: BadForegroundServiceNotificationException
Installed build: 1.0 / <APK hash prefix>

[Check Hub for repair]
[Install downloaded repair]
[Upload diagnostics]
[View technical details]
[Reset transient session state]
[Try normal startup once]
```

Behavior:

- Show a concise explanation first, with stack traces behind a details button.
- Disable normal background services while Safe Mode is open.
- Make network and installer progress explicit.
- Never automatically erase user data.
- Require confirmation before resetting state.
- Separate transient-state reset from full configuration reset.
- Allow copying/exporting the report if the Hub cannot be reached.
- Provide a manual “always enter Safe Mode next launch” toggle for development.

The first Safe Mode screen should remain functional even if app resources changed. Use stable system styling where practical and do not reuse normal app themes that execute custom initialization.

## 10. Recovery updater

### 10.1 Independence from normal runtime

The current `AndroidUpdateManager` has suitable validation logic but is created inside the normal Application graph and reports deployment status through callbacks that commonly reach SignalR. Extract the reusable validation and package-install primitives into code that has no SignalR, view model, service, or Compose dependency.

`RecoveryUpdateClient` should load only the minimum recovery configuration:

- last known Hub base URL;
- API key;
- optional fallback LAN/WAN Hub addresses;
- pending deployment request ID, if one exists.

The primary route should be direct authenticated HTTP:

- `GET /api/android-update` for metadata;
- `GET /api/android-update/apk` for the APK;
- `X-Omni-Api-Key` for authentication.

This path already exists on the Hub and does not require the SignalR connection that normal OmniSync creates.

### 10.2 APK validation

Before opening `PackageInstaller`, verify:

1. Content length is bounded and matches metadata when supplied.
2. SHA-256 matches Hub metadata.
3. Archive package name equals `com.omni.sync`.
4. Archive signer intersects the installed signer set.
5. `versionCode` is acceptable.
6. The downloaded file is readable as an APK.
7. The APK hash differs from the currently failing APK.

Never allow Safe Mode to install an arbitrary URL supplied by an external Intent. Hub locations must come from trusted local configuration or an authenticated, explicit user entry.

### 10.3 Installer callback safety

The existing `AndroidUpdateReceiver` accesses `application.androidUpdateManager` and `application.signalRClient`. That is unsafe when normal initialization is intentionally skipped.

Replace or split it so the installer callback:

- persists the status through `RecoveryStateStore` first;
- does not assume `NormalRuntime` exists;
- reports through direct HTTP only when safe;
- posts a standard Android notification after installation if the system does not reopen the app;
- never uses custom `RemoteViews`;
- marks package replacement as an expected exit, not a crash.

Add an `ACTION_MY_PACKAGE_REPLACED` receiver that performs only minimal recovery bookkeeping and posts a standard “Update installed — open OmniSync” notification when background activity launch restrictions prevent automatic reopening.

### 10.4 After update

On the first launch of a new APK hash:

- archive the previous failure record;
- allow one normal startup attempt;
- retain Safe Mode as an immediate fallback;
- start a 30-second health observation;
- upload the previous crash only after the user has enabled diagnostics upload or explicitly requests it;
- report `healthy` to the Hub only after reaching `STABLE`.

## 11. Hub additions

The existing Hub deployment coordinator treats statuses such as `installer_started` and `installed` as terminal deployment progress. Installation is not proof that the new process is healthy.

Add authenticated endpoints or equivalent Hub methods for:

### 11.1 Crash reports

```text
POST /api/android-recovery/crash-report
GET  /api/android-recovery/crash-report/latest
```

The POST body should include the bounded report metadata and trace. The Hub should:

- validate the API key;
- impose a small request-size limit;
- write reports outside the APK publication folder;
- sanitize filenames;
- avoid logging secrets or full report bodies into general logs;
- return a stable report ID;
- deduplicate repeated identical crash loops.

### 11.2 Health acknowledgements

```text
POST /api/android-recovery/health
```

Suggested payload:

```json
{
  "deploymentRequestId": "...",
  "apkSha256": "...",
  "versionCode": 2,
  "launchId": "...",
  "state": "stable",
  "uptimeSeconds": 30
}
```

Deployment status should distinguish:

- `installer_started`;
- `installed_unverified`;
- `launched`;
- `healthy`;
- `safe_mode`;
- `crash_detected`;
- `health_timeout`.

The Hub should not announce a successful deployment until `healthy`, unless it clearly labels installation as unverified.

## 12. Deployment-tool health checks

Extend `build_and_deploy.py` so ADB deployments verify runtime health automatically:

1. Clear only the package-relevant test log window or record a timestamp marker.
2. Install with `adb install -r`.
3. Launch the package.
4. Wait for the process and `MainActivity`.
5. Observe it for at least 30 seconds.
6. Check `pidof com.omni.sync` periodically.
7. Read the crash buffer and filter for `Process: com.omni.sync` after the marker.
8. Verify a `STABLE` health log or stored state.
9. Exit nonzero and save a focused report if the process crashes.

Do not rely solely on the exit code from `adb install`. The 2026-09-04 incident installed successfully but the launched process immediately failed.

For Hub-driven deployment, poll beyond `installer_started` and wait for the new APK's direct health acknowledgement or a documented timeout.

## 13. State reset policy

Safe Mode should offer targeted recovery operations in increasing order of destructiveness:

1. Ignore restored navigation/session selection for one launch.
2. Clear transient AI/terminal rendering state.
3. Disable overlay and optional background services for one launch.
4. Reset notification customization.
5. Reset network endpoints while preserving credentials separately.
6. Export and then reset all app configuration only after explicit confirmation.

Each reset must create a small backup first and state exactly what it changes. Never automatically delete files, user bookmarks, macros, credentials, or cached documents merely because the app crashed.

## 14. Logging and observability

Use consistent tags and a launch ID through the entire startup chain:

```text
OmniRecovery/Bootstrap
OmniRecovery/CrashRecorder
OmniRecovery/ExitReason
OmniRecovery/State
OmniRecovery/Updater
OmniRecovery/Uploader
OmniRecovery/Health
```

Every milestone log should include:

- launch ID;
- process role;
- APK hash prefix;
- previous state;
- next state;
- elapsed startup time.

Do not log the API key, full authorization headers, clipboard content, AI prompts, terminal output, passwords, or full private filesystem paths sent by the user.

Maintain an in-memory ring buffer of approximately 200 structured events. Persist it only when a crash is detected or the user requests diagnostics.

## 15. Security and privacy

- Crash upload should be opt-in initially, or controlled by an explicit diagnostics setting.
- Safe Mode must show what will be uploaded.
- Redact API keys, bearer tokens, URLs containing credentials, email addresses where feasible, and known sensitive configuration fields.
- Authenticate every update and report endpoint.
- Keep APK signer verification mandatory even when SHA-256 matches.
- Reject redirects to an unexpected host during recovery downloads unless explicitly allowed.
- Use HTTPS for non-LAN Hub endpoints where available.
- Rate-limit crash upload on both app and Hub.
- Keep recovery Activities and receivers non-exported except the launcher entry point required by Android.
- Validate every external Intent before using it to affect recovery state.

## 16. Implementation plan

### Phase 1: Minimal bootstrap and durable detection

Files to add or change:

- `OmniSyncApplication.kt`: install minimal crash handling and stop eager normal initialization.
- `NormalRuntime.kt`: own lazy construction of the current normal dependencies.
- `RecoveryStateStore.kt`: durable state and milestones.
- `CrashRecorder.kt`: global handler and bounded reports.
- `ExitReasonReader.kt`: API 30+ exit classification.
- `CrashLoopDetector.kt`: pure decision logic.
- `BootstrapActivity.kt`: launcher routing.
- `MainActivity.kt`: consume `NormalRuntime` only after approval.
- `AndroidManifest.xml`: launcher change and recovery process declarations.

Verification:

- Unit-test all state transitions and threshold timing.
- Simulate an unclean `STARTING` marker and confirm first retry is normal.
- Simulate the second matching failure and confirm Safe Mode routing.
- Confirm force-stop, reboot, and package replacement are not misclassified.

### Phase 2: Minimal Safe Mode UI

Files to add:

- `RecoveryActivity.kt` with platform Views only.
- `RecoveryReportFormatter.kt` for concise and detailed views.

Verification:

- Deliberately throw during `NormalRuntime` initialization and verify Safe Mode remains usable.
- Deliberately break Compose initialization and verify recovery does not load Compose classes.
- Verify recovery process does not start SignalR, clipboard watcher, alarms, overlays, or foreground service.

### Phase 3: Independent recovery updater

Files to add or refactor:

- `RecoveryUpdateClient.kt`.
- Shared APK validation helper extracted from `AndroidUpdateManager.kt`.
- `RecoveryInstallReceiver.kt` or a safe refactor of `AndroidUpdateReceiver.kt`.
- Minimal trusted recovery configuration reader.

Verification:

- Reject wrong hash, package, signer, and older version.
- Complete an update while normal runtime is intentionally broken.
- Confirm install status survives process replacement.
- Confirm the new build gets one normal launch and falls back to Safe Mode if it fails.

### Phase 4: Hub reports and health protocol

Files to add or change:

- Hub recovery controller and report storage service.
- `AndroidUpdateDeploymentCoordinator.cs` for post-install health states.
- Android direct crash uploader and health reporter.

Verification:

- Upload a redacted report without SignalR.
- Deduplicate a repeated crash loop.
- Confirm the Hub reports `healthy` only after the stability window.
- Confirm `safe_mode` and `health_timeout` are visible to deployment tooling.

### Phase 5: Automated update-cycle testing

Add a repeatable script that:

- starts the foreground service;
- installs a changed-resource APK over the running version;
- launches the replacement;
- checks process survival and crash buffer;
- exercises normal and intentionally crashing builds;
- verifies Safe Mode update recovery without manual interaction where Android permissions allow it.

Run the test on both an emulator and the Samsung device because the motivating `RemoteViews` failure was device/SystemUI-specific.

## 17. Test matrix

| Scenario | Expected result |
| --- | --- |
| Clean normal launch | Normal UI; state reaches `STABLE`; counter cleared |
| One Java crash during startup | Report saved; one normal retry allowed |
| Second Java crash within five minutes | Recovery UI launches; normal services remain stopped |
| Native crash | Detected from `ApplicationExitInfo`; Safe Mode threshold applies |
| ANR during startup | Exit trace recorded; Safe Mode threshold applies |
| User force-stop | Not counted as crash |
| APK replacement kills process | Marked expected; not counted as crash |
| Hub offline | Recovery UI stays usable; report remains pending |
| SignalR code broken | Direct recovery update still works |
| Compose code broken | Platform recovery UI still works |
| Foreground notification rejected | Next launch enters Safe Mode after threshold |
| Recovery UI crashes | Capped retry; `RECOVERY_FAILED` persisted |
| Wrong APK signer/hash/package | Installation blocked with clear error |
| Repaired APK installed | Previous report retained; one normal launch attempted |
| Repaired APK also crashes | Safe Mode returns for the new APK hash |
| App stable for 30 seconds | Hub receives `healthy` acknowledgement |

## 18. Definition of done

Safe Mode is complete when all of the following are demonstrated without ADB intervention during the recovery itself:

1. An intentionally broken normal startup crashes twice.
2. The next launch opens the minimal Safe Mode screen.
3. No normal services or Compose code initialize in Safe Mode.
4. The previous failure and exit reason are visible locally.
5. Safe Mode downloads and validates a repaired APK from the Hub.
6. Android installs the repaired APK.
7. The repaired app reaches the 30-second `STABLE` milestone.
8. The Hub records the deployment as `healthy` rather than merely installed.
9. The crash report is available on the Hub with secrets redacted.
10. The same flow passes on the emulator and the Samsung device.

## 19. Recommended first implementation slice

The smallest useful slice is Phases 1 and 2: minimal Application bootstrap, durable crash-loop detection, global crash recording, `ApplicationExitInfo`, and a recovery Activity that can display/export diagnostics and try normal startup once.

The independent updater should follow immediately. Building updater UI before isolating Application startup would create a Safe Mode that can still die in the normal object graph, while implementing only crash logging would improve diagnosis but would not let the device recover without ADB.

