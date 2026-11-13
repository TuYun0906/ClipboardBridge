$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$build = New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot ("history-build-" + [guid]::NewGuid().ToString("N")))
$exe = Join-Path $build.FullName 'HistorySyncServerCheck.exe'
$windows = 'tests/HistorySyncServerCheck.cs', 'windows/SyncServer.cs', 'windows/Identity.cs',
    'windows/TlsLink.cs', 'windows/Wire.cs', 'windows/ClipHistory.cs' |
    ForEach-Object { (Resolve-Path (Join-Path $root $_)).Path }
& "$env:WINDIR/Microsoft.NET/Framework64/v4.0.30319/csc.exe" /nologo /codepage:65001 "/out:$exe" /r:System.Security.dll $windows
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$android = 'tests/HistorySyncPeersCheck.java', 'android/src/clip/TlsLink.java', 'android/src/clip/Wire.java',
    'android/src/clip/SyncClient.java' | ForEach-Object { (Resolve-Path (Join-Path $root $_)).Path }
javac --release 17 -encoding UTF-8 -d $build.FullName $android
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$info = [Diagnostics.ProcessStartInfo]::new($exe)
$info.ArgumentList.Add($build.FullName)
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardInput = $true
$info.RedirectStandardOutput = $true
$server = [Diagnostics.Process]::Start($info)
try {
    $ready = $server.StandardOutput.ReadLineAsync()
    if (!$ready.Wait(8000) -or !$ready.Result) { throw 'History sync server could not start' }
    $port, $pairing = $ready.Result.Split(' ')
    java -cp $build.FullName clip.HistorySyncPeersCheck $port $pairing
    if ($LASTEXITCODE) { throw 'History sync across peers failed' }
    $server.StandardInput.WriteLine()
    if (!$server.WaitForExit(6000) -or $server.ExitCode) { throw 'History sync server did not exit cleanly' }
} finally {
    if (!$server.HasExited) { $server.Kill(); $server.WaitForExit() }
    $server.Dispose()
}
