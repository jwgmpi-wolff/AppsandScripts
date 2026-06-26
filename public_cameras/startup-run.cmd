@echo off
cd /d "%~dp0"

if not exist ".venv\Scripts\python.exe" (
    py -3 -m venv .venv 2>nul || python -m venv .venv
    if errorlevel 1 exit /b 1
    call ".venv\Scripts\activate.bat"
    pip install -r requirements.txt --quiet
)

set FLASK_DEBUG=0
".venv\Scripts\python.exe" app.py >> "server.log" 2>> "server.err"
