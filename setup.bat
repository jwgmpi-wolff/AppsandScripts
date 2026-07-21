@echo off
REM Quick start script for FinOps API (Windows)

cls
echo ==================================
echo   Azure FinOps Reporting API
echo   Quick Start Setup (Windows)
echo ==================================
echo.

REM Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python not found
    echo Please install Python 3.10+ from https://www.python.org
    exit /b 1
)

for /f "tokens=*" %%i in ('python --version') do set PYTHON_VERSION=%%i
echo ✓ %PYTHON_VERSION% detected

REM Create virtual environment
if not exist "venv" (
    echo Creating virtual environment...
    python -m venv venv
)

REM Activate venv
call venv\Scripts\activate.bat

echo ✓ Virtual environment activated

REM Install dependencies
echo Installing dependencies...
pip install -q -r requirements.txt
echo ✓ Dependencies installed

REM Check Azure auth
echo.
echo Checking Azure authentication...
az account show >nul 2>&1
if errorlevel 1 (
    echo ⚠ Not logged into Azure CLI
    echo   Run: az login
    echo   Then: az account set --subscription ^<subscription-id^>
) else (
    for /f "tokens=*" %%i in ('az account show -o tsv --query id') do set CURRENT_SUB=%%i
    echo ✓ Logged in to subscription: %CURRENT_SUB%
)

REM Setup environment
if not exist ".env" (
    echo.
    echo Creating .env file from template...
    copy .env.example .env
    echo ⚠ Please edit .env with your AZURE_SUBSCRIPTION_ID
)

REM Create tests directory
if not exist "tests" mkdir tests
type nul > tests\__init__.py

echo.
echo ✓ Setup complete!
echo.
echo Next steps:
echo   1. Edit .env with your AZURE_SUBSCRIPTION_ID
echo   2. Run examples: python examples.py
echo   3. Start API:   uvicorn dashboard_api:app --reload
echo   4. Visit docs:  http://localhost:8000/docs
echo.
