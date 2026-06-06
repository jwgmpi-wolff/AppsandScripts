 <#
.SYNOPSIS
  Runs the Python MCP client script using the project venv (preferred).

.DESCRIPTION
  - Prefers .venv\Scripts\python.exe if present
  - Otherwise tries: py -3.11, then python
  - Requires OPENWEATHER_API_KEY (env var) unless passed as -ApiKey
  - Runs with stable working directory set to the script folder
#>

[CmdletBinding()]
param(
    # Path to the Python script that contains your asyncio.run(main()) client code
    [Parameter(Mandatory = $false)]
    [string]$ClientScript = "test_client.py",

    # Optional: provide API key explicitly (otherwise uses process env var)
    [Parameter(Mandatory = $false)]
    [string]$ApiKey,

    # Optional: city override if your Python script supports reading it (see note below)
    [Parameter(Mandatory = $false)]
    [string]$City
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Resolve project folder = folder containing this .ps1
$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -Path $ProjectDir

# ---- API key handling ----
if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    $ApiKey = '8d29a94eae8596196e52ac030957b00b'
}

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    throw "OPENWEATHER_API_KEY is not set. Either set it in the environment or pass -ApiKey."
}

# Ensure the env var is set for the child process
$env:OPENWEATHER_API_KEY = $ApiKey

# ---- Find Python interpreter (prefer venv) ----
$VenvPython = Join-Path $ProjectDir ".venv\Scripts\python.exe"
$PythonExe = $null
$PythonArgsPrefix = @()

if (Test-Path $VenvPython) {
    $PythonExe = $VenvPython
}
else {
    # Try Windows launcher with 3.11 (adjust if needed)
    $py = Get-Command py -ErrorAction SilentlyContinue
    if ($py) {
        $PythonExe = "py"
        $PythonArgsPrefix = @("-3.11")
    }
    else {
        $python = Get-Command python -ErrorAction SilentlyContinue
        if ($python) {
            $PythonExe = "python"
        }
    }
}

if (-not $PythonExe) {
    throw "No Python found. Install Python or create a venv at $VenvPython."
}

# ---- Resolve client script path ----
$ClientPath = Join-Path $ProjectDir $ClientScript
if (-not (Test-Path $ClientPath)) {
    throw "Client script not found: $ClientPath"
}

# ---- Optional: pass city as an argument (only works if your Python script reads argv) ----
# If you update your Python client to accept a city arg, this will pass it through.
$PythonArgs = @()
$PythonArgs += $PythonArgsPrefix
$PythonArgs += @($ClientPath)

if (-not [string]::IsNullOrWhiteSpace($City)) {
    $PythonArgs += @("--city", $City)
}

Write-Host "ProjectDir : $ProjectDir"
Write-Host "Python     : $PythonExe $($PythonArgs -join ' ')"
Write-Host "API Key    : (set in env for child process)"

# ---- Run ----
& $PythonExe @PythonArgs
$exit = $LASTEXITCODE

if ($exit -ne 0) {
    Write-Error "Python exited with code $exit"
}

exit $exit