# Creates the release signing keystore once and prints what to put in the GitHub secrets.
# Windows counterpart of gen-keystore.sh. Needs keytool from a JDK (Android Studio ships one in
# <install dir>\jbr\bin; add it to PATH or call it with its full path).
# Usage (PowerShell):  .\scripts\gen-keystore.ps1 [-Out imu-mapper-release.jks] [-Alias imumapper]
param(
    [string]$Out = "imu-mapper-release.jks",
    [string]$Alias = "imumapper"
)

$ErrorActionPreference = "Stop"

if (Test-Path $Out) {
    Write-Error "refusing to overwrite existing $Out"
}

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
    $candidates = @(
        "$env:JAVA_HOME\bin\keytool.exe",
        "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe",
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr\bin\keytool.exe"
    ) | Where-Object { $_ -and (Test-Path $_) }
    if ($candidates.Count -eq 0) {
        Write-Error "keytool not found. Install a JDK or Android Studio, or add its jbr\bin folder to PATH."
    }
    $keytoolPath = $candidates[0]
} else {
    $keytoolPath = $keytool.Source
}

$secure = Read-Host -Prompt "Keystore password (also used for the key)" -AsSecureString
$pass = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
if ($pass.Length -lt 6) {
    Write-Error "password must be at least 6 characters"
}

& $keytoolPath -genkeypair -v `
    -keystore $Out -storetype PKCS12 `
    -alias $Alias -keyalg RSA -keysize 4096 -validity 10000 `
    -storepass $pass -keypass $pass `
    -dname "CN=IMU Mapper, OU=Release, O=Stastyle, C=IL"
if ($LASTEXITCODE -ne 0) {
    Write-Error "keytool failed"
}

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $Out)))
Set-Content -Path "$Out.base64" -Value $b64 -NoNewline

Write-Host ""
Write-Host "Add these four repository secrets (GitHub > Settings > Secrets and variables > Actions):"
Write-Host ""
Write-Host "  ANDROID_KEYSTORE_BASE64   = contents of $Out.base64 (one line)"
Write-Host "  ANDROID_KEYSTORE_PASSWORD = the password you just typed"
Write-Host "  ANDROID_KEY_ALIAS         = $Alias"
Write-Host "  ANDROID_KEY_PASSWORD      = the password you just typed"
Write-Host ""
Write-Host "Keep $Out private and backed up; it is git-ignored. Losing it means future releases cannot update the installed app."
