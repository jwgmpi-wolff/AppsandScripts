@echo off
REM Add trafficcams.local to hosts file

setlocal enabledelayedexpansion

set HOSTS_FILE=C:\Windows\System32\drivers\etc\hosts

echo.
echo ========================================
echo  Setting up trafficcams.local
echo ========================================
echo.

REM Check if already in hosts
findstr /M "trafficcams.local" "%HOSTS_FILE%" >nul 2>&1

if errorlevel 1 (
    echo Adding trafficcams.local to hosts file...
    echo.>> "%HOSTS_FILE%"
    echo 127.0.0.1 trafficcams.local >> "%HOSTS_FILE%"
    echo.
    echo ✓ Added: 127.0.0.1 trafficcams.local
) else (
    echo ✓ trafficcams.local already in hosts file
)

echo.
echo Flushing DNS cache...
ipconfig /flushdns >nul 2>&1

if errorlevel 0 (
    echo ✓ DNS cache flushed
) else (
    echo ⚠ Could not flush DNS (may need admin)
)

echo.
echo Testing resolution...
ping -n 1 trafficcams.local >nul 2>&1

if errorlevel 0 (
    echo ✓ trafficcams.local resolves successfully!
    echo.
    echo Access your server at:
    echo   http://trafficcams.local
    echo   http://trafficcams.local/dashboard.htm
) else (
    echo ⚠ Could not reach trafficcams.local
    echo   Try accessing via: http://localhost instead
)

echo.
echo ========================================
echo.
