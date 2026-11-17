# OmniZones · OmniInstaller prebuilt Release route

The repository-root `omniinstaller.json` in `jjonj/Omni` publishes `dev.jjonj.omnizones`. OmniInstaller reads it from the GitHub Release tag's source snapshot and expects exactly one `OmniZones-win-x64.zip` asset in that stable Release.

To prepare the Windows x64 payload, run from the repository checkout:

```powershell
.\OmniZones\scripts\omniinstaller\Build-ReleaseAsset.ps1 -OutputDirectory "E:\_AgentScratch\Unassigned\OmniZonesRelease"
```

The wrapper uses the standalone WPF project's existing `dotnet publish` policy (self-contained, single-file, no .NET runtime on destination), but stages the result in a temporary directory rather than deleting the developer's `OmniZones/dist` or stopping the live tray process. Upload the **single ZIP** output as `OmniZones-win-x64.zip` on a new stable `jjonj/Omni` GitHub Release whose tagged commit contains the manifest/scripts. Do not confuse the preexisting local `dist/OmniZones.zip` with a published GitHub asset. No Release has been published by this task. Release asset publication is an explicit maintainer operation, not an automatic user app update.

A release install extracts the verified ZIP, copies only `OmniZones.exe` plus two ownership/version marker files into the chosen app destination, and records `managedFiles` for conservative uninstall. The default is `%LOCALAPPDATA%\OmniInstaller\Apps\dev.jjonj.omnizones`; a custom destination works, but an existing unowned `OmniZones.exe` causes a refusal. Existing tray/HUB runtime and shared `%APPDATA%\OmniZones\settings.json` are not touched. No action auto-launches or terminates a running OmniZones instance.

For a local offline recipe proof, extract an existing `OmniZones.zip` into a temporary package directory, set `OMNIINSTALLER_PACKAGE_DIR`, `OMNIINSTALLER_APP_ID`, `OMNIINSTALLER_INSTALL_DIR` and a local test `OMNIINSTALLER_SOURCE_VERSION`, then invoke `OmniZones/scripts/omniinstaller/Invoke-Recipe.ps1 install`; afterwards call `detectVersion`. This proves the package install script without claiming that a GitHub Release already exists.
