param(
    [string]$KeystorePath = (Join-Path $env:LOCALAPPDATA 'Lenshelf\signing\lenshelf-release.p12')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sdkRoot = Join-Path $projectRoot '.android-sdk'
if (-not (Test-Path -LiteralPath $sdkRoot)) {
    $sdkRoot = $env:ANDROID_HOME
}
if (-not $sdkRoot) {
    throw 'Android SDK not found. Set ANDROID_HOME or install the project-local .android-sdk.'
}

$buildTools = Join-Path $sdkRoot 'build-tools\35.0.0'
$aapt = Join-Path $buildTools 'aapt.exe'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$apksigner = Join-Path $buildTools 'apksigner.bat'
foreach ($tool in @($aapt, $zipalign, $apksigner)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "Missing Android build tool: $tool" }
}

$keytool = (Get-Command keytool.exe -ErrorAction Stop).Source
if (-not (Test-Path -LiteralPath $KeystorePath)) {
    $keystoreDir = Split-Path -Parent $KeystorePath
    New-Item -ItemType Directory -Path $keystoreDir -Force | Out-Null
    Write-Host "Creating release keystore at $KeystorePath"
    Write-Host 'Choose a strong password in this terminal and back it up with the keystore.'
    & $keytool -genkeypair -keystore $KeystorePath -storetype PKCS12 -alias lenshelf -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Lenshelf, O=Lenshelf'
    if ($LASTEXITCODE -ne 0) { throw 'Release keystore creation failed.' }
}

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') assembleRelease --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
} finally {
    Pop-Location
}

$releaseDir = Join-Path $projectRoot 'app\build\outputs\apk\release'
$unsigned = Join-Path $releaseDir 'app-release-unsigned.apk'
if (-not (Test-Path -LiteralPath $unsigned)) { throw 'Unsigned release APK not found.' }
$stageId = [guid]::NewGuid().ToString('N')
$stagedUnsigned = Join-Path $env:TEMP "lenshelf-unsigned-$stageId.apk"
$aligned = Join-Path $env:TEMP "lenshelf-aligned-$stageId.apk"
$stagedSigned = Join-Path $env:TEMP "lenshelf-signed-$stageId.apk"
try {
    # Android build tools cannot reliably open the project's non-ASCII path.
    Copy-Item -LiteralPath $unsigned -Destination $stagedUnsigned
    $badging = & $aapt dump badging $stagedUnsigned
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect release APK.' }
    $packageLine = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
    if (-not $packageLine -or $packageLine -notmatch "versionName='([^']+)'") {
        throw 'Could not read versionName from release APK.'
    }
    $version = $Matches[1]
    $signed = Join-Path $releaseDir "Lenshelf-v$version.apk"
    if (Test-Path -LiteralPath $signed) {
        throw "Signed APK already exists; move it aside before rebuilding: $signed"
    }

    & $zipalign -p 4 $stagedUnsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
    Write-Host 'Enter your release keystore password when apksigner prompts.'
    & $apksigner sign --ks $KeystorePath --ks-key-alias lenshelf --out $stagedSigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $apksigner verify --verbose --print-certs $stagedSigned
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    Copy-Item -LiteralPath $stagedSigned -Destination $signed
    Get-FileHash -LiteralPath $signed -Algorithm SHA256 | Format-List Path, Hash
    Write-Host "Release APK ready: $signed"
    Write-Host "Back up both $KeystorePath and its password. Never commit either to Git."
} finally {
    foreach ($file in @($stagedUnsigned, $aligned, $stagedSigned)) {
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
    }
}
