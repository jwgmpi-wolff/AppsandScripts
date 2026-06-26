@echo off

set "STARTUP=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
set "VBS_FILE=%STARTUP%\public_cameras_startup.vbs"

if exist "%VBS_FILE%" (
    del "%VBS_FILE%"
    echo Removed startup entry: %VBS_FILE%
) else (
    echo No startup entry found at: %VBS_FILE%
)
