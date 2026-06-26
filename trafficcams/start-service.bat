@echo off
REM Traffic Cams - Startup Service
REM Runs the traffic camera server in the background on system startup

setlocal enabledelayedexpansion

REM Get the script directory
set SCRIPT_DIR=%~dp0

REM Change to the script directory
cd /d "%SCRIPT_DIR%"

REM Start the Node server silently in the background
if not exist node_modules (
    echo Installing dependencies...
    call npm install >nul 2>&1
)

REM Run the server with no console window (hidden)
start /b "" /min node "%SCRIPT_DIR%server.js"

REM Log the startup
echo %date% %time% - Traffic Cams Server started >> "%SCRIPT_DIR%logs\startup.log"

exit /b 0
