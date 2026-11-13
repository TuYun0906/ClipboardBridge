param([switch]$SmokeTest)
$ErrorActionPreference = 'Stop'
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$build = (New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot 'build')).FullName
$references = @('WPF/PresentationFramework.dll', 'WPF/PresentationCore.dll', 'WPF/WindowsBase.dll',
    'WPF/System.Windows.Presentation.dll', 'System.Xaml.dll', 'System.Drawing.dll',
    'System.Windows.Forms.dll', 'System.Security.dll') |
    ForEach-Object { '/r:' + (Resolve-Path (Join-Path $framework $_)).Path }
$sources = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.cs' | ForEach-Object FullName)
if ($SmokeTest) {
    $exe = Join-Path $build 'WindowsAppCheck.exe'
    $sources += (Resolve-Path (Join-Path (Split-Path -Parent $PSScriptRoot) 'tests/WindowsAppCheck.cs')).Path
    $options = @('/target:exe', '/main:WindowsAppCheck')
} else {
    $exe = Join-Path $build 'Clipboard-Windows.exe'
    $options = @('/target:winexe')
}
$icon = Join-Path $PSScriptRoot 'Clipboard.ico'
& (Join-Path $framework 'csc.exe') /nologo /codepage:65001 /platform:anycpu /optimize+ "/out:$exe" "/win32icon:$icon" "/resource:$icon,Clipboard.ico" $options $references $sources
if ($LASTEXITCODE) { exit $LASTEXITCODE }
Get-Item -LiteralPath $exe | Select-Object FullName, Length
