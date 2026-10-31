#Requires -RunAsAdministrator
<#
  One-time setup for the Jarvis bridge on this PC. Run it as Administrator. It is safe to run again.

  What it does (and nothing else):
    1. Adds a Windows Firewall rule that lets the phone reach the bridge (TCP 8787), but ONLY from
       Tailscale addresses (100.64.0.0/10). Your home network and the internet stay blocked.
    2. Creates a scheduled task "Jarvis bridge" that starts the bridge hidden each time the normal user
       signs in, and restarts it if it stops.
    3. Starts the bridge now.

  The task runs as whoever is signed in at the screen, NOT as the administrator account running this
  script (they can be different accounts, and the bridge needs the normal user's adb and Tailscale access).

  To undo: run uninstall-bridge.ps1 (same folder) as Administrator.
#>
$ErrorActionPreference = 'Stop'
$port = 8787
$ruleName = 'Jarvis bridge (Tailscale only)'
$taskName = 'Jarvis bridge'
$bridgeDir = Split-Path -Parent $PSScriptRoot

$pythonw = (Get-Command pythonw.exe -ErrorAction Stop).Source
if (-not (Test-Path (Join-Path $bridgeDir 'jarvis_bridge\server.py'))) {
    throw "Cannot find the bridge next to this script ($bridgeDir)."
}

# Who is signed in at the screen? That is the account the bridge must run as.
$user = (Get-CimInstance Win32_ComputerSystem).UserName
if (-not $user) {
    throw 'Nobody is signed in at the screen. Sign in as the normal user, then run this again.'
}
$sid = (New-Object System.Security.Principal.NTAccount($user)).Translate([System.Security.Principal.SecurityIdentifier]).Value
$profilePath = (Get-CimInstance Win32_UserProfile | Where-Object { $_.SID -eq $sid }).LocalPath
$dataDir = Join-Path $profilePath 'AppData\Local\jarvis-assist'
Write-Host "Setting up the bridge for: $user"

# Stop any earlier copy (for example one set up for the wrong account) so the port is free.
Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='pythonw.exe'" |
    Where-Object { $_.CommandLine -match 'jarvis_bridge' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP `
    -LocalPort $port -RemoteAddress '100.64.0.0/10' -Profile Any | Out-Null
Write-Host "Firewall rule added: TCP $port from Tailscale addresses only."

$action = New-ScheduledTaskAction -Execute $pythonw -Argument '-m jarvis_bridge' -WorkingDirectory $bridgeDir
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Force | Out-Null
Write-Host "Scheduled task '$taskName' created for $user (starts at sign-in, runs hidden)."

Start-ScheduledTask -TaskName $taskName
Start-Sleep -Seconds 3
$up = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue).Count -gt 0
if ($up) { Write-Host "Bridge is running and listening on port $port." }
else { Write-Host "The bridge did not start listening yet. Wait a few seconds; if it still is not, tell Claude." }
Write-Host "Its log is at $dataDir\bridge.log"
Write-Host "The app token is in $dataDir\bridge-token (open it in Notepad; do not share it)."
