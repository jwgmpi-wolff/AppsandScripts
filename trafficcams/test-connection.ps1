# Quick connection test
Write-Host "Testing Traffic Camera Server..." -ForegroundColor Cyan

# Test 1: Hosts file
Write-Host "`n1. Checking hosts file..."
if (Get-Content "C:\Windows\System32\drivers\etc\hosts" | Select-String "trafficcams") {
    Write-Host "   ✓ Hosts entry found" -ForegroundColor Green
} else {
    Write-Host "   ✗ Hosts entry missing - adding..." -ForegroundColor Yellow
    Add-Content -Path "C:\Windows\System32\drivers\etc\hosts" -Value "`n127.0.0.1       trafficcams.local" -Encoding UTF8
    Write-Host "   ✓ Entry added" -ForegroundColor Green
}

# Test 2: DNS flush
Write-Host "`n2. Flushing DNS cache..."
& ipconfig /flushdns | Out-Null
Write-Host "   ✓ DNS flushed" -ForegroundColor Green

# Test 3: Local connection
Write-Host "`n3. Testing server on localhost..."
try {
    $response = Invoke-WebRequest -Uri "http://127.0.0.1/health" -TimeoutSec 3 -ErrorAction Stop
    Write-Host "   ✓ Server responding on 127.0.0.1:80" -ForegroundColor Green
} catch {
    Write-Host "   ✗ Server not responding: $($_.Exception.Message)" -ForegroundColor Red
}

# Test 4: Domain resolution
Write-Host "`n4. Testing domain resolution..."
try {
    $ip = [System.Net.Dns]::GetHostAddresses("trafficcams.local")[0]
    Write-Host "   ✓ trafficcams.local resolves to $ip" -ForegroundColor Green
    
    $response = Invoke-WebRequest -Uri "http://trafficcams.local/health" -TimeoutSec 3 -ErrorAction Stop
    Write-Host "   ✓ Server responding on trafficcams.local" -ForegroundColor Green
} catch {
    Write-Host "   ✗ Failed: $($_.Exception.Message)" -ForegroundColor Red
}

Write-Host "`n✓ Now try: http://trafficcams.local or http://localhost`n" -ForegroundColor Cyan
