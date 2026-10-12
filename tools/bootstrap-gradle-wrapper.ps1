$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$version = '8.13'
$cacheRoot = Join-Path $env:LOCALAPPDATA 'HiCastGradleBootstrap'
$zipPath = Join-Path $cacheRoot "gradle-$version-bin.zip"
$extractRoot = Join-Path $cacheRoot "gradle-$version"
$gradleHome = Join-Path $extractRoot "gradle-$version"
$gradleBat = Join-Path $gradleHome 'bin\gradle.bat'
$url = "https://services.gradle.org/distributions/gradle-$version-bin.zip"

New-Item -ItemType Directory -Force -Path $cacheRoot | Out-Null
if (-not (Test-Path $gradleBat)) {
    if (-not (Test-Path $zipPath)) {
        Write-Host "Downloading Gradle $version from $url ..."
        Invoke-WebRequest -Uri $url -OutFile $zipPath
    }
    New-Item -ItemType Directory -Force -Path $extractRoot | Out-Null
    Write-Host 'Extracting Gradle distribution ...'
    Expand-Archive -Path $zipPath -DestinationPath $extractRoot -Force
}

if (-not (Test-Path $gradleBat)) {
    throw "Gradle executable was not found at $gradleBat"
}

Write-Host 'Generating the standard Gradle Wrapper files in the project ...'
Push-Location $projectRoot
try {
    & $gradleBat wrapper --gradle-version $version --distribution-type bin
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle wrapper task failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
Write-Host 'Done. Use .\gradlew.bat :app:assembleDebug to build the APK.'
