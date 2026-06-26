param([switch]$Elevated)

function Test-Admin {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($currentUser)
    return $principal.IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
}

if (-not (Test-Admin)) {
    Write-Host "⚠️  This script needs administrator privileges."
    Write-Host "Requesting elevation..."
    Write-Host ""
    
    $scriptPath = $PSCommandPath
    $arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$scriptPath`" -Elevated"
    
    Start-Process PowerShell -Verb RunAs -ArgumentList $arguments -Wait
    exit
}

Write-Host ""
Write-Host "╔════════════════════════════════════════╗"
Write-Host "║  Setting up trafficcams.local DNS     ║"
Write-Host "╚════════════════════════════════════════╝"
Write-Host ""

$hostsPath = "C:\Windows\System32\drivers\etc\hosts"
$entry = "127.0.0.1 trafficcams.local"

# Check if entry already exists
$content = Get-Content $hostsPath
$exists = $content -contains $entry

if ($exists) {
    Write-Host "✅ Entry already exists in hosts file:"
    Write-Host "   $entry"
} else {
    Write-Host "Adding entry to hosts file..."
    Write-Host "   $entry"
    
    try {
        Add-Content -Path $hostsPath -Value "" -Force
        Add-Content -Path $hostsPath -Value $entry -Force
        Write-Host "✅ Entry added successfully"
    } catch {
        Write-Host "❌ Failed to add entry: $_"
        exit 1
    }
}

Write-Host ""
Write-Host "Flushing DNS cache..."
try {
    ipconfig /flushdns | Out-Null
    Write-Host "✅ DNS cache flushed"
} catch {
    Write-Host "⚠️  Could not flush DNS: $_"
}

Write-Host ""
Write-Host "Testing DNS resolution..."
Start-Sleep -Seconds 2

try {
    $ip = [System.Net.Dns]::GetHostAddresses("trafficcams.local")
    Write-Host "✅ trafficcams.local resolves to: $($ip[0].IPAddressToString)"
} catch {
    Write-Host "⚠️  Still cannot resolve trafficcams.local"
    Write-Host "   Try accessing via: http://localhost instead"
}

Write-Host ""
Write-Host "╔════════════════════════════════════════╗"
Write-Host "║  🎉 Setup Complete!                  ║"
Write-Host "╚════════════════════════════════════════╝"
Write-Host ""
Write-Host "Access your traffic camera system at:"
Write-Host "  🌐 http://trafficcams.local"
Write-Host "  📊 http://trafficcams.local/dashboard.htm"
Write-Host ""
Write-Host "Or use localhost:"
Write-Host "  🌐 http://localhost"
Write-Host "  📊 http://localhost/dashboard.htm"
Write-Host ""
