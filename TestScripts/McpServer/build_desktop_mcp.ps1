param()
$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$project = Join-Path $repoRoot "OmniSync.Hub\src\OmniSync.Hub\OmniSync.Hub.csproj"
$output = Join-Path $repoRoot "artifacts\mcp-desktop-validation"
dotnet build $project -c Debug -o $output --nologo -v quiet -clp:ErrorsOnly
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output "MCP desktop build validation succeeded. Did not restart OmniHub."
