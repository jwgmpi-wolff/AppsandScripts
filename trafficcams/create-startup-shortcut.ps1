# Create Windows Shortcut for Traffic Cams Startup
# This script creates a shortcut in your Startup folder as an alternative to Task Scheduler

param(
    [switch]$Startup = $false,
    [switch]$Remove = $false
)

$isAdmin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator")

if (-not $isAdmin) {
    Write-Host "⚠️  This script must be run as Administrator" -ForegroundColor Red
    Write-Host "`nRestarting as Administrator..." -ForegroundColor Yellow
    Start-Process powershell -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Startup" -Verb RunAs
    exit
}

$scriptDir = Split-Path -Parent $PSCommandPath
$startupFolder = [Environment]::GetFolderPath("Startup")
$shortcutPath = Join-Path $startupFolder "Traffic-Cams-Server.lnk"
$batchFile = Join-Path $scriptDir "start-service.bat"

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  Traffic Cams Startup Shortcut Manager" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

if ($Remove) {
    Write-Host "Removing startup shortcut..." -ForegroundColor Yellow
    if (Test-Path $shortcutPath) {
        Remove-Item $shortcutPath -Force
        Write-Host "✅ Shortcut removed" -ForegroundColor Green
    } else {
        Write-Host "ℹ️  Shortcut not found" -ForegroundColor Blue
    }
    exit
}

if ($Startup) {
    Write-Host "Creating startup shortcut..." -ForegroundColor Green
    
    try {
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($shortcutPath)
        $shortcut.TargetPath = $batchFile
        $shortcut.WorkingDirectory = $scriptDir
        $shortcut.WindowStyle = 7  # Minimized
        $shortcut.Description = "Starts the Traffic Cams Server"
        $shortcut.Save()
        
        Write-Host "✅ Shortcut created successfully!" -ForegroundColor Green
        Write-Host ""
        Write-Host "Location: $shortcutPath" -ForegroundColor Cyan
        Write-Host ""
        Write-Host "✅ The server will now start when you log in!" -ForegroundColor Green
        
    } catch {
        Write-Host "❌ Error creating shortcut: $_" -ForegroundColor Red
        exit 1
    }
    exit
}

Write-Host "Usage:" -ForegroundColor Cyan
Write-Host "  .\create-startup-shortcut.ps1 -Startup   Create startup shortcut" -ForegroundColor Green
Write-Host "  .\create-startup-shortcut.ps1 -Remove    Remove startup shortcut" -ForegroundColor Red
Write-Host ""
