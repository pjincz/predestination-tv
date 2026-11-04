$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$res = Join-Path $PSScriptRoot '../app/src/main/res'
$source = [System.Drawing.Bitmap]::FromFile((Join-Path $PSScriptRoot 'mobius-icon-source.png'))

function Save-Icon([string]$path, [int]$width, [int]$height, [int]$artSize) {
    $bitmap = [System.Drawing.Bitmap]::new($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $attributes = [System.Drawing.Imaging.ImageAttributes]::new()
    try {
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $attributes.SetWrapMode([System.Drawing.Drawing2D.WrapMode]::TileFlipXY)
        $rect = [System.Drawing.Rectangle]::new(
            [int](($width - $artSize) / 2), [int](($height - $artSize) / 2), $artSize, $artSize)
        $graphics.DrawImage($source, $rect, 0, 0, $source.Width, $source.Height,
            [System.Drawing.GraphicsUnit]::Pixel, $attributes)
        # Extend the resampled edge colors instead of adding a flat-color frame.
        $graphics.Flush()
        for ($y = $rect.Top; $y -lt $rect.Bottom; $y++) {
            $left = [System.Drawing.SolidBrush]::new($bitmap.GetPixel($rect.Left, $y))
            $right = [System.Drawing.SolidBrush]::new($bitmap.GetPixel($rect.Right - 1, $y))
            try {
                $graphics.FillRectangle($left, 0, $y, $rect.Left, 1)
                $graphics.FillRectangle($right, $rect.Right, $y, $width - $rect.Right, 1)
            } finally {
                $left.Dispose()
                $right.Dispose()
            }
        }
        $graphics.Flush()
        for ($x = 0; $x -lt $width; $x++) {
            $top = [System.Drawing.SolidBrush]::new($bitmap.GetPixel($x, $rect.Top))
            $bottom = [System.Drawing.SolidBrush]::new($bitmap.GetPixel($x, $rect.Bottom - 1))
            try {
                $graphics.FillRectangle($top, $x, 0, 1, $rect.Top)
                $graphics.FillRectangle($bottom, $x, $rect.Bottom, 1, $height - $rect.Bottom)
            } finally {
                $top.Dispose()
                $bottom.Dispose()
            }
        }
        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $attributes.Dispose()
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

try {
    foreach ($entry in @(@('mdpi', 1), @('hdpi', 1.5), @('xhdpi', 2),
                         @('xxhdpi', 3), @('xxxhdpi', 4))) {
        $directory = Join-Path $res "mipmap-$($entry[0])"
        New-Item -ItemType Directory -Force $directory | Out-Null
        $scale = $entry[1]
        Save-Icon (Join-Path $directory 'launcher_icon.png') (48*$scale) (48*$scale) (48*$scale)
        Save-Icon (Join-Path $directory 'launcher_foreground.png') (108*$scale) (108*$scale) (86*$scale)
        Save-Icon (Join-Path $directory 'tv_banner.png') (320*$scale) (180*$scale) (180*$scale)
    }
} finally {
    $source.Dispose()
}
