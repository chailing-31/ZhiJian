@echo off
setlocal
cd /d "%~dp0"
set "BUSINESS_ROOT=%~dp0..\ZhiJian-develop\business"
set "BUSINESS_PYTHON=%BUSINESS_ROOT%\.venv\Scripts\python.exe"

if not exist "%BUSINESS_ROOT%\app\main.py" (
  echo B service source was not found:
  echo %BUSINESS_ROOT%
  pause
  exit /b 1
)
if not exist "%BUSINESS_PYTHON%" (
  echo B service dependencies are not installed.
  echo Double-click setup-dependencies.bat once, then retry.
  pause
  exit /b 1
)

cd /d "%BUSINESS_ROOT%"
echo Starting B rule service at http://127.0.0.1:8002
"%BUSINESS_PYTHON%" -m uvicorn app.main:app --host 127.0.0.1 --port 8002
echo B service stopped.
pause
endlocal
