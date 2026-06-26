Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonExe = Join-Path $projectRoot '.venv\Scripts\python.exe'

if (-not (Test-Path $pythonExe)) {
    throw "Python virtual environment not found at $pythonExe"
}

# Avoid launching duplicate instances for the same project.
$escapedRoot = [Regex]::Escape($projectRoot)
$running = Get-CimInstance Win32_Process -Filter "name = 'python.exe'" |
    Where-Object {
        $_.CommandLine -match "$escapedRoot.*app.py" -or $_.CommandLine -match "app.py.*$escapedRoot"
    }

if ($running) {
    exit 0
}

$env:APP_DEBUG = '0'
if (-not $env:PORT) {
    $env:PORT = '5000'
}

Start-Process -FilePath $pythonExe -ArgumentList 'app.py' -WorkingDirectory $projectRoot -WindowStyle Hidden
