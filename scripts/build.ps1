$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
New-Item -ItemType Directory -Force logs,temp,dist | Out-Null
$javaCommand = Get-Command java.exe -ErrorAction Stop
$javaDirectory = Split-Path -Parent $javaCommand.Source
$env:JAVA_HOME = Split-Path -Parent $javaDirectory
$env:MAVEN_OPTS = "$env:MAVEN_OPTS --enable-native-access=ALL-UNNAMED -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Duser.language=en"
& mvn -B '-Dmaven.repo.local=temp/m2' test package *> logs/build.log
if ($LASTEXITCODE -ne 0) {
    Get-Content -LiteralPath logs/build.log -Tail 60
    throw 'Maven build failed. See logs/build.log.'
}
Copy-Item -LiteralPath temp/build/CopyWords.jar -Destination dist/CopyWords.jar -Force
Copy-Item -LiteralPath assets/CopyWords.ico -Destination dist/CopyWords.ico -Force
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut((Join-Path $projectRoot 'CopyWords.lnk'))
$shortcut.TargetPath = Join-Path $javaDirectory 'javaw.exe'
$shortcut.Arguments = '--enable-native-access=ALL-UNNAMED -jar "' + (Join-Path $projectRoot 'dist/CopyWords.jar') + '"'
$shortcut.WorkingDirectory = $projectRoot
$shortcut.Description = 'CopyWords clipboard shortcuts'
$shortcut.IconLocation = (Join-Path $projectRoot 'dist/CopyWords.ico') + ',0'
$shortcut.Save()
Write-Output 'Build complete. Double-click CopyWords.lnk to launch.'
