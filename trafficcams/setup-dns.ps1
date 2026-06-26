$hostsPath = "C:\Windows\System32\drivers\etc\hosts"
$entry = "127.0.0.1       trafficcams.local"

# Check if running as admin
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator")

if (-not $isAdmin) {
    Write-Host "Restarting as Administrator..."
    $scriptPath = $PSCommandPath
    Start-Process PowerShell -ArgumentList "-File `"$scriptPath`"" -Verb RunAs
    exit
}

Write-Host "Running as Administrator..."

# Add entry if not exists
$content = Get-Content $hostsPath -Raw
if ($content -notmatch "trafficcams") {
    Add-Content -Path $hostsPath -Value "`n$entry" -Encoding UTF8
    Write-Host "✓ Added to hosts file"
} else {
    Write-Host "✓ Entry already exists"
}

# Flush DNS
& ipconfig /flushdns | Out-Null
Write-Host "✓ DNS cache flushed"

Write-Host "`n✓ Setup complete!"
Write-Host "Now visit: http://trafficcams.local`n"

Read-Host "Press Enter to close"
