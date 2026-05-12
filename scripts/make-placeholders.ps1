$ErrorActionPreference = "Stop"
# yihuan-fish/scripts -> parent is project root yihuan-fish
$root = Split-Path -Parent $PSScriptRoot
$imgDir = Join-Path $root "image"
New-Item -ItemType Directory -Force -Path $imgDir | Out-Null
Add-Type -AssemblyName System.Drawing

function Save-Png($name, [System.Drawing.Color]$color) {
    $bmp = New-Object System.Drawing.Bitmap 32, 32
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Clear($color)
    $path = Join-Path $imgDir "$name.png"
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
    Write-Host $path
}

Save-Png "yellow_marker" ([System.Drawing.Color]::FromArgb(255, 220, 40))
Save-Png "green_zone" ([System.Drawing.Color]::FromArgb(40, 180, 80))
Save-Png "success_screen" ([System.Drawing.Color]::FromArgb(200, 100, 200))
