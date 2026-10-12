@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0"
rem Environment override, repository venv, existing A machine venv, then PATH.
if not defined AI_PYTHON if exist "%~dp0.venv\Scripts\python.exe" set "AI_PYTHON=%~dp0.venv\Scripts\python.exe"
if not defined AI_PYTHON if exist "D:\ZhiJianData\envs\ai-cpu\Scripts\python.exe" set "AI_PYTHON=D:\ZhiJianData\envs\ai-cpu\Scripts\python.exe"
if not defined AI_PYTHON set "AI_PYTHON=python"
"%AI_PYTHON%" --version
if errorlevel 1 (
  echo Python is unavailable. Set AI_PYTHON to a working Python executable.
  exit /b 1
)
if not defined AI_MODEL_MANIFEST set "AI_MODEL_MANIFEST=%~dp0models\a15\model-manifest.json"
if not exist "%AI_MODEL_MANIFEST%" (
  echo Model manifest not found. See docs/AI_HANDOFF_A15.md.
  exit /b 1
)
rem Preserve existing evidence storage; use repository-local runtime on new machines.
set "AI_DEVICE=cpu"
if not defined AI_STORAGE_ROOT if exist "D:\ZhiJianData\ai-service\predictions" set "AI_STORAGE_ROOT=D:\ZhiJianData\ai-service\predictions"
if not defined AI_STORAGE_ROOT set "AI_STORAGE_ROOT=%~dp0runtime\predictions"
if not defined YOLO_CONFIG_DIR set "YOLO_CONFIG_DIR=%~dp0runtime\ultralytics"
if not exist "%YOLO_CONFIG_DIR%" mkdir "%YOLO_CONFIG_DIR%"
if not exist "%YOLO_CONFIG_DIR%" exit /b 1
echo AI_MODEL_MANIFEST="%AI_MODEL_MANIFEST%"
echo AI_STORAGE_ROOT="%AI_STORAGE_ROOT%"
echo Local CPU service only. No model-accuracy certification.
"%AI_PYTHON%" -m uvicorn inspection_service.main:create_app --factory --app-dir "%CD%" --host 127.0.0.1 --port 8001 --workers 1
exit /b %errorlevel%
