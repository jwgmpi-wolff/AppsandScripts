Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$taskName = 'PeopleSearchMVP_Autostart'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$launcherPath = Join-Path $projectRoot 'autostart_app.ps1'

if (-not (Test-Path $launcherPath)) {
    throw "Launcher script not found: $launcherPath"
}

$pwsh = (Get-Command pwsh -ErrorAction SilentlyContinue).Source
if (-not $pwsh) {
    $pwsh = "$env:WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe"
}

$action = New-ScheduledTaskAction -Execute $pwsh -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$launcherPath`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERNAME"
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Force | Out-Null
Write-Host "Installed startup task: $taskName"
Write-Host "It will start People Search MVP automatically when you sign in."
