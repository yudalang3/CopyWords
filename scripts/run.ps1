$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaDirectory = Split-Path -Parent (Get-Command java.exe -ErrorAction Stop).Source
$javawPath = Join-Path $javaDirectory 'javaw.exe'
$jarPath = Join-Path $projectRoot 'dist/CopyWords.jar'
if (!(Test-Path -LiteralPath $jarPath)) { throw 'Run scripts/build.ps1 first.' }
Start-Process -FilePath $javawPath -ArgumentList ('--enable-native-access=ALL-UNNAMED -jar "' + $jarPath + '"') -WorkingDirectory $projectRoot -WindowStyle Hidden
