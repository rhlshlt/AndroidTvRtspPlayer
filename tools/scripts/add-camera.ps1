# Adds a Tapo camera to the Phase 0 app over adb, so nobody types a password with the remote.
# The password is read without echo and isn't saved in PowerShell's command history.
# Works with the debug and perf builds; start the app on the TV first (cameras live in memory).
#
# Usage:  .\tools\scripts\add-camera.ps1 -Name "Front door" -CameraHost 192.168.1.20 -User tapoview
#         [-Telephoto] [-Device 192.168.1.50:5555]
# -CameraHost is an IP address or host name; add :port when RTSP isn't on 554 (192.168.1.20:8554).
param(
    [Parameter(Mandatory = $true)] [string]$Name,
    [Parameter(Mandatory = $true)] [string]$CameraHost,
    [Parameter(Mandatory = $true)] [string]$User,
    [switch]$Telephoto,
    [string]$Device
)
$ErrorActionPreference = "Stop"

$secure = Read-Host -AsSecureString "Camera Account password for $User"
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $pass = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
if ($pass.Contains('"')) { throw "Passwords with double quotes can't be passed through adb safely; use the app's form instead." }

# adb joins its arguments into one command for the TV's shell, so quote each value for that shell.
function ConvertTo-ShellQuoted([string]$Value) { "'" + $Value.Replace("'", "'\''") + "'" }

$adbArgs = @()
if ($Device) { $adbArgs += @("-s", $Device) }
$adbArgs += @(
    "shell", "am", "broadcast",
    "-a", "io.github.rhlshlt.tvrtsp.ADD_TAPO",
    "-n", "io.github.rhlshlt.tvrtsp/.debug.AdbCameraReceiver",
    "--es", "name", (ConvertTo-ShellQuoted $Name),
    "--es", "host", (ConvertTo-ShellQuoted $CameraHost),
    "--es", "user", (ConvertTo-ShellQuoted $User),
    "--es", "pass", (ConvertTo-ShellQuoted $pass)
)
if ($Telephoto) { $adbArgs += @("--es", "lens", "telephoto") }
& adb @adbArgs
$pass = $null
