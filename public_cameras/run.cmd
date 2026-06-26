@echo off
cd /d "%~dp0"

if not exist ".venv" (
    echo Creating virtual environment...
    python -m venv .venv
)

call .venv\Scripts\activate.bat
pip install -r requirements.txt --quiet

echo.
echo Starting Public Camera Intelligence on http://localhost:7000
echo.
set FLASK_DEBUG=0
python app.py
