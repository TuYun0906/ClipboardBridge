$ErrorActionPreference = 'Stop'
$version = '13.1.5'
$root = Join-Path $PSScriptRoot 'deps'
$hashes = @{
    aidl = '33FE7191CDD69FCB66D649264F3B0C47ACB2F3D6343AFC05B98DBBFF6F221963'
    api = '4DEF9BDE498EF8626614C2FC5DB9AF4749C86F16F6C33E3F5658D35E70BAB59B'
    provider = 'B0F18CD9812464EC171C53CAC93A819FE411718A3965C311F01EB4DE265381B3'
    shared = '4659642C9339BE0A26E9C65BB8648F7AD6D8F4A465F557993CCBC78802381635'
}
function Fetch($url, $path, $hash) {
    if (!(Test-Path -LiteralPath $path) -or (Get-FileHash -LiteralPath $path).Hash -ne $hash) {
        curl.exe --fail --location --silent --show-error --retry 2 --max-time 45 $url --output $path
        if ($LASTEXITCODE) { throw 'Dependency download failed' }
    }
    if ((Get-FileHash -LiteralPath $path).Hash -ne $hash) { throw "Dependency checksum mismatch for $path" }
}
foreach ($name in $hashes.Keys) {
    $archive = "$name-$version"
    $zip = Join-Path $root "$archive.zip"
    Fetch "https://repo.maven.apache.org/maven2/dev/rikka/shizuku/$name/$version/$archive.aar" $zip $hashes[$name]
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $root $name) -Force
}
Fetch 'https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.3.0/annotation-1.3.0.jar' `
    (Join-Path $root 'annotation.jar') '97DC45AFEFE3A1E421DA42B8B6E9F90491477C45FC6178203E3A5E8A05EE8553'
