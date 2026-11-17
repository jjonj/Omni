param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\dist\omniinstaller-release')
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$destination = [IO.Path]::GetFullPath($OutputDirectory)
$project = Join-Path $root 'src\OmniZones.App\OmniZones.App.csproj'
$staging = Join-Path $env:TEMP ("OmniZones-Release-" + [guid]::NewGuid().ToString('N'))
$asset = Join-Path $destination 'OmniZones-win-x64.zip'
try {
    New-Item -ItemType Directory -Path $staging -Force | Out-Null
    & dotnet publish $project -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -p:DebugType=none -o $staging
    if ($LASTEXITCODE -ne 0) { throw 'Standalone OmniZones publish failed.' }
    if (-not (Test-Path -LiteralPath (Join-Path $staging 'OmniZones.exe') -PathType Leaf)) {
        throw 'Published OmniZones.exe not found.'
    }
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Compress-Archive -LiteralPath (Join-Path $staging 'OmniZones.exe') -DestinationPath $asset -Force
    Write-Output "Release asset staged at $asset (not uploaded or installed)."
}
finally {
    if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
}
