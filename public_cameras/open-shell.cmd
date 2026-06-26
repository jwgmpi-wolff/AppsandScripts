@echo off
cd /d "%~dp0"

powershell -NoExit -ExecutionPolicy Bypass -Command "Set-Location -LiteralPath '%~dp0'; if (-not (Test-Path '.venv\\Scripts\\Activate.ps1')) { py -3 -m venv .venv 2>$null; if (-not (Test-Path '.venv\\Scripts\\Activate.ps1')) { python -m venv .venv } }; & '.venv\\Scripts\\Activate.ps1'; Write-Host ''; Write-Host 'Project shell ready (public_cameras).' -ForegroundColor Green"
