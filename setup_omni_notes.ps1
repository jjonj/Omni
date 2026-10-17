$ErrorActionPreference = "Stop"

$Target = "B:\GDrive\SharedWithPhone\Obsidian\jjj\Projects And Work\Omni"
$Link = Join-Path $PSScriptRoot "OmniNotes"

if (-not (Test-Path -LiteralPath $Target)) {
    throw "Obsidian Omni notes folder not found: $Target"
}

if (Test-Path -LiteralPath $Link) {
    Write-Host "OmniNotes already exists: $Link"
    exit 0
}

cmd /c mklink /J "$Link" "$Target"
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $Link "_Agent Index.md"))) {
    throw "Failed to create or validate OmniNotes junction."
}

Write-Host "OmniNotes junction ready: $Link -> $Target"
