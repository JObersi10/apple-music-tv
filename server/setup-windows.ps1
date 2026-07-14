# One-shot setup for the Apple Music TV proxy server on Windows.
# Installs Bun, Python, ffmpeg (via winget) and Bento4 mp4decrypt (direct
# download), creates a Python venv, and writes server\.env with resolved paths.
#
# Run in PowerShell:  powershell -ExecutionPolicy Bypass -File .\setup-windows.ps1
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "==> Apple Music TV - server setup (Windows)"

function Ensure-Winget($id) {
  Write-Host "--> Installing $id ..."
  winget install --id $id -e --accept-source-agreements --accept-package-agreements 2>$null
}

# 1. Bun, Python, ffmpeg
Ensure-Winget "Oven-sh.Bun"
Ensure-Winget "Python.Python.3.12"
Ensure-Winget "Gyan.FFmpeg"

# Refresh PATH for this session
$env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" +
            [System.Environment]::GetEnvironmentVariable("Path","User")

# 2. Bento4 mp4decrypt (prebuilt Windows binary)
$b4dir = Join-Path $PSScriptRoot ".bento4"
$mp4 = Join-Path $b4dir "mp4decrypt.exe"
if (-not (Test-Path $mp4)) {
  Write-Host "--> Downloading Bento4 mp4decrypt ..."
  $zip = Join-Path $env:TEMP "bento4.zip"
  Invoke-WebRequest "https://www.bok.net/Bento4/binaries/Bento4-SDK-1-6-0-641.x86_64-microsoft-win32.zip" -OutFile $zip
  $ex = Join-Path $env:TEMP "bento4"
  Expand-Archive $zip -DestinationPath $ex -Force
  New-Item -ItemType Directory -Force -Path $b4dir | Out-Null
  Copy-Item ((Get-ChildItem "$ex\*\bin\mp4decrypt.exe" -Recurse | Select-Object -First 1).FullName) $mp4
}

# 3. Python venv + decrypt deps
Write-Host "--> Creating Python venv and installing gamdl + pywidevine ..."
python -m venv .venv
& ".\.venv\Scripts\python.exe" -m pip install --upgrade pip | Out-Null
& ".\.venv\Scripts\python.exe" -m pip install gamdl pywidevine httpx

# 4. Resolve paths and write .env
$py = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
$ffmpeg = (Get-Command ffmpeg -ErrorAction SilentlyContinue).Source
if (-not $ffmpeg) { $ffmpeg = "ffmpeg" }  # fall back to PATH; reopen terminal if needed

@"
GAMDL_SITE=
PYTHON_BIN=$py
MP4DECRYPT_BIN=$mp4
FFMPEG_BIN=$ffmpeg
"@ | Set-Content -Encoding ASCII .env
Write-Host "--> Wrote server\.env"

# 5. Bun deps
Write-Host "--> Installing server dependencies ..."
bun install

Write-Host ""
Write-Host "==> Setup complete!"
Write-Host ""
Write-Host "--- Music User Token (optional, needed for library/full streams) ---"
Write-Host "1. Open music.apple.com in your browser"
Write-Host "2. DevTools -> Network tab -> click anything -> find a request to amp-api-edge.music.apple.com"
Write-Host "3. Copy the Music-User-Token request header value"
Write-Host ""
$MUT = Read-Host "Paste your Music-User-Token (or press Enter to skip)"
if ($MUT) {
    $stateFile = Join-Path $PSScriptRoot "auth-state.json"
    $state = @{ mut = ""; bearerToken = ""; mutSetAt = 0 }
    if (Test-Path $stateFile) {
        try { $state = Get-Content $stateFile | ConvertFrom-Json -AsHashtable } catch {}
    }
    $state["mut"] = $MUT
    $state | ConvertTo-Json | Set-Content -Encoding ASCII $stateFile
    Write-Host "--> Saved MUT to auth-state.json"
}

Write-Host ""
Write-Host "Start the server with:"
Write-Host "      cd server; bun run src/index.ts"
Write-Host "(If ffmpeg was just installed, reopen PowerShell first so it's on PATH.)"
