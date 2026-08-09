@echo off
title CamControl - Starting...
cd /d "%~dp0"

echo ============================================
echo  CamControl - YI Camera Platform
echo ============================================

:: Check Python
where python >nul 2>&1 || (
    echo [ERROR] Python not found. Run Install-CamControl.ps1 first.
    pause & exit /b 1
)

:: Install deps if needed
if not exist ".venv\Scripts\python.exe" (
    echo [Setup] Creating virtual environment...
    python -m venv .venv
    echo [Setup] Installing dependencies...
    .venv\Scripts\pip.exe install -r requirements.txt --quiet
)

:: Kill any existing instances
taskkill /f /im go2rtc.exe >nul 2>&1
for /f "tokens=5" %%a in ('netstat -ano ^| find ":8080"') do taskkill /f /pid %%a >nul 2>&1

:: Start go2rtc (background)
if exist "tools\go2rtc.exe" (
    echo [go2rtc] Starting stream proxy...
    start /b "" "tools\go2rtc.exe" -config "tools\go2rtc.yaml"
    timeout /t 2 /nobreak >nul
)

:: Start gateway
echo [Gateway] Starting on http://localhost:8080
start /b "" ".venv\Scripts\python.exe" -m camera_bridge.main

timeout /t 4 /nobreak >nul

:: Open browser
echo [Browser] Opening dashboard...
start http://localhost:8080/

echo.
echo  Dashboard: http://localhost:8080/
echo  Press Ctrl+C to stop
echo.
pause
