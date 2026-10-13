param([string]$ProfileHome = (Join-Path $env:APPDATA 'CopyWords'))

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)

function Get-ConfigurationHash([string]$Path) {
    $inputStream = [System.IO.File]::OpenRead($Path)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { return [System.BitConverter]::ToString($algorithm.ComputeHash($inputStream)).Replace('-', '') }
    finally { $algorithm.Dispose(); $inputStream.Dispose() }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$resolvedProfile = (Resolve-Path -LiteralPath $ProfileHome).Path
$configurationPath = Join-Path $resolvedProfile 'config.xml'
if (-not (Test-Path -LiteralPath $configurationPath -PathType Leaf)) {
    Write-Output 'No configuration to archive.'
    exit 0
}
$lockPath = Join-Path $resolvedProfile 'instance.lock'
$instanceLock = $null
$locked = $false
try {
    $instanceLock = [System.IO.File]::Open($lockPath, [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::ReadWrite)
    $instanceLock.Lock(0, 1)
    $locked = $true
    $archiveDirectory = Join-Path $projectRoot ('logs\都不要\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    New-Item -ItemType Directory -Path $archiveDirectory -ErrorAction Stop | Out-Null
    $archivePath = Join-Path $archiveDirectory 'config.xml'
    $originalHash = Get-ConfigurationHash $configurationPath
    Copy-Item -LiteralPath $configurationPath -Destination $archivePath -ErrorAction Stop
    if ((Get-ConfigurationHash $archivePath) -ne $originalHash -or
        (Get-ConfigurationHash $configurationPath) -ne $originalHash) {
        throw 'Archive verification failed; original configuration was preserved.'
    }
    [System.IO.File]::WriteAllLines((Join-Path $archiveDirectory 'archive.txt'),
        [string[]]@(('originalPath=' + $configurationPath), ('archivePath=' + $archivePath), ('sha256=' + $originalHash)),
        [System.Text.Encoding]::UTF8)
    Remove-Item -LiteralPath $configurationPath -ErrorAction Stop
    Write-Output ('Archived configuration: ' + $archivePath)
} finally {
    if ($locked) { $instanceLock.Unlock(0, 1) }
    if ($null -ne $instanceLock) { $instanceLock.Dispose() }
}
