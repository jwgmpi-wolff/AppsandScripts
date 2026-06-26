@echo off
cd /d "%~dp0"

set "STARTUP=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup"
set "VBS_FILE=%STARTUP%\public_cameras_startup.vbs"

if not exist "%STARTUP%" (
    echo Startup folder not found: %STARTUP%
    exit /b 1
)

> "%VBS_FILE%" echo Set WshShell = CreateObject("WScript.Shell")
>> "%VBS_FILE%" echo WshShell.CurrentDirectory = "%~dp0"
>> "%VBS_FILE%" echo WshShell.Run Chr(34) ^& "%~dp0startup-run.cmd" ^& Chr(34), 0
>> "%VBS_FILE%" echo Set WshShell = Nothing

echo Installed startup entry:
echo %VBS_FILE%
echo public_cameras will auto-start on Windows sign-in.
