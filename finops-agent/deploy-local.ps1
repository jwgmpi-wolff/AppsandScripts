param(
    [switch]$SkipInstall,
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Test-Path '.\.venv\Scripts\python.exe')) {
    Write-Host 'Creating virtual environment...'
    python -m venv .venv
}

$python = '.\.venv\Scripts\python.exe'

if (-not $SkipInstall) {
    Write-Host 'Installing dependencies...'
    & $python -m pip install --upgrade pip
    & $python -m pip install -r requirements.txt
}

if (-not (Test-Path '.\local.settings.json')) {
    if (Test-Path '.\local.settings.sample.json') {
        Copy-Item '.\local.settings.sample.json' '.\local.settings.json'
        Write-Host 'Created local.settings.json from sample.'
    } else {
        throw 'local.settings.sample.json was not found.'
    }
}

Write-Host 'Validating function_app.py...'
& $python -m py_compile .\function_app.py

if ($NoStart) {
    Write-Host 'Local environment is ready. Start manually with: func start'
    exit 0
}

Write-Host 'Starting Azure Functions host...'
func start
