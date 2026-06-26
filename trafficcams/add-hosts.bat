@echo off
REM Add hosts file entry with admin elevation
setlocal enabledelayedexpansion

REM Check if running as admin
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo Requesting admin privileges...
    powershell -Command "Start-Process '%0' -Verb runAs"
    exit /b
)

REM Add hosts entry
echo Adding trafficcams.local to hosts file...
echo 127.0.0.1       trafficcams.local >> C:\Windows\System32\drivers\etc\hosts

REM Flush DNS
ipconfig /flushdns

echo.
echo ✓ Hosts entry added
echo ✓ DNS cache flushed
echo.
echo Now visit: http://trafficcams.local
echo.
pause
