#Requires -RunAsAdministrator
<#
  One-time setup for Start Claude on this PC. Run it as Administrator. It is safe to run again.

  What it does (and nothing else):
    1. Adds a Windows Firewall rule that lets the phone reach this program (TCP 8788) from Tailscale
       addresses (100.64.0.0/10).
    2. Adds a second rule for the same-network fallback (the phone is at home but Tailscale is off): it lets
       devices on your own home network in, but ONLY while Windows calls that network "Private". On a
       "Public" network (a cafe, a hotel, or a home network Windows has not been told is private) it stays
       closed. Even when open, every request from the home network must carry a signature made with the
       secret in pc\data\token. To skip this, delete the second rule or set START_CLAUDE_NO_LAN=1.
    3. Creates a scheduled task "Start Claude PC" that starts the program hidden each time the normal user
       signs in, and restarts it if it stops.
    4. Starts it now.

  The task runs as whoever is signed in at the screen, NOT as the administrator account running this
  script (they can be different accounts, and the program needs the normal user's Tailscale access and the Claude login).

  To undo: run uninstall.ps1 (same folder) as Administrator.
#>
$ErrorActionPreference = 'Stop'
$port = 8788
$ruleName = 'Start Claude PC (Tailscale)'
$lanRuleName = 'Start Claude PC (home network, Private networks only)'
$taskName = 'Start Claude PC'
$pcDir = Split-Path -Parent $PSScriptRoot

$pythonw = (Get-Command pythonw.exe -ErrorAction Stop).Source
if (-not (Test-Path (Join-Path $pcDir 'start_claude_pc\server.py'))) {
    throw "Cannot find the program next to this script ($pcDir)."
}

# Who is signed in at the screen? That is the account this program must run as.
$user = (Get-CimInstance Win32_ComputerSystem).UserName
if (-not $user) {
    throw 'Nobody is signed in at the screen. Sign in as the normal user, then run this again.'
}
$dataDir = Join-Path $pcDir 'data'
Write-Host "Setting up Start Claude for: $user"

# Stop any earlier copy (for example one set up for the wrong account) so the port is free.
Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='pythonw.exe'" |
    Where-Object { $_.CommandLine -match 'start_claude_pc' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# Earlier versions named the Tailscale rule differently; clear it too.
Get-NetFirewallRule -DisplayName 'Start Claude PC (Tailscale only)' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow -Protocol TCP `
    -LocalPort $port -RemoteAddress '100.64.0.0/10' -Profile Any | Out-Null
Write-Host "Firewall rule added: TCP $port from Tailscale addresses."

Get-NetFirewallRule -DisplayName $lanRuleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName $lanRuleName -Direction Inbound -Action Allow -Protocol TCP `
    -LocalPort $port -RemoteAddress LocalSubnet -Profile Private | Out-Null
Write-Host "Firewall rule added: TCP $port from your home network, on networks Windows calls Private."

$action = New-ScheduledTaskAction -Execute $pythonw -Argument '-m start_claude_pc' -WorkingDirectory $pcDir
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
if ($up) { Write-Host "It is running and listening on port $port." }
else { Write-Host "It did not start listening yet. Wait a few seconds; if it still is not, look at the log named below." }
Write-Host "Its log is at $dataDir\pc.log"
Write-Host "The token is in $dataDir\token (open it in Notepad; do not share it)."
