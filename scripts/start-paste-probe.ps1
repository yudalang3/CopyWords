$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$signals = Join-Path $projectRoot 'temp/paste-probe'
New-Item -ItemType Directory -Force $signals | Out-Null
foreach ($signalName in @('ready.txt','copied.txt','stop.txt')) {
    $signalPath = Join-Path $signals $signalName
    if (Test-Path -LiteralPath $signalPath) { Remove-Item -LiteralPath $signalPath }
}
$javaPath = (Get-Command java.exe -ErrorAction Stop).Source
$arguments = '--enable-native-access=ALL-UNNAMED -cp "' + (Join-Path $projectRoot 'dist/CopyWords.jar') + '" "' + (Join-Path $PSScriptRoot 'CapsPasteProbe.java') + '" "' + $signals + '"'
$probe = Start-Process -FilePath $javaPath -ArgumentList $arguments -WindowStyle Hidden -WorkingDirectory $projectRoot -PassThru -RedirectStandardOutput (Join-Path $projectRoot 'logs/paste-probe-output.log') -RedirectStandardError (Join-Path $projectRoot 'logs/paste-probe-error.log')
$probe.Id | Set-Content -LiteralPath (Join-Path $signals 'pid.txt')
Write-Output ('Probe process: ' + $probe.Id)
