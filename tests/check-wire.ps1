$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$exe=Join-Path $root 'tests\ProtocolCheck.exe'
$csTest=(Join-Path $PSScriptRoot 'ProtocolCheck.cs')
$csWire=(Join-Path $root 'windows/Wire.cs')
& "$env:WINDIR/Microsoft.NET/Framework64/v4.0.30319/csc.exe" /nologo /codepage:65001 "/out:$exe" $csTest $csWire
if ($LASTEXITCODE) { exit $LASTEXITCODE }
javac --release 17 -encoding UTF-8 -d "$root/tests/classes" "$root/tests/ProtocolCheck.java" "$root/android/src/clip/Wire.java"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& $exe write "$root/tests/windows.bin"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
java -cp "$root/tests/classes" clip.ProtocolCheck read "$root/tests/windows.bin"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
java -cp "$root/tests/classes" clip.ProtocolCheck write "$root/tests/android.bin"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
& $exe read "$root/tests/android.bin"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
if ((Get-FileHash "$root/tests/windows.bin").Hash -ne (Get-FileHash "$root/tests/android.bin").Hash) { throw 'Output bytes differ' }
'PASS: Cross-language frame exchange and malformed frame rejection'
