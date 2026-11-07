# EchoLink 图标生成器
#
# 用法： powershell.exe -ExecutionPolicy Bypass -File .\scripts\gen-icon.ps1
#
# 同一份几何常量同时输出：
#   assets/icon.svg        （矢量源，README / 设计稿用）
#   assets/icon-512.png    （README 展示）
#   assets/icon.ico        （Windows 可执行文件图标，多尺寸）
# 这样 SVG / PNG / ICO 不会各画各的。
#
# 图形：深色圆角底 + 白色手机 + 三道青色声波。极简，缩到 16px 也能认出来。

param(
    [string]$OutDir = (Join-Path (Split-Path -Parent $PSScriptRoot) "assets")
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

# ---------------- 几何（1024 网格） ----------------
$Size      = 1024.0
$BgRadius  = 224.0
$PhoneX    = 248.0
$PhoneY    = 296.0
$PhoneW    = 236.0
$PhoneH    = 432.0
$PhoneR    = 54.0
$WaveCx    = 484.0
$WaveCy    = 512.0
$WaveRadii = @(96.0, 160.0, 224.0)
$WaveStart = -52.0
$WaveSweep = 104.0
$StrokeW   = 44.0
$WaveAlpha = @(255, 200, 140)

# 手机上的两笔细节：听筒缝 + 底部横条（大尺寸下更像手机，小尺寸下自然糊掉，不碍事）
$SlitX = 336.0; $SlitY = 330.0; $SlitW = 60.0; $SlitH = 14.0
$BarX  = 330.0; $BarY  = 684.0; $BarW  = 72.0; $BarH  = 10.0

$ColorBgTop    = [System.Drawing.Color]::FromArgb(255, 16, 27, 46)    # #101B2E
$ColorBgBottom = [System.Drawing.Color]::FromArgb(255, 7, 12, 22)     # #070C16
$ColorPhone    = [System.Drawing.Color]::FromArgb(255, 233, 245, 255) # #E9F5FF
$ColorDetail   = [System.Drawing.Color]::FromArgb(255, 168, 189, 209) # #A8BDD1
$ColorWave     = [System.Drawing.Color]::FromArgb(255, 100, 210, 255) # #64D2FF

function New-RoundedPath {
    param([single]$X, [single]$Y, [single]$W, [single]$H, [single]$R)

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $R * 2
    $path.AddArc($X, $Y, $d, $d, 180, 90)
    $path.AddArc($X + $W - $d, $Y, $d, $d, 270, 90)
    $path.AddArc($X + $W - $d, $Y + $H - $d, $d, $d, 0, 90)
    $path.AddArc($X, $Y + $H - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function New-IconBitmap {
    param([int]$PixelSize)

    $bmp = New-Object System.Drawing.Bitmap($PixelSize, $PixelSize, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.ScaleTransform($PixelSize / $Size, $PixelSize / $Size)

    # 底：圆角深色渐变
    $bgPath = New-RoundedPath 0 0 $Size $Size $BgRadius
    $bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.PointF(0, 0)),
        (New-Object System.Drawing.PointF(0, [single]$Size)),
        $ColorBgTop,
        $ColorBgBottom)
    $g.FillPath($bgBrush, $bgPath)
    $bgBrush.Dispose()
    $bgPath.Dispose()

    # 手机
    $phonePath = New-RoundedPath $PhoneX $PhoneY $PhoneW $PhoneH $PhoneR
    $phoneBrush = New-Object System.Drawing.SolidBrush($ColorPhone)
    $g.FillPath($phoneBrush, $phonePath)
    $phoneBrush.Dispose()
    $phonePath.Dispose()

    # 听筒缝 + 底部横条
    $detailBrush = New-Object System.Drawing.SolidBrush($ColorDetail)
    $slitPath = New-RoundedPath $SlitX $SlitY $SlitW $SlitH 7
    $g.FillPath($detailBrush, $slitPath)
    $slitPath.Dispose()
    $barPath = New-RoundedPath $BarX $BarY $BarW $BarH 5
    $g.FillPath($detailBrush, $barPath)
    $barPath.Dispose()
    $detailBrush.Dispose()

    # 三道声波（同心、圆头）
    for ($i = 0; $i -lt $WaveRadii.Count; $i++) {
        $r = $WaveRadii[$i]
        $color = [System.Drawing.Color]::FromArgb($WaveAlpha[$i], $ColorWave.R, $ColorWave.G, $ColorWave.B)
        $pen = New-Object System.Drawing.Pen($color, [single]$StrokeW)
        $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        $g.DrawArc($pen, [single]($WaveCx - $r), [single]($WaveCy - $r), [single](2 * $r), [single](2 * $r), [single]$WaveStart, [single]$WaveSweep)
        $pen.Dispose()
    }

    $g.Dispose()
    return $bmp
}

function Save-Png {
    param([System.Drawing.Bitmap]$Bitmap, [string]$Path)
    $Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

function Save-Ico {
    param([string]$Path, [int[]]$Sizes)

    $entries = New-Object System.Collections.ArrayList
    foreach ($s in $Sizes) {
        $bmp = New-IconBitmap $s
        $ms = New-Object System.IO.MemoryStream
        $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
        [void]$entries.Add([pscustomobject]@{ Size = $s; Bytes = $ms.ToArray() })
        $ms.Dispose()
    }

    $fs = [System.IO.File]::Create($Path)
    $bw = New-Object System.IO.BinaryWriter($fs)
    try {
        $bw.Write([uint16]0)                 # reserved
        $bw.Write([uint16]1)                 # type = icon
        $bw.Write([uint16]$entries.Count)    # count

        $offset = 6 + 16 * $entries.Count
        foreach ($e in $entries) {
            $dim = if ($e.Size -ge 256) { 0 } else { $e.Size }
            $bw.Write([byte]$dim)
            $bw.Write([byte]$dim)
            $bw.Write([byte]0)               # palette
            $bw.Write([byte]0)               # reserved
            $bw.Write([uint16]1)             # planes
            $bw.Write([uint16]32)            # bpp
            $bw.Write([uint32]$e.Bytes.Length)
            $bw.Write([uint32]$offset)
            $offset += $e.Bytes.Length
        }

        foreach ($e in $entries) { $bw.Write($e.Bytes) }
    } finally {
        $bw.Dispose()
        $fs.Dispose()
    }
}

function Get-Svg {
    # 角度 → 圆弧端点（SVG 用显式端点 + A 命令）
    $rad = [math]::PI / 180.0
    $arcPaths = @()
    foreach ($r in $WaveRadii) {
        $dx = $r * [math]::Cos(52.0 * $rad)
        $dy = $r * [math]::Sin(52.0 * $rad)
        $x0 = [math]::Round($WaveCx + $dx, 1)
        $y0 = [math]::Round($WaveCy - $dy, 1)
        $y1 = [math]::Round($WaveCy + $dy, 1)
        $arcPaths += ('    <path d="M {0} {1} A {2} {2} 0 0 1 {0} {3}" />' -f $x0, $y0, $r, $y1)
    }

    $opacities = @(1.0, 0.78, 0.55)
    for ($i = 0; $i -lt $arcPaths.Count; $i++) {
        $arcPaths[$i] = $arcPaths[$i] -replace '/>$', (' opacity="{0}" />' -f $opacities[$i])
    }

    return @"
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" width="1024" height="1024" role="img" aria-label="EchoLink">
  <title>EchoLink</title>
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#101B2E" />
      <stop offset="1" stop-color="#070C16" />
    </linearGradient>
  </defs>

  <!-- 底：深色圆角 -->
  <rect width="1024" height="1024" rx="224" fill="url(#bg)" />

  <!-- 手机 -->
  <rect x="248" y="296" width="236" height="432" rx="54" fill="#E9F5FF" />
  <rect x="336" y="330" width="60" height="14" rx="7" fill="#A8BDD1" />
  <rect x="330" y="684" width="72" height="10" rx="5" fill="#A8BDD1" />

  <!-- 声波：以手机右缘为圆心，三道同心圆弧 -->
  <g fill="none" stroke="#64D2FF" stroke-width="44" stroke-linecap="round">
$($arcPaths -join "`n")
  </g>
</svg>
"@
}

# ---------------- 输出 ----------------
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$svgPath = Join-Path $OutDir "icon.svg"
Set-Content -Path $svgPath -Value (Get-Svg) -Encoding UTF8
Write-Host "写入 $svgPath"

$master = New-IconBitmap 1024
Save-Png $master (Join-Path $OutDir "icon-1024.png")
$master.Dispose()

$png512 = New-IconBitmap 512
Save-Png $png512 (Join-Path $OutDir "icon-512.png")
$png512.Dispose()

$png256 = New-IconBitmap 256
Save-Png $png256 (Join-Path $OutDir "icon-256.png")
$png256.Dispose()

$icoPath = Join-Path $OutDir "icon.ico"
Save-Ico $icoPath @(16, 20, 24, 32, 40, 48, 64, 128, 256)
Write-Host "写入 $icoPath"

Get-ChildItem $OutDir | ForEach-Object { "{0,-18} {1,8:N0} B" -f $_.Name, $_.Length }
