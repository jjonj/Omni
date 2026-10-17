# Builds the standalone OmniZones as one self-contained exe (no .NET install needed) and zips it for sharing.
# Output: OmniZones/dist/OmniZones.exe and OmniZones/dist/OmniZones.zip
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$dist = Join-Path $root 'dist'
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
dotnet publish (Join-Path $root 'src/OmniZones.App/OmniZones.App.csproj') -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true `
    -p:DebugType=none -o $dist
if ($LASTEXITCODE -ne 0) { throw 'publish failed' }
Compress-Archive -Path (Join-Path $dist 'OmniZones.exe') -DestinationPath (Join-Path $dist 'OmniZones.zip') -Force
Get-ChildItem $dist | Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } }
