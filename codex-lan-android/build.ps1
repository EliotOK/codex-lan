param([string]$SdkPath = $env:ANDROID_HOME, [ValidateSet('Debug','Release')][string]$Variant = 'Debug')
$ErrorActionPreference = 'Stop'
if (-not $SdkPath) { throw '请设置 ANDROID_HOME 或用 -SdkPath 指定 Android SDK。' }
$env:ANDROID_HOME = (Resolve-Path -LiteralPath $SdkPath).Path
$buildTask = if ($Variant -eq 'Release') { ':app:assembleRelease' } else { ':app:assembleDebug' }
Push-Location $PSScriptRoot
try {
    & .\gradlew.bat $buildTask :app:testDebugUnitTest :app:lintDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android 构建或验证失败。' }
    Write-Host "APK 位于 app\build\outputs\apk\$($Variant.ToLower())。Release 产物需要使用自己的密钥签名。"
} finally { Pop-Location }
