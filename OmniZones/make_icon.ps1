# Generates src/OmniZones.App/omnizones.ico (zones on a dark tile). Run once; the .ico is committed.
Add-Type -AssemblyName System.Drawing
$sizes = 16, 32, 48, 256
$pngs = foreach ($s in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap $s, $s
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)
    $r = [int]($s * 0.18)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc(0, 0, $d, $d, 180, 90); $path.AddArc($s - $d - 1, 0, $d, $d, 270, 90)
    $path.AddArc($s - $d - 1, $s - $d - 1, $d, $d, 0, 90); $path.AddArc(0, $s - $d - 1, $d, $d, 90, 90); $path.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 17, 20, 27))), $path)
    $amber = [System.Drawing.Color]::FromArgb(255, 245, 165, 36)
    $fill = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(70, 245, 165, 36))
    $pen = New-Object System.Drawing.Pen $amber, ([Math]::Max(1.0, $s / 22))
    $m = $s * 0.14; $gap = $s * 0.06
    $w = $s - 2 * $m; $left = ($w - $gap) * 0.45; $right = $w - $gap - $left
    $rects = @(
        @($m, $m, $left, $w),
        @(($m + $left + $gap), $m, $right, (($w - $gap) / 2)),
        @(($m + $left + $gap), ($m + ($w - $gap) / 2 + $gap), $right, (($w - $gap) / 2))
    )
    foreach ($q in $rects) {
        $g.FillRectangle($fill, [single]$q[0], [single]$q[1], [single]$q[2], [single]$q[3])
        $g.DrawRectangle($pen, [single]$q[0], [single]$q[1], [single]$q[2], [single]$q[3])
    }
    $g.Dispose()
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    , $ms.ToArray()
}
$out = New-Object System.IO.MemoryStream
$bw = New-Object System.IO.BinaryWriter $out
$bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $s = $sizes[$i]; $len = $pngs[$i].Length
    $dim = if ($s -ge 256) { 0 } else { $s }
    $bw.Write([byte]$dim); $bw.Write([byte]$dim); $bw.Write([byte]0); $bw.Write([byte]0)
    $bw.Write([uint16]1); $bw.Write([uint16]32); $bw.Write([uint32]$len); $bw.Write([uint32]$offset)
    $offset += $len
}
foreach ($p in $pngs) { $bw.Write($p) }
[System.IO.File]::WriteAllBytes((Join-Path $PSScriptRoot 'src/OmniZones.App/omnizones.ico'), $out.ToArray())
Write-Host "wrote omnizones.ico"
