# Traffic Cams Local Server Startup Script
# This script sets up hosts file and starts the server

param(
    [switch]$SkipSetup = $false
)

Write-Host "`n" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Traffic Cams Local Server Launcher" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "`n"

# Check if running as admin
$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator")

if (-not $isAdmin) {
    Write-Host "⚠️  This script needs Administrator privileges to modify the hosts file" -ForegroundColor Yellow
    Write-Host "`nRestarting as Administrator..." -ForegroundColor Yellow
    Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -Verb RunAs
    exit
}

# Setup hosts file
if (-not $SkipSetup) {
    Write-Host "🔧 Setting up hosts file..." -ForegroundColor Cyan
    
    $hostsPath = "C:\Windows\System32\drivers\etc\hosts"
    $entry = "127.0.0.1 trafficcams.local"
    
    # Check if entry exists
    $hostsContent = Get-Content $hostsPath
    
    if ($hostsContent -notcontains $entry) {
        Add-Content -Path $hostsPath -Value "`n$entry"
        Write-Host "✅ Added to hosts file: $entry" -ForegroundColor Green
    } else {
        Write-Host "ℹ️  Entry already exists in hosts file" -ForegroundColor Blue
    }
    Write-Host ""
}

# Check if Node.js is installed
Write-Host "🔍 Checking for Node.js..." -ForegroundColor Cyan
$nodeVersion = node --version 2>$null

if ($LASTEXITCODE -ne 0) {
    Write-Host "❌ Node.js is not installed" -ForegroundColor Red
    Write-Host "Please install Node.js from https://nodejs.org/" -ForegroundColor Yellow
    Write-Host "`nThen run this script again`n"
    pause
    exit 1
}

Write-Host "✅ Node.js $nodeVersion found" -ForegroundColor Green
Write-Host ""

# Install dependencies if needed
Write-Host "📦 Checking dependencies..." -ForegroundColor Cyan
$projectRoot = Split-Path -Parent $PSCommandPath

if (-not (Test-Path "$projectRoot\node_modules")) {
    Write-Host "Installing npm dependencies..." -ForegroundColor Yellow
    Set-Location $projectRoot
    npm install
    Write-Host "✅ Dependencies installed" -ForegroundColor Green
} else {
    Write-Host "✅ Dependencies already installed" -ForegroundColor Green
}

Write-Host ""
Write-Host "🚀 Starting server..." -ForegroundColor Green
Write-Host "`nYour traffic camera viewer is now running!" -ForegroundColor Green
Write-Host "📍 Access at: http://trafficcams.local" -ForegroundColor Cyan
Write-Host "🔗 Or direct: http://localhost" -ForegroundColor Cyan
Write-Host "`nPress Ctrl+C to stop the server`n" -ForegroundColor Yellow

Set-Location $projectRoot
npm start
