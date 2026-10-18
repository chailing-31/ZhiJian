@echo off
setlocal
cd /d "%~dp0"

where docker >nul 2>&1
if errorlevel 1 (
  echo Docker Desktop was not found.
  echo Start MySQL 8 manually on port 3306, or install Docker Desktop.
  pause
  exit /b 1
)

echo Starting MySQL 8 with the project migrations...
docker compose up mysql
echo MySQL window ended. Press any key to close.
pause
endlocal
