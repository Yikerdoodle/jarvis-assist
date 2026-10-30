#Requires -RunAsAdministrator
<#
  One-time setup for the Jarvis bridge on this PC. Run it once, as Administrator.

  What it does (and nothing else):
    1. Adds a Windows Firewall rule that lets the phone reach the bridge (TCP 8787), but ONLY from
       Tailscale addresses (100.64.0.0/10). Your home network and the internet stay blocked.
    2. Creates a scheduled task "Jarvis bridge" that starts the bridge hidden each time you log in,
       and restarts it if it stops.
    3. Starts the bridge now.

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

Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP `
    -LocalPort $port -RemoteAddress '100.64.0.0/10' -Profile Any | Out-Null
Write-Host "Firewall rule added: TCP $port from Tailscale addresses only."

$action = New-ScheduledTaskAction -Execute $pythonw -Argument '-m jarvis_bridge' -WorkingDirectory $bridgeDir
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
    -Principal $principal -Force | Out-Null
Write-Host "Scheduled task '$taskName' created (starts at logon, runs hidden)."

Start-ScheduledTask -TaskName $taskName
Write-Host "Bridge started. Its log is at $env:LOCALAPPDATA\jarvis-assist\bridge.log"
Write-Host "The app token is in $env:LOCALAPPDATA\jarvis-assist\bridge-token (open it in Notepad; do not share it)."
