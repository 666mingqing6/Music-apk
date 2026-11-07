Add-Type -AssemblyName System.Drawing

$root = "Z:\HeoMusic\apk"
$srcPath = Join-Path $root "design\cand1.jpg"
$ttf = Join-Path $root "_toolchain\fa-solid-900.ttf"

$src = [System.Drawing.Image]::FromFile($srcPath)
$pfc = New-Object System.Drawing.Text.PrivateFontCollection
$pfc.AddFontFile($ttf)
$fam = $pfc.Families[0]

function New-Canvas([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap -ArgumentList $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    return @($bmp, $g)
}

function New-RoundedPath([double]$x, [double]$y, [double]$w, [double]$h, [double]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc([single]$x, [single]$y, [single]($r * 2), [single]($r * 2), 180, 90)
    $p.AddArc([single]($x + $w - $r * 2), [single]$y, [single]($r * 2), [single]($r * 2), 270, 90)
    $p.AddArc([single]($x + $w - $r * 2), [single]($y + $h - $r * 2), [single]($r * 2), [single]($r * 2), 0, 90)
    $p.AddArc([single]$x, [single]($y + $h - $r * 2), [single]($r * 2), [single]($r * 2), 90, 90)
    $p.CloseFigure()
    return $p
}

function Draw-Cover($g, $img, [double]$x, [double]$y, [double]$w, [double]$h) {
    $s = [Math]::Max($w / $img.Width, $h / $img.Height)
    $dw = $img.Width * $s
    $dh = $img.Height * $s
    $dx = $x + ($w - $dw) / 2
    $dy = $y + ($h - $dh) / 2
    $g.DrawImage($img, [single]$dx, [single]$dy, [single]$dw, [single]$dh)
}

$green = [System.Drawing.Color]::FromArgb(238, 59, 130, 246)
$white = New-Object System.Drawing.SolidBrush -ArgumentList ([System.Drawing.Color]::White)
$fmt = New-Object System.Drawing.StringFormat
$fmt.Alignment = [System.Drawing.StringAlignment]::Center
$fmt.LineAlignment = [System.Drawing.StringAlignment]::Center

# ---------- 1024 完整图标（圆角方形，fallback 用） ----------
$r1 = New-Canvas 1024
$bmp = $r1[0]; $g = $r1[1]
$g.Clear([System.Drawing.Color]::Transparent)
$clip = New-RoundedPath 0 0 1024 1024 210
$g.SetClip($clip)
Draw-Cover $g $src 0 0 1024 1024
$g.FillRectangle((New-Object System.Drawing.SolidBrush -ArgumentList ([System.Drawing.Color]::FromArgb(125, 0, 0, 0))), 0, 0, 1024, 1024)
$g.ResetClip()
$g.FillEllipse((New-Object System.Drawing.SolidBrush -ArgumentList $green), 262, 262, 500, 500)
$f1 = New-Object System.Drawing.Font -ArgumentList $fam, 300, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
$g.DrawString([string][char]0xf001, $f1, $white, (New-Object System.Drawing.RectangleF -ArgumentList 0, -18, 1024, 1024), $fmt)
$bmp.Save((Join-Path $root "design\icon-1024.png"), [System.Drawing.Imaging.ImageFormat]::Png)

# ---------- 1024 自适应前景（透明，圆形照片 + 绿环 + 音符） ----------
$r2 = New-Canvas 1024
$fg = $r2[0]; $g2 = $r2[1]
$g2.Clear([System.Drawing.Color]::Transparent)
$circle = New-Object System.Drawing.Drawing2D.GraphicsPath
$circle.AddEllipse(202, 202, 620, 620)
$g2.SetClip($circle)
Draw-Cover $g2 $src 202 202 620 620
$g2.FillRectangle((New-Object System.Drawing.SolidBrush -ArgumentList ([System.Drawing.Color]::FromArgb(115, 0, 0, 0))), 202, 202, 620, 620)
$g2.ResetClip()
$ring = New-Object System.Drawing.Pen -ArgumentList ([System.Drawing.Color]::FromArgb(255, 59, 130, 246)), 16
$g2.DrawEllipse($ring, 202, 202, 620, 620)
$f2 = New-Object System.Drawing.Font -ArgumentList $fam, 250, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
$g2.DrawString([string][char]0xf001, $f2, $white, (New-Object System.Drawing.RectangleF -ArgumentList 202, 186, 620, 620), $fmt)
$fg.Save((Join-Path $root "design\icon-fg-1024.png"), [System.Drawing.Imaging.ImageFormat]::Png)

# ---------- 输出各密度 mipmap ----------
$mainMap = @{ "mdpi" = 48; "hdpi" = 72; "xhdpi" = 96; "xxhdpi" = 144; "xxxhdpi" = 192 }
$fgMap = @{ "mdpi" = 108; "hdpi" = 162; "xhdpi" = 216; "xxhdpi" = 324; "xxxhdpi" = 432 }

foreach ($k in $mainMap.Keys) {
    $s = $mainMap[$k]
    $dir = Join-Path $root ("app\src\main\res\mipmap-" + $k)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $small = New-Object System.Drawing.Bitmap -ArgumentList $s, $s, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $sg = [System.Drawing.Graphics]::FromImage($small)
    $sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $sg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $sg.DrawImage($bmp, 0, 0, $s, $s)
    $small.Save((Join-Path $dir "ic_launcher.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $sg.Dispose(); $small.Dispose()
}

foreach ($k in $fgMap.Keys) {
    $s = $fgMap[$k]
    $dir = Join-Path $root ("app\src\main\res\mipmap-" + $k)
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $small = New-Object System.Drawing.Bitmap -ArgumentList $s, $s, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $sg = [System.Drawing.Graphics]::FromImage($small)
    $sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $sg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $sg.DrawImage($fg, 0, 0, $s, $s)
    $small.Save((Join-Path $dir "ic_launcher_fg.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $sg.Dispose(); $small.Dispose()
}

$g.Dispose(); $bmp.Dispose(); $g2.Dispose(); $fg.Dispose(); $src.Dispose()
"saved: design/icon-1024.png, design/icon-fg-1024.png and mipmaps"
