# Publishes the lab streams to MediaMTX and restarts any that stop, until Ctrl+C:
#   lab/stream1, lab/stream2    C320WS-shaped clips, looped (any number of readers)
#   tapo/stream1, tapo/stream2  the same clips on paths limited to 2 readers, like a Tapo camera
#   lab/clock                   a live clock showing this PC's time with milliseconds
# Usage:  .\publish.ps1 [-LabHost 127.0.0.1] [-Port 8554] [-User publisher] [-Password publisher-lab-pass]
param(
    [string]$LabHost = "127.0.0.1",
    [int]$Port = 8554,
    [string]$User = "publisher",
    [string]$Password = "publisher-lab-pass",
    [string]$Font = "C:/Windows/Fonts/consolab.ttf"
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
foreach ($clip in "lab-stream1", "lab-stream2") {
    if (-not (Test-Path "clips/$clip.mkv")) { throw "clips/$clip.mkv is missing; run .\make-clips.ps1 first" }
}
$base = "rtsp://$($User):$($Password)@$($LabHost):$($Port)"
$fontArg = $Font -replace ':', '\:'

# One argument string per publisher, because Start-Process doesn't quote array elements.
function Get-ClipArgs([string]$Clip, [string]$Path) {
    "-hide_banner -loglevel warning -re -stream_loop -1 -i `"clips/$Clip.mkv`" -c copy -f rtsp -rtsp_transport tcp `"$base/$Path`""
}
function Get-ClockArgs {
    # Seconds since local midnight, so %{pts:hms} shows the wall clock with milliseconds.
    $offset = (Get-Date).TimeOfDay.TotalSeconds.ToString("F3", [Globalization.CultureInfo]::InvariantCulture)
    $vf = "drawtext=fontfile='$fontArg':text='%{pts\:hms\:$offset}':x=(w-tw)/2:y=(h-th)/2:fontsize=96:fontcolor=white:box=1:boxcolor=black@0.7:boxborderw=20," +
          "drawtext=fontfile='$fontArg':text='lab clock (PC time)':x=40:y=40:fontsize=36:fontcolor=white:box=1:boxcolor=black@0.6"
    "-hide_banner -loglevel warning -re -f lavfi -i testsrc2=size=1280x720:rate=15 -vf `"$vf`" -c:v libx264 -preset veryfast -tune zerolatency -profile:v main -bf 0 -g 15 -pix_fmt yuv420p -f rtsp -rtsp_transport tcp `"$base/lab/clock`""
}

$publishers = [ordered]@{
    "lab/stream1"  = { Get-ClipArgs "lab-stream1" "lab/stream1" }
    "lab/stream2"  = { Get-ClipArgs "lab-stream2" "lab/stream2" }
    "tapo/stream1" = { Get-ClipArgs "lab-stream1" "tapo/stream1" }
    "tapo/stream2" = { Get-ClipArgs "lab-stream2" "tapo/stream2" }
    "lab/clock"    = { Get-ClockArgs }
}
$running = @{}
Write-Host "Publishing to rtsp://$($LabHost):$($Port)/{lab/stream1,lab/stream2,lab/clock,tapo/stream1,tapo/stream2}. Ctrl+C stops."
try {
    while ($true) {
        foreach ($path in $publishers.Keys) {
            $p = $running[$path]
            if ($null -eq $p -or $p.HasExited) {
                if ($null -ne $p) { Write-Host "Publisher for $path stopped (exit $($p.ExitCode)); restarting" }
                $running[$path] = Start-Process -FilePath "ffmpeg" -ArgumentList (& $publishers[$path]) -NoNewWindow -PassThru
            }
        }
        Start-Sleep -Seconds 2
    }
}
finally {
    Write-Host "Stopping publishers"
    foreach ($p in $running.Values) {
        if ($null -ne $p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue }
    }
}
