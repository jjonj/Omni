param(
    [Parameter(Mandatory)][string]$SourceExe,
    [Parameter(Mandatory)][string]$ExeName
)
$ErrorActionPreference = 'Stop'

# Replace only files belonging to this recipe, never a whole application directory.
$dest = $env:OMNIINSTALLER_INSTALL_DIR
$appId = $env:OMNIINSTALLER_APP_ID
$version = $env:OMNIINSTALLER_SOURCE_VERSION
if (-not $dest -or -not $appId -or -not $version) { throw 'OmniInstaller install context is incomplete.' }
if (-not (Test-Path -LiteralPath $SourceExe -PathType Leaf)) { throw "Missing built/package executable: $SourceExe" }
$dest = [IO.Path]::GetFullPath($dest).TrimEnd('\')
if ($dest -eq [IO.Path]::GetPathRoot($dest).TrimEnd('\')) { throw 'Refusing to install into a drive root.' }
$exePath = Join-Path $dest $ExeName
$idPath = Join-Path $dest '.omniinstaller-appid'
$versionPath = Join-Path $dest '.omniinstaller-version'
if (Test-Path -LiteralPath $exePath) {
    if (-not (Test-Path -LiteralPath $idPath -PathType Leaf) -or
        (Get-Content -LiteralPath $idPath -Raw).Trim() -ne $appId) {
        throw "The destination already contains an unowned $ExeName; select another folder."
    }
}
if (Test-Path -LiteralPath $idPath -PathType Leaf) {
    if ((Get-Content -LiteralPath $idPath -Raw).Trim() -ne $appId) {
        throw "The destination is owned by a different OmniInstaller app."
    }
}
New-Item -ItemType Directory -Path $dest -Force | Out-Null
$token = [guid]::NewGuid().ToString('N')
$stage = Join-Path $dest ('.omni-stage-' + $token)
$backup = Join-Path $dest ('.omni-backup-' + $token)
$files = @($ExeName, '.omniinstaller-appid', '.omniinstaller-version')
$movedOld = [System.Collections.Generic.List[string]]::new()
$movedNew = [System.Collections.Generic.List[string]]::new()
$success = $false
try {
    New-Item -ItemType Directory -Path $stage | Out-Null
    New-Item -ItemType Directory -Path $backup | Out-Null
    Copy-Item -LiteralPath $SourceExe -Destination (Join-Path $stage $ExeName) -ErrorAction Stop
    [IO.File]::WriteAllText((Join-Path $stage '.omniinstaller-appid'), $appId)
    [IO.File]::WriteAllText((Join-Path $stage '.omniinstaller-version'), $version)
    foreach ($name in $files) {
        $path = Join-Path $dest $name
        if (Test-Path -LiteralPath $path) {
            Move-Item -LiteralPath $path -Destination (Join-Path $backup $name) -ErrorAction Stop
            $movedOld.Add($name)
        }
    }
    foreach ($name in $files) {
        Move-Item -LiteralPath (Join-Path $stage $name) -Destination (Join-Path $dest $name) -ErrorAction Stop
        $movedNew.Add($name)
    }
    $success = $true
    Write-Output "Installed $appId ($version) to $dest"
}
catch {
    foreach ($name in $movedNew) {
        Remove-Item -LiteralPath (Join-Path $dest $name) -Force -ErrorAction SilentlyContinue
    }
    foreach ($name in $movedOld) {
        Move-Item -LiteralPath (Join-Path $backup $name) -Destination (Join-Path $dest $name) -ErrorAction SilentlyContinue
    }
    throw
}
finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
    if ($success -and (Test-Path -LiteralPath $backup)) {
        Remove-Item -LiteralPath $backup -Recurse -Force
    }
}
