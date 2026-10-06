$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaDirectory = Split-Path -Parent (Get-Command java.exe -ErrorAction Stop).Source
$arguments = '--enable-native-access=ALL-UNNAMED -cp dist/CopyWords.jar scripts/TrayMenuSmoke.java'
$process = Start-Process -FilePath (Join-Path $javaDirectory 'java.exe') -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot 'logs/tray-menu-smoke.stdout.log') -RedirectStandardError (Join-Path $projectRoot 'logs/tray-menu-smoke.stderr.log') -PassThru
$process.Id | Set-Content -LiteralPath (Join-Path $projectRoot 'temp/tray-menu-smoke.pid')
