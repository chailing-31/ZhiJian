@echo off
setlocal
cd /d "%~dp0ai-service"

if not exist "models\a15\model-manifest.json" (
  echo A15 model manifest was not found.
  pause
  exit /b 1
)
if not exist ".venv\Scripts\python.exe" (
  echo AI service dependencies are not installed.
  echo Double-click setup-dependencies.bat once, then retry.
  pause
  exit /b 1
)

echo Starting A15 AI service at http://127.0.0.1:8001
call run-a15.cmd
echo AI service stopped.
pause
endlocal
