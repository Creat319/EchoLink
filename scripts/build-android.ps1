# EchoLink Android 端构建脚本
#
#   .\scripts\build-android.ps1              # debug APK（自带调试签名，可直接安装）
#   .\scripts\build-android.ps1 -Release     # release APK（未签名，需要自己配签名）
#   .\scripts\build-android.ps1 -Test        # 只跑单元测试
#
param(
    [switch]$Release,
    [switch]$Test
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$android = Join-Path $root "android"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "C:\Android\Sdk" }

if (-not (Test-Path (Join-Path $android "local.properties"))) {
    Set-Content -Path (Join-Path $android "local.properties") -Value ("sdk.dir=" + $sdk.Replace("\", "\\")) -Encoding ASCII
    Write-Host "已写入 android\local.properties (sdk.dir=$sdk)" -ForegroundColor Yellow
}

Push-Location $android
try {
    if ($Test) {
        Write-Host "== 单元测试 ==" -ForegroundColor Cyan
        & .\gradlew.bat :app:testDebugUnitTest --console=plain
    } elseif ($Release) {
        Write-Host "== 构建 release APK ==" -ForegroundColor Cyan
        & .\gradlew.bat :app:assembleRelease --console=plain
    } else {
        Write-Host "== 构建 debug APK ==" -ForegroundColor Cyan
        & .\gradlew.bat :app:assembleDebug --console=plain
    }

    if ($LASTEXITCODE -ne 0) { throw "Gradle 构建失败" }

    $apks = Get-ChildItem -Path (Join-Path $android "app\build\outputs\apk") -Recurse -Filter *.apk
    Write-Host ""
    Write-Host "APK：" -ForegroundColor Green
    $apks | ForEach-Object { Write-Host ("  " + $_.FullName + "  (" + [math]::Round($_.Length / 1MB, 2) + " MB)") }

    # 复制一份到 artifacts\ 方便直接用（该目录不入库）
    if (-not $Release) {
        $debugApk = $apks | Where-Object { $_.Name -eq "app-debug.apk" } | Select-Object -First 1
        if ($debugApk) {
            $outDir = Join-Path $root "artifacts"
            New-Item -ItemType Directory -Force -Path $outDir | Out-Null
            Copy-Item $debugApk.FullName (Join-Path $outDir "echolink-arm64-debug.apk") -Force
            Write-Host ("已复制到 " + (Join-Path $outDir "echolink-arm64-debug.apk")) -ForegroundColor Green
        }
    }

    if (-not $Release) {
        Write-Host ""
        Write-Host "安装：$sdk\platform-tools\adb.exe install -r <apk 路径>" -ForegroundColor Cyan
        Write-Host "注意：手机需要开启「开发者选项 → USB 调试」；仅开 USB 网络共享时 adb 看不到设备。" -ForegroundColor Yellow
    }
} finally {
    Pop-Location
}
