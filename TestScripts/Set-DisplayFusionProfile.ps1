<#
.SYNOPSIS
Loads a DisplayFusion monitor profile and automatically clicks Keep Changes.
.EXAMPLE
powershell -NoProfile -ExecutionPolicy Bypass -File .\TestScripts\Set-DisplayFusionProfile.ps1 -Profile SOFA
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Profile = 'SOFA',
    [ValidateRange(5, 120)]
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$displayFusion = Get-Process -Name DisplayFusion | Select-Object -First 1
$commandPath = Join-Path (Split-Path $displayFusion.Path) 'DisplayFusionCommand.exe'
$profileKeys = @(Get-ChildItem 'HKCU:\Software\Binary Fortress Software\DisplayFusion\MonitorConfig' |
    Where-Object { (Get-ItemProperty -LiteralPath $_.PSPath).Name -eq $Profile })
if ($profileKeys.Count -ne 1) { throw "Expected one saved monitor profile named '$Profile'; found $($profileKeys.Count)." }
$savedProfile = Get-ItemProperty -LiteralPath $profileKeys[0].PSPath
$previousLastUsed = $savedProfile.DateTimeLastUsedUTC

$processCondition = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ProcessIdProperty, [int]$displayFusion.Id)
$buttonCondition = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
    [System.Windows.Automation.ControlType]::Button)

# Launch asynchronously so we can accept the dialog while the command waits.
$command = Start-Process -FilePath $commandPath -ArgumentList @('-monitorloadprofile', ('"{0}"' -f $savedProfile.Name)) -WindowStyle Hidden -PassThru
$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
$accepted = $false
while ([DateTime]::UtcNow -lt $deadline) {
    $windows = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
        [System.Windows.Automation.TreeScope]::Children, $processCondition)
    foreach ($window in $windows) {
        try {
            $buttons = $window.FindAll([System.Windows.Automation.TreeScope]::Descendants, $buttonCondition)
            foreach ($button in $buttons) {
                if ($button.Current.Name.Replace('&', '').Trim() -eq 'Keep Changes' -and
                    $button.Current.IsEnabled -and -not $button.Current.IsOffscreen) {
                    Write-Host "Accepting DisplayFusion's Keep Changes dialog..."
                    $button.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke()
                    $accepted = $true
                }
            }
        } catch [System.Windows.Automation.ElementNotAvailableException] {
            # Windows can disappear between enumeration and invocation.
        }
    }
    $lastUsed = (Get-ItemProperty -LiteralPath $profileKeys[0].PSPath).DateTimeLastUsedUTC
    if ($command.HasExited -and $lastUsed -ne $previousLastUsed) {
        if ($command.ExitCode -ne 0) { throw "DisplayFusionCommand exited with code $($command.ExitCode)." }
        Write-Host "Loaded '$($savedProfile.Name)'. Confirmation automatically accepted: $accepted"
        exit 0
    }
    Start-Sleep -Milliseconds 100
}
throw "Timed out loading '$Profile'. Automatically accepted confirmation: $accepted. Check DisplayFusion for an error dialog."
