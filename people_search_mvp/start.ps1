Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$project = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $project

if (-not (Test-Path '.venv')) {
    python -m venv .venv
}

.\.venv\Scripts\python.exe -m pip install -r requirements.txt
if (-not $env:APP_AUTO_PORT) {
    $env:APP_AUTO_PORT = '1'
}
.\.venv\Scripts\python.exe app.py
