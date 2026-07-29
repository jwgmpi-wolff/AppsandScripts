@echo off
cd /d "%~dp0"
if not exist .venv python -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements.txt
if "%APP_AUTO_PORT%"=="" set APP_AUTO_PORT=1
.venv\Scripts\python.exe app.py
