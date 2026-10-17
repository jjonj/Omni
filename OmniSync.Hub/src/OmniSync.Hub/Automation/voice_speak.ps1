param(
    [Parameter(Mandatory = $true)]
    [string]$Text
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $voice.Speak($Text)
} finally {
    $voice.Dispose()
}
