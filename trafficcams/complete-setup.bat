@echo off
REM Quick Setup Script - Combines hosts file setup + Task Scheduler registration
REM Right-click and "Run as Administrator"

setlocal enabledelayedexpansion

echo.
echo ========================================
echo  Traffic Cams - Complete Setup
echo ========================================
echo.

REM Check admin
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo ❌ This script must run as Administrator
    echo.
    echo Please:
    echo 1. Right-click this file
    echo 2. Select "Run as Administrator"
    echo.
    pause
    exit /b 1
)

echo ✅ Running with Administrator privileges
echo.

REM Setup hosts file
echo [1/3] Adding trafficcams.local to hosts file...
set HOSTS=C:\Windows\System32\drivers\etc\hosts
findstr /c:"127.0.0.1 trafficcams.local" %HOSTS% >nul 2>&1
if errorLevel 1 (
    echo 127.0.0.1 trafficcams.local >> %HOSTS%
    echo ✅ Added: 127.0.0.1 trafficcams.local
) else (
    echo ℹ️  Already in hosts file
)

echo.
echo [2/3] Installing Node.js dependencies...
cd /d "%~dp0"
if not exist node_modules (
    call npm install
)
echo ✅ Dependencies ready

echo.
echo [3/3] Registering startup task...
powershell -NoProfile -ExecutionPolicy Bypass -Command "& {.\register-startup.ps1 -Enable}"

echo.
echo ========================================
echo ✅ SETUP COMPLETE!
echo ========================================
echo.
echo Your Traffic Cams Server is now set to:
echo.
echo 🚀 Start automatically on PC boot
echo 🌐 Access at: http://trafficcams.local
echo 🔄 Refresh every 90 seconds
echo 📱 29 live traffic cameras
echo.
echo Next steps:
echo 1. Restart your computer (or wait for next reboot)
echo 2. Open browser: http://trafficcams.local
echo 3. Enjoy! 📷
echo.
pause
