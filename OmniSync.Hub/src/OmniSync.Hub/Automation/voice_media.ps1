param(
    [ValidateSet('status', 'play', 'pause')]
    [string]$Action = 'status'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime]
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() |
    Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1 } |
    Select-Object -First 1

function Wait-WinRt($operation, [type]$resultType) {
    $task = $asTask.MakeGenericMethod($resultType).Invoke($null, @($operation))
    return $task.GetAwaiter().GetResult()
}

try {
    $manager = Wait-WinRt ($managerType::RequestAsync()) $managerType
    $session = $manager.GetCurrentSession()
    if ($null -eq $session) {
        Write-Output 'no-session'
        exit 2
    }

    $status = $session.GetPlaybackInfo().PlaybackStatus.ToString()
    if ($Action -eq 'status') {
        Write-Output $status
        exit 0
    }
    if ($Action -eq 'pause' -and $status -ne 'Playing') {
        Write-Output "already-$status"
        exit 0
    }
    if ($Action -eq 'play' -and $status -eq 'Playing') {
        Write-Output 'already-Playing'
        exit 0
    }

    $operation = if ($Action -eq 'pause') { $session.TryPauseAsync() } else { $session.TryPlayAsync() }
    $success = Wait-WinRt $operation ([bool])
    Write-Output "$Action=$success"
    if (-not $success) { exit 3 }
} catch {
    Write-Error $_
    exit 1
}
