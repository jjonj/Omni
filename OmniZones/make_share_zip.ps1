# Builds a zip you can send to a friend: the standalone OmniZones.exe (self-contained, no .NET or installer needed) plus a short readme.
# Output: OmniZones/dist/OmniZones-share.zip
# Builds in a temp folder, so it works even while your own OmniZones.exe is running from dist.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$dist = Join-Path $root 'dist'
$stage = Join-Path ([IO.Path]::GetTempPath()) ("omnizones-share-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $stage, $dist -Force | Out-Null
try {
    $build = Join-Path $stage 'build'
    dotnet publish (Join-Path $root 'src/OmniZones.App/OmniZones.App.csproj') -c Release -r win-x64 --self-contained true `
        -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true `
        -p:DebugType=none -o $build
    if ($LASTEXITCODE -ne 0) { throw 'publish failed' }

    $pack = Join-Path $stage 'OmniZones'
    New-Item -ItemType Directory $pack | Out-Null
    Copy-Item (Join-Path $build 'OmniZones.exe') $pack
    @'
OmniZones - drag windows into zones

1. Unzip anywhere and double-click OmniZones.exe. Nothing to install.
2. Look for its icon in the system tray (click the ^ arrow if hidden). Right-click it for Settings.
3. Drag any window by its title bar: zones appear, drop the window on one to snap it.

Handy settings (tray icon > Settings):
 - Simple mode: just plain zones, nothing else.
 - Require holding Shift to snap.
 - Start OmniZones with Windows.

Windows may show a SmartScreen warning the first time because the app is not code-signed:
click "More info" then "Run anyway".
To remove it: exit from the tray icon, turn off "Start with Windows" in Settings, delete the exe,
and delete %APPDATA%\OmniZones if you like.
'@ | Set-Content (Join-Path $pack 'README.txt') -Encoding UTF8

    $zip = Join-Path $dist 'OmniZones-share.zip'
    if (Test-Path $zip) { Remove-Item $zip -Force }
    Compress-Archive -Path (Join-Path $pack '*') -DestinationPath $zip
    Get-Item $zip | Select-Object FullName, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } }
}
finally {
    Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}
