Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$taskName = 'PeopleSearchMVP_Autostart'

$task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if (-not $task) {
    Write-Host "Startup task not found: $taskName"
    exit 0
}

Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
Write-Host "Removed startup task: $taskName"
