# Builds OmniSleep.exe and OmniShutdown.exe straight into the OmniPower folder, so they're easy to find and pin.
# Framework-dependent single-file exes (uses the installed .NET 9 runtime, so they stay small).
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

foreach ($project in 'OmniSleep', 'OmniShutdown') {
    # Remove the previous build of this exe so a failed publish can't leave a stale file behind.
    Remove-Item (Join-Path $root "$project.exe") -ErrorAction SilentlyContinue
    dotnet publish (Join-Path $root "src/$project/$project.csproj") -c Release -r win-x64 --self-contained false `
        -p:PublishSingleFile=true -p:DebugType=none -o $root
    if ($LASTEXITCODE -ne 0) { throw "publish failed: $project" }
}

# dist/ was the old output folder; remove it so there's only one set of exes.
Remove-Item (Join-Path $root 'dist') -Recurse -Force -ErrorAction SilentlyContinue

Get-ChildItem $root -Filter 'Omni*.exe' | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 2) } }
