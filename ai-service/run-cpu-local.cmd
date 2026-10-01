@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0"
if not defined AI_PYTHON set "AI_PYTHON=D:\ZhiJianData\envs\ai-cpu\Scripts\python.exe"
if not exist "%AI_PYTHON%" (
  echo CPU Python not found. Set AI_PYTHON to your working environment.
  exit /b 1
)
"%AI_PYTHON%" --version
if errorlevel 1 exit /b 1
if not defined AI_MODEL_MANIFEST set "AI_MODEL_MANIFEST=D:\ZhiJianData\models\ssda-yolov8n-dev-v1\model-manifest.json"
if not exist "%AI_MODEL_MANIFEST%" (
  echo Model manifest not found. Set AI_MODEL_MANIFEST first.
  exit /b 1
)
set "AI_DEVICE=cpu"
if not defined AI_STORAGE_ROOT set "AI_STORAGE_ROOT=D:\ZhiJianData\ai-service\predictions"
if not defined YOLO_CONFIG_DIR set "YOLO_CONFIG_DIR=D:\ZhiJianData\ai-service\ultralytics"
if not exist "%YOLO_CONFIG_DIR%" mkdir "%YOLO_CONFIG_DIR%"
"%AI_PYTHON%" -m uvicorn inspection_service.main:create_app --factory --app-dir "%CD%" --host 127.0.0.1 --port 8001 --workers 1
exit /b %errorlevel%
