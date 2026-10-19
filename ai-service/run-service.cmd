@echo off
setlocal
cd /d "%~dp0"
if not exist ".venv\Scripts\python.exe" (
  echo Run setup-service.cmd first.
  exit /b 1
)
if not defined AI_STORAGE_ROOT set "AI_STORAGE_ROOT=D:\ZhiJianData\ai-service\predictions"
if not defined AI_DEVICE set "AI_DEVICE=cpu"
if not exist "D:\ZhiJianData\ai-service\tmp" mkdir "D:\ZhiJianData\ai-service\tmp"
if errorlevel 1 exit /b 1
set "TEMP=D:\ZhiJianData\ai-service\tmp"
set "TMP=D:\ZhiJianData\ai-service\tmp"
set "YOLO_CONFIG_DIR=D:\ZhiJianData\ai-service\ultralytics"
echo Starting internal AI service at http://127.0.0.1:8001
echo This does not enable AI in the web UI. Model-not-ready is expected without a manifest.
".venv\Scripts\python.exe" -m uvicorn inspection_service.main:create_app --factory --host 127.0.0.1 --port 8001 --workers 1
endlocal
