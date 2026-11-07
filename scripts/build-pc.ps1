# EchoLink PC 端构建脚本
#
#   .\scripts\build-pc.ps1                 # Release 构建 + 发布到 artifacts\pc
#   .\scripts\build-pc.ps1 -SelfContained  # 自包含单文件（目标机不需要装 .NET 运行时，约 150 MB）
#   .\scripts\build-pc.ps1 -NoPublish      # 只构建不发布
#
param(
    [switch]$SelfContained,
    [switch]$NoPublish,
    [string]$Configuration = "Release"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$pc = Join-Path $root "pc"
$out = Join-Path $root "artifacts\pc"

Write-Host "== 构建 EchoLink PC 端 ($Configuration) ==" -ForegroundColor Cyan
dotnet build (Join-Path $pc "EchoLink.slnx") -c $Configuration --nologo
if ($LASTEXITCODE -ne 0) { throw "构建失败" }

if ($NoPublish) {
    Write-Host "跳过发布（-NoPublish）" -ForegroundColor Yellow
    exit 0
}

New-Item -ItemType Directory -Force -Path $out | Out-Null

$publishArgs = @(
    "publish",
    (Join-Path $pc "src\EchoLink.App\EchoLink.App.csproj"),
    "-c", $Configuration,
    "-o", $out,
    "--nologo"
)

if ($SelfContained) {
    $publishArgs += @("-r", "win-x64", "--self-contained", "true", "-p:PublishSingleFile=true", "-p:IncludeNativeLibrariesForSelfExtract=true")
} else {
    $publishArgs += @("-p:DebugType=none")
}

Write-Host "== 发布到 $out ==" -ForegroundColor Cyan
dotnet @publishArgs
if ($LASTEXITCODE -ne 0) { throw "发布失败" }

# 诊断工具一并带上，方便现场排障
dotnet publish (Join-Path $pc "tools\EchoLink.Diagnostics\EchoLink.Diagnostics.csproj") -c $Configuration -o $out --nologo
if ($LASTEXITCODE -ne 0) { throw "诊断工具发布失败" }

Write-Host ""
Write-Host "完成。可执行文件：" -ForegroundColor Green
Get-ChildItem $out -Filter *.exe | ForEach-Object { Write-Host ("  " + $_.FullName) }
