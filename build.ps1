$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

# Keep build tools outside the extracted project so project updates do not erase them.
$Tools = Join-Path $env:LOCALAPPDATA 'HyperCC\BuildTools'
$Sdk = Join-Path $Tools 'android-sdk'
New-Item -ItemType Directory -Force -Path $Tools, $Sdk | Out-Null

function Download-File([string]$Url, [string]$OutFile) {
    Write-Host "Downloading $Url" -ForegroundColor Cyan
    Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing
}
function Find-Java([string]$Base) {
    if (-not (Test-Path $Base)) { return $null }
    $candidate = Get-ChildItem -Path $Base -Filter java.exe -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.FullName -match '\\bin\\java\.exe$' } | Select-Object -First 1
    if ($candidate) { return (Split-Path (Split-Path $candidate.FullName -Parent) -Parent) }
    return $null
}

# Prefer installed Java 17; otherwise reuse or download a private copy once.
$JavaOk = $false
try {
    $JavaText = (& java -version 2>&1 | Out-String)
    if ($JavaText -match 'version "17\.') { $JavaOk = $true }
} catch {}
if ($JavaOk) {
    $env:JAVA_HOME = (Split-Path (Split-Path (Get-Command java).Source -Parent) -Parent)
} else {
    $JdkDir = Join-Path $Tools 'jdk17'
    $env:JAVA_HOME = Find-Java $JdkDir
    if (-not $env:JAVA_HOME) {
        Write-Host 'Java 17 not found; downloading private Temurin JDK 17 (first run only)...' -ForegroundColor Yellow
        $Zip = Join-Path $Tools 'temurin-jdk17.zip'
        Download-File 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' $Zip
        New-Item -ItemType Directory -Force -Path $JdkDir | Out-Null
        Expand-Archive -Path $Zip -DestinationPath $JdkDir -Force
        Remove-Item $Zip -Force
        $env:JAVA_HOME = Find-Java $JdkDir
    }
    if (-not $env:JAVA_HOME) { throw 'Could not locate Java 17.' }
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Write-Host "Using Java: $env:JAVA_HOME" -ForegroundColor Green

# Gradle is also cached outside the project and reused across project updates.
$GradleHome = Join-Path $Tools 'gradle-8.7'
if (-not (Test-Path (Join-Path $GradleHome 'bin\gradle.bat'))) {
    Write-Host 'Downloading Gradle 8.7 (first run only)...' -ForegroundColor Yellow
    $GradleZip = Join-Path $Tools 'gradle-8.7-bin.zip'
    Download-File 'https://services.gradle.org/distributions/gradle-8.7-bin.zip' $GradleZip
    Expand-Archive -Path $GradleZip -DestinationPath $Tools -Force
    Remove-Item $GradleZip -Force
}
$Gradle = Join-Path $GradleHome 'bin\gradle.bat'

# Download Android command-line tools only if not already cached.
$SdkManager = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $SdkManager)) {
    Write-Host 'Downloading Android SDK command-line tools (first run only)...' -ForegroundColor Yellow
    $CmdZip = Join-Path $Tools 'android-commandlinetools.zip'
    $TempCmd = Join-Path $Tools 'cmdline-tools-temp'
    Download-File 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' $CmdZip
    if (Test-Path $TempCmd) { Remove-Item $TempCmd -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $TempCmd | Out-Null
    Expand-Archive -Path $CmdZip -DestinationPath $TempCmd -Force
    $Latest = Join-Path $Sdk 'cmdline-tools\latest'
    New-Item -ItemType Directory -Force -Path (Split-Path $Latest -Parent) | Out-Null
    if (Test-Path $Latest) { Remove-Item $Latest -Recurse -Force }
    Move-Item (Join-Path $TempCmd 'cmdline-tools') $Latest
    Remove-Item $CmdZip -Force
    Remove-Item $TempCmd -Recurse -Force
}
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:Path = "$Sdk\platform-tools;$Sdk\cmdline-tools\latest\bin;$env:Path"

# Only ask sdkmanager to install packages that are actually missing.
$Missing = @()
if (-not (Test-Path (Join-Path $Sdk 'platform-tools\adb.exe'))) { $Missing += 'platform-tools' }
if (-not (Test-Path (Join-Path $Sdk 'platforms\android-34\android.jar'))) { $Missing += 'platforms;android-34' }
if (-not (Test-Path (Join-Path $Sdk 'build-tools\34.0.0\aapt.exe'))) { $Missing += 'build-tools;34.0.0' }
if ($Missing.Count -gt 0) {
    Write-Host "Installing missing Android SDK packages: $($Missing -join ', ')" -ForegroundColor Yellow
    & $SdkManager "--sdk_root=$Sdk" @Missing
    if ($LASTEXITCODE -ne 0) { throw 'Android SDK package installation failed.' }
    Write-Host 'Accepting Android SDK licences...' -ForegroundColor Yellow
    ("y`n" * 40) | & $SdkManager "--sdk_root=$Sdk" --licenses
    if ($LASTEXITCODE -ne 0) { throw 'Could not accept Android SDK licences.' }
} else {
    Write-Host 'Android SDK requirements already installed; skipping SDK downloads.' -ForegroundColor Green
}

# Point this extracted project at the persistent SDK.
$SdkForProperties = $Sdk.Replace('\', '\\').Replace(':', '\:')
Set-Content -Path (Join-Path $Root 'local.properties') -Value "sdk.dir=$SdkForProperties" -Encoding ASCII

Write-Host 'Building HyperCC debug APK...' -ForegroundColor Cyan
& $Gradle 'assembleDebug' '--no-daemon' '--stacktrace'
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Build failed. Review the error above.' -ForegroundColor Red
    exit $LASTEXITCODE
}
$Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path $Apk) {
    Write-Host "`nBUILD SUCCESSFUL: $Apk" -ForegroundColor Green
    Write-Host 'Build tools are cached in %LOCALAPPDATA%\HyperCC\BuildTools and reused after project updates.' -ForegroundColor Green
} else {
    throw 'Gradle reported success, but the expected APK was not found.'
}
