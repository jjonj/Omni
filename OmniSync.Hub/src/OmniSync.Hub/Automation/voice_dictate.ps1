param(
    [int]$ListenSeconds = 12
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$engine = $null
try {
    $available = [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers()
    if ($available.Count -eq 0) { throw 'No Windows speech recognizer installed.' }
    $engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine($available[0])
    $engine.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar))
    $engine.SetInputToDefaultAudioDevice()
    $result = $engine.Recognize([TimeSpan]::FromSeconds([Math]::Max(2, [Math]::Min(30, $ListenSeconds))))
    if ($null -eq $result -or [string]::IsNullOrWhiteSpace($result.Text)) {
        exit 2
    }
    [Console]::Out.Write($result.Text.Trim())
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
} finally {
    if ($null -ne $engine) { $engine.Dispose() }
}
