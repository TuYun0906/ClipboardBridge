param(
    [Parameter(Mandatory)][string]$AndroidJar,
    [Parameter(Mandatory)][string]$BuildTools,
    [Parameter(Mandatory)][string]$Jdk,
    [string]$ProjectDir = $PSScriptRoot,
    [string]$ApkName = 'Clipboard-Android.apk',
    [int]$VersionCode = 1,
    [string]$VersionName = '0.1.0',
    [string]$SigningKey
)

$ErrorActionPreference = 'Stop'
$build = (New-Item -ItemType Directory -Force (Join-Path $ProjectDir 'build')).FullName
$classes = (New-Item -ItemType Directory -Force (Join-Path $build 'classes')).FullName
$dex = (New-Item -ItemType Directory -Force (Join-Path $build 'dex')).FullName
$aapt = Join-Path $BuildTools 'aapt2.exe'
$java = Join-Path $Jdk 'bin/java.exe'
$javac = Join-Path $Jdk 'bin/javac.exe'
$jar = Join-Path $Jdk 'bin/jar.exe'
$keytool = Join-Path $Jdk 'bin/keytool.exe'
$signer = Join-Path $BuildTools 'lib/apksigner.jar'
$unsigned = Join-Path $build 'unsigned.apk'
$aligned = Join-Path $build 'aligned.apk'
$apk = Join-Path $build $ApkName
$key = if ($SigningKey) { (Resolve-Path -LiteralPath $SigningKey).Path } else { Join-Path $build 'local-debug.p12' }
$resources = Join-Path $build 'resources.zip'

function Run([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

Run $aapt @('compile', '--dir', (Join-Path $ProjectDir 'res'), '-o', $resources)
$linkArgs = @('link', '-I', $AndroidJar, '--manifest', (Join-Path $ProjectDir 'AndroidManifest.xml'),
    '--version-code', "$VersionCode", '--version-name', $VersionName, '-o', $unsigned)
$license = Join-Path $ProjectDir 'deps/SHIZUKU-LICENSE.txt'
if (Test-Path -LiteralPath $license) {
    $notices = (New-Item -ItemType Directory -Force (Join-Path $build 'notices')).FullName
    Copy-Item -LiteralPath $license -Destination $notices
    $linkArgs += @('-A', $notices)
}
Run $aapt ($linkArgs + @($resources))
$sources = @(Get-ChildItem -LiteralPath (Join-Path $ProjectDir 'src') -Filter '*.java' -Recurse |
    ForEach-Object FullName)
$libraries = @()
$aidl = Join-Path $ProjectDir 'aidl'
if (Test-Path -LiteralPath $aidl) {
    $generated = (New-Item -ItemType Directory -Force (Join-Path $build 'generated')).FullName
    $framework = Join-Path (Split-Path -Parent $AndroidJar) 'framework.aidl'
    foreach ($inputFile in (Get-ChildItem -LiteralPath $aidl -Filter '*.aidl' -Recurse)) {
        Run (Join-Path $BuildTools 'aidl.exe') @('-I', $aidl, '-p', $framework, '-o', $generated, $inputFile.FullName)
    }
    $sources += @(Get-ChildItem -LiteralPath $generated -Filter '*.java' -Recurse | ForEach-Object FullName)
    $libraries = @('api','provider','aidl','shared' | ForEach-Object {
        (Resolve-Path (Join-Path $ProjectDir "deps/$_/classes.jar")).Path
    })
}
$classpath = (@($AndroidJar) + $libraries) -join ';'
$annotations = Join-Path $ProjectDir 'deps/annotation.jar'
if (Test-Path -LiteralPath $annotations) { $classpath += ";$annotations" }
Run $javac (@('--release', '17', '-encoding', 'UTF-8', '-classpath', $classpath, '-d', $classes) + $sources)
Run $jar @('cf', (Join-Path $build 'classes.jar'), '-C', $classes, '.')
Run $java (@('-cp', (Join-Path $BuildTools 'lib/d8.jar'), 'com.android.tools.r8.D8',
    '--min-api', '29', '--lib', $AndroidJar, '--output', $dex, (Join-Path $build 'classes.jar')) + $libraries)
Run $jar @('uf', $unsigned, '-C', $dex, 'classes.dex')
Run (Join-Path $BuildTools 'zipalign.exe') @('-f', '4', $unsigned, $aligned)

if (!(Test-Path -LiteralPath $key)) {
    Run $keytool @('-genkeypair', '-keystore', $key, '-storetype', 'PKCS12', '-storepass', 'android',
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-alias', 'clipboard',
        '-dname', 'CN=Clipboard Local Debug')
}
if (!$SigningKey) { $env:CLIPBOARD_APK_PASSWORD = 'android' }
if (!$env:CLIPBOARD_APK_PASSWORD) { throw 'Android signing password is missing' }
Run $java @('-jar', $signer, 'sign', '--ks', $key, '--ks-pass', 'env:CLIPBOARD_APK_PASSWORD', '--out', $apk, $aligned)
Run $java @('-jar', $signer, 'verify', '--verbose', $apk)
Run $aapt @('dump', 'badging', $apk)
