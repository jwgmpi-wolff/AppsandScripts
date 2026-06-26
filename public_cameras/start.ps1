# Public Camera Intelligence – start script
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path ".venv")) {
    Write-Host "Creating virtual environment..." -ForegroundColor Cyan
    python -m venv .venv
}

& ".venv\Scripts\Activate.ps1"
pip install -r requirements.txt --quiet

Write-Host ""
Write-Host "Starting Public Camera Intelligence on http://localhost:7000" -ForegroundColor Green
Write-Host ""

$env:FLASK_DEBUG = "0"
python app.py
