param([Parameter(Mandatory)][ValidateSet('install','detectVersion')][string]$Action)
$ErrorActionPreference = 'Stop'
$appId = 'dev.jjonj.omnizones'
if ($env:OMNIINSTALLER_APP_ID -ne $appId) { throw 'Selected app and repository recipe differ.' }
$destination = $env:OMNIINSTALLER_INSTALL_DIR
if ($Action -eq 'detectVersion') {
    $exe = Join-Path $destination 'OmniZones.exe'
    $idFile = Join-Path $destination '.omniinstaller-appid'
    $versionFile = Join-Path $destination '.omniinstaller-version'
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf) -or
        -not (Test-Path -LiteralPath $idFile -PathType Leaf) -or
        -not (Test-Path -LiteralPath $versionFile -PathType Leaf)) { exit 10 }
    if ((Get-Content -LiteralPath $idFile -Raw).Trim() -ne $appId) { exit 10 }
    $installed = (Get-Content -LiteralPath $versionFile -Raw).Trim()
    if (-not $installed) { exit 10 }
    Write-Output $installed
    exit 0
}
$packageDir = $env:OMNIINSTALLER_PACKAGE_DIR
if (-not $packageDir -or -not (Test-Path -LiteralPath $packageDir -PathType Container)) {
    throw 'OmniZones requires a verified, extracted GitHub Release ZIP.'
}
$exePath = Join-Path $packageDir 'OmniZones.exe'
& (Join-Path $PSScriptRoot 'Install-ManagedBinary.ps1') -SourceExe $exePath -ExeName 'OmniZones.exe'
if (-not $?) { exit 1 }
