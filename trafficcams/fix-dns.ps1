$hostsPath = "C:\Windows\System32\drivers\etc\hosts"
$entry = "127.0.0.1 trafficcams.local"

# Check admin
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator")

if (-not $isAdmin) {
    Write-Host "⚠️  Restarting as Administrator..." -ForegroundColor Yellow
    Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -Verb RunAs
    exit
}

Write-Host ""
Write-Host "🔧 Fixing DNS Resolution..." -ForegroundColor Cyan
Write-Host ""

# Read hosts file
$hostsContent = @(Get-Content $hostsPath)

# Check if entry exists
if ($hostsContent -contains $entry) {
    Write-Host "✅ Entry already exists in hosts file" -ForegroundColor Green
} else {
    # Add entry
    Add-Content -Path $hostsPath -Value "`n$entry" -Encoding ASCII
    Write-Host "✅ Added to hosts file: $entry" -ForegroundColor Green
}

# Flush DNS cache
Write-Host ""
Write-Host "🔄 Flushing DNS cache..." -ForegroundColor Cyan
ipconfig /flushdns | Out-Null
Write-Host "✅ DNS cache flushed" -ForegroundColor Green

# Verify entry
Write-Host ""
Write-Host "✓ Verifying hosts file..." -ForegroundColor Cyan
Get-Content $hostsPath | Select-String "trafficcams.local"
Write-Host ""

# Check if server is running
Write-Host "🔍 Checking if server is running..." -ForegroundColor Cyan
try {
    $response = Invoke-WebRequest -Uri "http://localhost/health" -ErrorAction Stop
    Write-Host "✅ Server is online!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Now try: http://trafficcams.local" -ForegroundColor Green
} catch {
    Write-Host "⚠️  Server is not running yet" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "To start the server, run:" -ForegroundColor Yellow
    Write-Host "  npm install" -ForegroundColor White
    Write-Host "  npm start" -ForegroundColor White
    Write-Host ""
    Write-Host "Or use the startup scripts:" -ForegroundColor Yellow
    Write-Host "  .\start.ps1" -ForegroundColor White
}

Write-Host ""
pause
