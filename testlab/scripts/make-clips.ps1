# Makes looping test clips shaped like the Tapo C320WS streams (docs/TEST_LAB.md):
#   clips/lab-stream1.mkv  2560x1440, 15 fps, H.264 Main Level 5.0, 1 reference frame, no B-frames
#   clips/lab-stream2.mkv  640x360,  15 fps, H.264 Main Level 2.2, 1 reference frame, no B-frames
# Both: keyframe every 2 s (adjust -KeyInt after measuring your camera), SPS/PPS before every
# keyframe, G.711 A-law 8 kHz mono audio.
# Needs ffmpeg.exe (with libx264) on PATH.  Usage:  .\make-clips.ps1 [-Duration 120] [-KeyInt 30]
param(
    [int]$Duration = 120,
    [int]$KeyInt = 30,
    [string]$Font = "C:/Windows/Fonts/arialbd.ttf"
)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
New-Item -ItemType Directory -Force -Path "clips" | Out-Null
# FFmpeg filter syntax needs the drive colon escaped: C\:/Windows/Fonts/arialbd.ttf
$fontArg = $Font -replace ':', '\:'

function New-LabClip([string]$Name, [string]$Size, [string]$Level, [string]$Rate, [int]$FontSize) {
    Write-Host "Encoding clips/$Name.mkv ($Size, Level $Level, $($Duration)s)..."
    $vf = "drawtext=fontfile='$fontArg':text='$Name $Size 15 fps  frame %{frame_num}':x=20:y=20:fontsize=$($FontSize):fontcolor=white:box=1:boxcolor=black@0.6"
    & ffmpeg -hide_banner -loglevel error -y `
        -f lavfi -i "testsrc2=size=$($Size):rate=15" `
        -f lavfi -i "sine=frequency=1000:sample_rate=8000" `
        -t $Duration -vf $vf `
        -c:v libx264 -preset veryfast -profile:v main -level:v $Level -pix_fmt yuv420p `
        -bf 0 -refs 1 -g $KeyInt -keyint_min $KeyInt -sc_threshold 0 `
        -b:v $Rate -maxrate $Rate -bufsize $Rate -x264-params repeat-headers=1 `
        -c:a pcm_alaw -ar 8000 -ac 1 "clips/$Name.mkv"
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed while encoding $Name" }
}

New-LabClip "lab-stream1" "2560x1440" "5.0" "3M" 48
New-LabClip "lab-stream2" "640x360" "2.2" "512k" 18
foreach ($clip in "lab-stream1", "lab-stream2") {
    & ffprobe -v error -show_entries stream=codec_name,profile,level,width,height,r_frame_rate,has_b_frames,refs,sample_rate -of compact "clips/$clip.mkv"
}
Write-Host "Done. Next: .\publish.ps1"
