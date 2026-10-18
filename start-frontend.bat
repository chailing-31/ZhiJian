@echo off
setlocal
cd /d "%~dp0frontend"

where npm >nul 2>&1
if errorlevel 1 (
  echo Node.js and npm were not found. Install Node.js 18 or newer first.
  pause
  exit /b 1
)
if not exist "package.json" (
  echo Frontend package.json was not found.
  pause
  exit /b 1
)
if not exist "node_modules\vite\bin\vite.js" (
  echo Installing frontend dependencies for the first run...
  call npm.cmd install
  if errorlevel 1 (
    echo Frontend dependency installation failed.
    pause
    exit /b 1
  )
)

echo Starting Vue frontend at http://127.0.0.1:5173
call npm.cmd run dev
echo Frontend stopped.
pause
endlocal
