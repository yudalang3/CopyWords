$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ShortcutIconCheck {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct FileInfo {
        public IntPtr Icon;
        public int IconIndex;
        public uint Attributes;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string DisplayName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)] public string TypeName;
    }
    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr SHGetFileInfo(string path, uint attributes,
        out FileInfo info, uint size, uint flags);
    [DllImport("user32.dll")]
    public static extern bool DestroyIcon(IntPtr icon);
}
'@
$shortcutPath = Join-Path $projectRoot 'CopyWords.lnk'
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$expectedIcon = (Join-Path $projectRoot 'dist/CopyWords.ico') + ',0'
if ($shortcut.IconLocation -ne $expectedIcon) { throw 'Shortcut icon path is incorrect.' }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot 'dist/CopyWords.ico'))) {
    throw 'Published ICO is missing.'
}
$info = New-Object ShortcutIconCheck+FileInfo
$result = [ShortcutIconCheck]::SHGetFileInfo($shortcutPath, 0, [ref]$info,
    [Runtime.InteropServices.Marshal]::SizeOf($info), 0x120)
if ($result -eq [IntPtr]::Zero -or $info.Icon -eq [IntPtr]::Zero) {
    throw 'Windows Shell could not load the shortcut icon.'
}
try {
    $icon = [System.Drawing.Icon]::FromHandle($info.Icon)
    $bitmap = $icon.ToBitmap()
    try { $bitmap.Save((Join-Path $projectRoot 'logs/shortcut-icon.png'), [Drawing.Imaging.ImageFormat]::Png) }
    finally { $bitmap.Dispose(); $icon.Dispose() }
} finally { [ShortcutIconCheck]::DestroyIcon($info.Icon) | Out-Null }
"PASS Windows Shell loads shortcut icon: $($shortcut.IconLocation)" |
    Set-Content -LiteralPath (Join-Path $projectRoot 'logs/shortcut-icon-report.txt') -Encoding UTF8
Write-Output 'PASS shortcut icon path and Windows Shell extraction'
