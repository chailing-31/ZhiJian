@echo off
setlocal
cd /d "%~dp0"
if errorlevel 1 exit /b 1
if not exist "D:\Projects" (
  echo D:\Projects is missing. This helper is for the agreed Windows D-drive layout.
  exit /b 1
)
if not exist "D:\ZhiJianData\ai-service\tmp" mkdir "D:\ZhiJianData\ai-service\tmp"
if errorlevel 1 exit /b 1
if not exist "D:\Caches\pip" mkdir "D:\Caches\pip"
if errorlevel 1 exit /b 1
set "TEMP=D:\ZhiJianData\ai-service\tmp"
set "TMP=D:\ZhiJianData\ai-service\tmp"
set "PIP_CACHE_DIR=D:\Caches\pip"
python --version
if errorlevel 1 exit /b 1
if not exist ".venv\Scripts\python.exe" python -m venv .venv
if errorlevel 1 exit /b 1
".venv\Scripts\python.exe" -m pip install --cache-dir "D:\Caches\pip" -r requirements-service.txt
if errorlevel 1 (
  echo Installation failed. Do not delete your project or database. Copy the final error lines.
  exit /b 1
)
echo Service dependencies installed. No torch, weights or database changes were requested.
echo Next: run-service.cmd
endlocal
