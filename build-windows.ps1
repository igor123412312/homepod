$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Install Git for Windows' }
if (-not (Get-Command py -ErrorAction SilentlyContinue)) { throw 'Install Python 3' }
& py -3 -m unittest discover -s tests -v
if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
& py -3 .\prepare.py
if ($LASTEXITCODE -ne 0) { throw 'Source patch failed' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$studioJava = 'C:\Program Files\Android\Android Studio\jbr'
if (-not $env:JAVA_HOME -and (Test-Path $studioJava)) { $env:JAVA_HOME = $studioJava }
Push-Location .\_centuryplay
try {
    & .\gradlew.bat --no-daemon assembleDebug testDebugUnitTest assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Gradle build/test failed' }
    Copy-Item '.\app\build\outputs\apk\debug\app-debug.apk' '..\HomePod-Music-v0.6-debug.apk' -Force
} finally { Pop-Location }
Write-Host 'APK: HomePod-Music-v0.6-debug.apk'
