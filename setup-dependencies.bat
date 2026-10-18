@echo off
setlocal
cd /d "%~dp0"
set "ROOT=%~dp0"
set "PYTHON_CMD="

where python >nul 2>&1
if not errorlevel 1 set "PYTHON_CMD=python"
if not defined PYTHON_CMD (
  where py >nul 2>&1
  if not errorlevel 1 set "PYTHON_CMD=py -3"
)
if not defined PYTHON_CMD (
  echo Python 3.12 or newer was not found.
  pause
  exit /b 1
)
where npm >nul 2>&1
if errorlevel 1 (
  echo Node.js and npm were not found.
  pause
  exit /b 1
)

echo [1/3] Installing frontend dependencies...
cd /d "%ROOT%frontend"
call npm.cmd install
if errorlevel 1 goto :failed

echo [2/3] Preparing B service dependencies...
set "BUSINESS_ROOT=%ROOT%..\ZhiJian-develop\business"
if not exist "%BUSINESS_ROOT%\app\main.py" (
  echo B service source was not found: %BUSINESS_ROOT%
  goto :failed
)
if not exist "%BUSINESS_ROOT%\.venv\Scripts\python.exe" (
  cd /d "%BUSINESS_ROOT%"
  %PYTHON_CMD% -m venv .venv
  if errorlevel 1 goto :failed
)
"%BUSINESS_ROOT%\.venv\Scripts\python.exe" -m pip install -r "%BUSINESS_ROOT%\requirements-dev.txt"
if errorlevel 1 goto :failed

echo [3/3] Preparing AI service dependencies...
cd /d "%ROOT%ai-service"
if not exist ".venv\Scripts\python.exe" (
  %PYTHON_CMD% -m venv .venv
  if errorlevel 1 goto :failed
)
"%ROOT%ai-service\.venv\Scripts\python.exe" -m pip install -r "%ROOT%ai-service\requirements-service.txt"
if errorlevel 1 goto :failed

echo.
echo Dependency setup completed.
echo Now double-click start-project.bat.
pause
exit /b 0

:failed
echo.
echo Dependency setup failed. Check the error above and try again.
pause
exit /b 1
