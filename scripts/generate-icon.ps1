# Regenerate the small native Windows icon without image libraries or network access.
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
Add-Type 'using System; using System.Runtime.InteropServices; public class LocalDropIconHandle { [DllImport("user32.dll")] public static extern bool DestroyIcon(IntPtr icon); }'
$bitmap=[Drawing.Bitmap]::new(32,32)
$graphics=[Drawing.Graphics]::FromImage($bitmap)
$brush=[Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(247,132,92))
$pen=[Drawing.Pen]::new([Drawing.Color]::FromArgb(25,28,27),[single]2.2)
$iconHandle=[IntPtr]::Zero
try {
 $graphics.SmoothingMode=[Drawing.Drawing2D.SmoothingMode]::AntiAlias
 $graphics.Clear([Drawing.Color]::Transparent)
 $path=[Drawing.Drawing2D.GraphicsPath]::new()
 try {
  $path.AddArc(0,0,6,6,180,90);$path.AddArc(25,0,6,6,270,90)
  $path.AddArc(25,25,6,6,0,90);$path.AddArc(0,25,6,6,90,90);$path.CloseFigure()
  $graphics.FillPath($brush,$path)
 } finally {$path.Dispose()}
 foreach($segment in @(@(18,6.7,25.3,6.7),@(25.3,6.7,25.3,14),@(25.3,6.7,16.7,15.3),@(14,25.3,6.7,25.3),@(6.7,25.3,6.7,18),@(6.7,25.3,15.3,16.7))){
  $graphics.DrawLine($pen,[single]$segment[0],[single]$segment[1],[single]$segment[2],[single]$segment[3])
 }
 $iconHandle=$bitmap.GetHicon()
 $icon=[Drawing.Icon]::FromHandle($iconHandle)
 $stream=[IO.File]::Create((Join-Path $PSScriptRoot '..\desktop\icons\icon.ico'))
 try {$icon.Save($stream)} finally {$stream.Dispose();$icon.Dispose()}
} finally {
 if($iconHandle -ne [IntPtr]::Zero){[LocalDropIconHandle]::DestroyIcon($iconHandle)|Out-Null}
 $pen.Dispose();$brush.Dispose();$graphics.Dispose();$bitmap.Dispose()
}
