#Requires -RunAsAdministrator
<# Undoes install.ps1: removes the firewall rules and the scheduled task, and stops the program.
   It does not delete the token, launcher.json or the logs. #>
$ErrorActionPreference = 'SilentlyContinue'
Stop-ScheduledTask -TaskName 'Start Claude PC'
Unregister-ScheduledTask -TaskName 'Start Claude PC' -Confirm:$false
Get-NetFirewallRule -DisplayName 'Start Claude PC (Tailscale)' | Remove-NetFirewallRule
Get-NetFirewallRule -DisplayName 'Start Claude PC (Tailscale only)' | Remove-NetFirewallRule
Get-NetFirewallRule -DisplayName 'Start Claude PC (home network, Private networks only)' | Remove-NetFirewallRule
Get-CimInstance Win32_Process -Filter "Name='pythonw.exe'" |
    Where-Object { $_.CommandLine -match 'start_claude_pc' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Write-Host 'Removed the firewall rules and scheduled task, and stopped the program.'
