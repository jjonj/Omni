# Deploy only the Hub's compiled app image. Preserve every existing source change.
# Requires elevated console and the existing "OmniSync Hub" scheduled task.
param([string]$SourceDirectory = 'D:\SSDProjects\Omni\artifacts\gateway-build')
$ErrorActionPreference = 'Stop'
$source = $SourceDirectory
$target = 'D:\SSDProjects\Omni\OmniSync.Hub\src\OmniSync.Hub\bin\Debug\net9.0-windows'
$backup = Join-Path $target ('gateway-backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$names = @('OmniSync.Hub.exe','OmniSync.Hub.dll','OmniSync.Hub.pdb')
$task = 'OmniSync Hub'
function Test-Gateway {
  try {
    $response = Invoke-RestMethod 'http://127.0.0.1:3333/gateway/api/tools' -TimeoutSec 3
    $sweep = Invoke-RestMethod 'http://127.0.0.1:3333/gateway/api/sweep' -TimeoutSec 3
    return @($response).Count -ge 3 -and $null -ne $sweep.revision -and $null -ne $sweep.setups
  } catch { return $false }
}
foreach($name in $names) {
  if (-not (Test-Path (Join-Path $source $name))) { throw "Missing staged file $name" }
}
if (-not (Get-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue)) { throw 'Hub scheduled task missing' }
$listener = Get-NetTCPConnection -LocalPort 3333 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $listener) { throw 'Existing OmniHub is not running; refusing blind deployment' }
$proc = Get-CimInstance Win32_Process -Filter ("ProcessId=" + $listener.OwningProcess)
if (-not ($proc.ExecutablePath -like "*OmniSync.Hub.exe")) { throw 'Port 3333 belongs to an unexpected process' }
Write-Output "Stopping current Hub PID $($listener.OwningProcess)."
# Do not leave the scheduled task relaunching a failing process while its DLL is replaced.
Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
Stop-Process -Id $listener.OwningProcess -Force
for($i=0;$i -lt 30;$i++) {
  if (-not (Get-NetTCPConnection -LocalPort 3333 -State Listen -ErrorAction SilentlyContinue)) { break }
  Start-Sleep -Milliseconds 300
}
New-Item -ItemType Directory $backup -Force | Out-Null
foreach($name in $names) { Copy-Item -LiteralPath (Join-Path $target $name) -Destination $backup -Force }
try {
  foreach($name in $names) { Copy-Item -LiteralPath (Join-Path $source $name) -Destination $target -Force }
  Start-ScheduledTask -TaskName $task
  for($i=0;$i -lt 60;$i++) {
    if(Test-Gateway) { Write-Output "PASS: new Gateway routes running; backup=$backup"; exit 0 }
    Start-Sleep -Milliseconds 500
  }
  throw 'Hub did not serve the new gateway endpoint after restart'
} catch {
  Write-Warning "Gateway deploy unsuccessful, rolling back: $_"
  $now = Get-NetTCPConnection -LocalPort 3333 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
  Stop-ScheduledTask -TaskName $task -ErrorAction SilentlyContinue
  # A startup failure may leave no port listener but still retain an exe lock.
  Get-CimInstance Win32_Process -Filter "Name='OmniSync.Hub.exe'" |
    Where-Object { $_.ExecutablePath -eq (Join-Path $target 'OmniSync.Hub.exe') } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  foreach($name in $names) { Copy-Item -LiteralPath (Join-Path $backup $name) -Destination $target -Force }
  Start-ScheduledTask -TaskName $task
  throw
}
