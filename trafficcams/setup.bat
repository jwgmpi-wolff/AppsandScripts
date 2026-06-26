@echo off
REM Traffic Cams Local Setup Script
REM This script sets up the local traffic camera viewer

echo.
echo ========================================
echo  Traffic Cams Local Server Setup
echo ========================================
echo.

REM Check if running as admin
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo ⚠️  This script needs to be run as Administrator to modify the hosts file
    echo.
    echo Please:
    echo 1. Right-click on this file
    echo 2. Select "Run as Administrator"
    echo.
    pause
    exit /b 1
)

echo ✅ Running with Administrator privileges
echo.

REM Add to hosts file
echo Adding trafficcams.local to hosts file...
setlocal enabledelayedexpansion
set HOSTS=C:\Windows\System32\drivers\etc\hosts

REM Check if entry already exists
findstr /c:"127.0.0.1 trafficcams.local" %HOSTS% >nul 2>&1
if errorLevel 1 (
    echo 127.0.0.1 trafficcams.local >> %HOSTS%
    echo ✅ Added: 127.0.0.1 trafficcams.local
) else (
    echo ℹ️  trafficcams.local already in hosts file
)

echo.
echo ✅ Setup complete!
echo.
echo Next steps:
echo 1. Open PowerShell as Administrator
echo 2. Run: npm install
echo 3. Run: npm start
echo 4. Visit: http://trafficcams.local
echo.
pause
