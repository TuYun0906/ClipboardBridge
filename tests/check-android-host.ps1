$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$build = (New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot 'tls-build')).FullName
$pfx = Join-Path $build 'host.pfx'
$der = Join-Path $build 'host.der'
$exe = Join-Path $build 'AndroidHostCheck.exe'
$rsa = [Security.Cryptography.RSA]::Create(2048)
try {
    $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new(
        'CN=Android Host Test', $rsa, [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $cert = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-1), [DateTimeOffset]::UtcNow.AddDays(1))
    try {
        [IO.File]::WriteAllBytes($pfx, $cert.Export('Pfx', 'clipboard-test'))
        [IO.File]::WriteAllBytes($der, $cert.RawData)
    } finally { $cert.Dispose() }
} finally { $rsa.Dispose() }

$windows = 'tests/AndroidHostCheck.cs', 'windows/TlsLink.cs', 'windows/Wire.cs', 'windows/SyncClient.cs' |
    ForEach-Object { Join-Path $root $_ }
& "$env:WINDIR/Microsoft.NET/Framework64/v4.0.30319/csc.exe" /nologo /codepage:65001 "/out:$exe" $windows
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$android = 'tests/AndroidHostCheck.java', 'android/src/clip/TlsLink.java', 'android/src/clip/Wire.java',
    'android/src/clip/SyncClient.java', 'android/src/clip/SyncHost.java' | ForEach-Object { Join-Path $root $_ }
javac --release 17 -encoding UTF-8 -d $build $android
if ($LASTEXITCODE) { exit $LASTEXITCODE }

$info = [Diagnostics.ProcessStartInfo]::new((Get-Command java).Source)
foreach ($arg in '-cp', $build, 'clip.AndroidHostCheck', $pfx) { $info.ArgumentList.Add($arg) }
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$hostProcess = [Diagnostics.Process]::Start($info)
try {
    $port = $hostProcess.StandardOutput.ReadLineAsync()
    if (!$port.Wait(8000) -or !$port.Result) { throw 'Android host did not start' }
    & $exe $port.Result $der
    if ($LASTEXITCODE) { throw 'Windows-to-Android host exchange failed' }
    if (!$hostProcess.WaitForExit(8000) -or $hostProcess.ExitCode) { throw 'Android host relay failed' }
    'PASS: Android host authentication, Windows and Android peers, clipboard, history edits and deletion, negotiated image deduplication, and cancellation'
} finally {
    if (!$hostProcess.HasExited) { $hostProcess.Kill() }
    $hostProcess.Dispose()
}