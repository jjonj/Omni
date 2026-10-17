# Live UI regression: checks all configured scenes and invokes SOFA only.
# Run elevated when the Hub is elevated. Opens OmniSweep and leaves the PC on SOFA.
param([string]$ReportPath)
$ErrorActionPreference = 'Stop'
if ($ReportPath) { Start-Transcript -LiteralPath $ReportPath -Force | Out-Null }
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes
Add-Type -AssemblyName System.Windows.Forms

$settings = Get-Content -LiteralPath "$env:APPDATA\OmniSync\settings.json" -Raw | ConvertFrom-Json
$expected = @($settings.Macros | Where-Object { $_.Category.Trim() -ieq 'Voice' -and $_.Name.Trim() } |
    ForEach-Object { $_.Name.Trim() } | Sort-Object -Unique)
$hubProcess = Get-Process -Name OmniSync.Hub | Select-Object -First 1
$hubCondition = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, [int]$hubProcess.Id)
$buttonCondition = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
    [System.Windows.Automation.ControlType]::Button)

$repoRoot = Split-Path (Split-Path $PSScriptRoot)
Push-Location $repoRoot
try {
    python -c "import runpy; m=runpy.run_path('TestScripts/System/configure_displayfusion_scenes.py'); h=m['Hub'](); h.invoke('HandleExternalCommand','SHOW_WINDOW',''); h.ws.close()"
    if ($LASTEXITCODE -ne 0) { throw 'Could not open the Hub control window.' }
} finally { Pop-Location }
Start-Sleep -Milliseconds 500
$windows = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
    [System.Windows.Automation.TreeScope]::Children, $hubCondition)
$openButton = $null
foreach ($window in $windows) {
    $openButton = $window.FindAll([System.Windows.Automation.TreeScope]::Descendants, $buttonCondition) |
        Where-Object { $_.Current.Name -eq 'Omni Sweep' } | Select-Object -First 1
    if ($openButton) { break }
}
if (-not $openButton) { throw 'Omni Sweep button not found in the Hub. Run this test elevated.' }
$openButton.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
$deadline = [DateTime]::UtcNow.AddSeconds(10)
$sweep = $null
while (-not $sweep -and [DateTime]::UtcNow -lt $deadline) {
    $windows = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
        [System.Windows.Automation.TreeScope]::Children, $hubCondition)
    $sweep = $windows | Where-Object { $_.Current.Name -eq 'OmniSweep' } | Select-Object -First 1
    Start-Sleep -Milliseconds 100
}
if (-not $sweep) { throw 'OmniSweep did not open from the Hub button.' }
$buttons = @($sweep.FindAll([System.Windows.Automation.TreeScope]::Descendants, $buttonCondition))
foreach ($scene in $expected) {
    $matches = @($buttons | Where-Object { $_.Current.Name -ieq $scene })
    if ($matches.Count -ne 1) { throw "Expected one button for '$scene', found $($matches.Count)." }
    if (-not $matches[0].Current.IsEnabled -or $matches[0].Current.IsOffscreen) {
        throw "Scene button '$scene' is disabled or offscreen."
    }
    Write-Host "PASS scene button: $scene"
}

$sofaKey = Get-ChildItem 'HKCU:\Software\Binary Fortress Software\DisplayFusion\MonitorConfig' |
    Where-Object { (Get-ItemProperty -LiteralPath $_.PSPath).Name -eq 'SOFA' } | Select-Object -First 1
$previous = (Get-ItemProperty -LiteralPath $sofaKey.PSPath).DateTimeLastUsedUTC
$sofaButton = $buttons | Where-Object { $_.Current.Name -eq 'SOFA' } | Select-Object -First 1
$sofaButton.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
$deadline = [DateTime]::UtcNow.AddSeconds(75)
while ((Get-ItemProperty -LiteralPath $sofaKey.PSPath).DateTimeLastUsedUTC -eq $previous -and
       [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 200 }
if ((Get-ItemProperty -LiteralPath $sofaKey.PSPath).DateTimeLastUsedUTC -eq $previous) {
    throw 'SOFA scene button did not activate and persist its DisplayFusion profile.'
}
$remaining = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
    [System.Windows.Automation.TreeScope]::Children, $hubCondition) |
    Where-Object { $_.Current.Name -eq 'OmniSweep' }
if ($remaining) { throw 'OmniSweep did not close after activating a scene.' }
Write-Host 'PASS SOFA button activates its profile, automatically confirms, and closes OmniSweep'
Write-Host 'ALL PASSED'
