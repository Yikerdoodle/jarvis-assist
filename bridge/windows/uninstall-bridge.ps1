#Requires -RunAsAdministrator
<# Undoes install-bridge.ps1: removes the firewall rule and the scheduled task, and stops the bridge.
   It does not delete the token or the log. #>
$ErrorActionPreference = 'SilentlyContinue'
Stop-ScheduledTask -TaskName 'Jarvis bridge'
Unregister-ScheduledTask -TaskName 'Jarvis bridge' -Confirm:$false
Stop-ScheduledTask -TaskName 'Jarvis bridge (njk)'
Unregister-ScheduledTask -TaskName 'Jarvis bridge (njk)' -Confirm:$false
Get-NetFirewallRule -DisplayName 'Jarvis bridge (Tailscale only)' | Remove-NetFirewallRule
Get-CimInstance Win32_Process -Filter "Name='pythonw.exe'" |
    Where-Object { $_.CommandLine -match 'jarvis_bridge' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Write-Host 'Removed the firewall rule and scheduled task, and stopped the bridge.'
