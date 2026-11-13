$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$build = New-Item -ItemType Directory -Force (Join-Path $PSScriptRoot 'tls-build')
$pfx = Join-Path $build.FullName 'test.pfx'
$der = Join-Path $build.FullName 'test.der'
$exe = Join-Path $build.FullName 'TlsCheck.exe'

$rsa = [Security.Cryptography.RSA]::Create(2048)
try {
    $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new(
        'CN=Clipboard Test', $rsa, [Security.Cryptography.HashAlgorithmName]::SHA256,
        [Security.Cryptography.RSASignaturePadding]::Pkcs1)
    $cert = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-1), [DateTimeOffset]::UtcNow.AddDays(1))
    try {
        [IO.File]::WriteAllBytes($pfx, $cert.Export('Pfx', 'clipboard-test'))
        [IO.File]::WriteAllBytes($der, $cert.RawData)
    } finally { $cert.Dispose() }
} finally { $rsa.Dispose() }

$windows = 'tests/TlsCheck.cs', 'windows/TlsLink.cs', 'windows/Wire.cs' |
    ForEach-Object { (Resolve-Path (Join-Path $root $_)).Path }
& "$env:WINDIR/Microsoft.NET/Framework64/v4.0.30319/csc.exe" /nologo /codepage:65001 "/out:$exe" $windows
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$android = 'tests/TlsCheck.java', 'tests/ClientCheck.java', 'android/src/clip/TlsLink.java',
    'android/src/clip/Wire.java', 'android/src/clip/SyncClient.java' |
    ForEach-Object { (Resolve-Path (Join-Path $root $_)).Path }
javac --release 17 -encoding UTF-8 -d $build.FullName $android
if ($LASTEXITCODE) { exit $LASTEXITCODE }

$info = [Diagnostics.ProcessStartInfo]::new($exe)
$info.ArgumentList.Add($pfx)
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
$server = [Diagnostics.Process]::Start($info)
try {
    $portLine = $server.StandardOutput.ReadLineAsync()
    if (!$portLine.Wait(6000) -or !$portLine.Result) {
        throw "Server could not start: $($server.StandardError.ReadToEndAsync().WaitAsync([TimeSpan]::FromSeconds(2)).GetAwaiter().GetResult())"
    }
    java -cp $build.FullName clip.TlsCheck $portLine.Result $der
    if ($LASTEXITCODE) {
        if (!$server.WaitForExit(1500)) { $server.Kill() }
        throw "Java client failed: $LASTEXITCODE. Server: $($server.StandardError.ReadToEnd())"
    }
    java -cp $build.FullName clip.ClientCheck $portLine.Result $der
    if ($LASTEXITCODE) {
        if (!$server.WaitForExit(1500)) { $server.Kill() }
        throw "Java reconnect check failed: $LASTEXITCODE. Server: $($server.StandardError.ReadToEnd())"
    }
    if (!$server.WaitForExit(6000)) { throw 'Server did not finish' }
    if ($server.ExitCode) { throw $server.StandardError.ReadToEnd() }
    'PASS: authenticated frame exchange, incorrect pin and key rejection, and latest-frame retry'
} finally {
    if (!$server.HasExited) { $server.Kill() }
    $server.Dispose()
}
