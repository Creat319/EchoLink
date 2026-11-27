# 构建 EchoLink Windows 安装器（单文件 exe）
#
# 做三件事：
#   1. 发布 WinUI 客户端到 artifacts\pc-winui（已包含体积精简）
#   2. 确保 .NET 8 桌面运行时安装包在 pc\installer\runtime\（没有就下载）
#   3. 用 Inno Setup 编出 artifacts\EchoLink-Setup-<版本>.exe
#
# 用法： pwsh -File scripts\build-installer.ps1
#        加 -SkipPublish 可以只重新打包（应用没变时省时间）

param(
    [switch]$SkipPublish
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$installerDir = Join-Path $root "pc\installer"
$runtimeDir = Join-Path $installerDir "runtime"
$runtimeExe = Join-Path $runtimeDir "windowsdesktop-runtime-8-win-x64.exe"
$publishDir = Join-Path $root "artifacts\pc-winui"

# 版本号以 Directory.Build.props 为准，避免和安装器脚本里的写死的值漂移
[xml]$props = Get-Content (Join-Path $root "pc\Directory.Build.props")
$version = $props.Project.PropertyGroup.Version
Write-Host "版本: $version"

# ---------------------------------------------------------------- 1. 发布应用
if (-not $SkipPublish) {
    Write-Host "发布 WinUI 客户端…"
    dotnet publish (Join-Path $root "pc\src\EchoLink.App.WinUI\EchoLink.App.WinUI.csproj") `
        -c Release -o $publishDir --nologo | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "应用发布失败" }

    $size = [math]::Round(((Get-ChildItem $publishDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
    Write-Host "  应用体积: $size MB"
}

# ---------------------------------------------------------------- 2. .NET 运行时
if (-not (Test-Path $runtimeExe)) {
    Write-Host "下载 .NET 8 桌面运行时（约 56 MB，只需一次）…"
    New-Item -ItemType Directory -Path $runtimeDir -Force | Out-Null
    $url = "https://aka.ms/dotnet/8.0/windowsdesktop-runtime-win-x64.exe"
    & curl.exe -L --fail --retry 3 --retry-delay 3 -o $runtimeExe $url
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $runtimeExe)) {
        throw "下载 .NET 运行时失败。请手动下载后放到 $runtimeExe"
    }
}

$runtimeSize = [math]::Round((Get-Item $runtimeExe).Length / 1MB, 1)
Write-Host "  .NET 运行时: $runtimeSize MB"

# ---------------------------------------------------------------- 3. 编译安装器
$isccCandidates = @(
    "C:\Program Files\Inno Setup 7\ISCC.exe",
    "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
    "C:\Program Files\Inno Setup 6\ISCC.exe"
)
$iscc = $isccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) {
    throw "未找到 Inno Setup 编译器（ISCC.exe）。请先安装 Inno Setup 7。"
}

Write-Host "编译安装器…"
Push-Location $installerDir
try {
    & $iscc "/DAppVersion=$version" "EchoLink.iss"
    if ($LASTEXITCODE -ne 0) { throw "安装器编译失败" }
}
finally {
    Pop-Location
}

$built = Join-Path $installerDir "dist\EchoLink-Setup-$version.exe"
if (-not (Test-Path $built)) { throw "没有找到编译产物: $built" }

$final = Join-Path $root "artifacts\EchoLink-Setup-$version.exe"
Copy-Item $built $final -Force

$setupSize = [math]::Round((Get-Item $final).Length / 1MB, 1)
Write-Host ""
Write-Host "完成: $final ($setupSize MB)"
