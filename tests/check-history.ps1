$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$scratch = New-Item -ItemType Directory (Join-Path $PSScriptRoot ('history-build-' + [Guid]::NewGuid().ToString('N')))
$exe = Join-Path $scratch.FullName 'history-check.exe'
$sources = @((Join-Path $PSScriptRoot 'HistoryCheck.cs'), (Join-Path $root 'windows/ClipHistory.cs'), (Join-Path $root 'windows/Wire.cs'))
& "$env:WINDIR/Microsoft.NET/Framework64/v4.0.30319/csc.exe" /nologo /codepage:65001 "/out:$exe" $sources
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& $exe $scratch.FullName
exit $LASTEXITCODE
