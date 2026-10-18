@echo off
setlocal
cd /d "%~dp0"
set "ROOT=%~dp0"

echo Starting ZhiJian local demo windows...
echo If this is the first run, double-click setup-dependencies.bat first.

start "ZhiJian MySQL" cmd /k call "%ROOT%start-database.bat"
echo Waiting for MySQL on port 3306...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ok=$false; 1..60 | %% { try { $c=New-Object Net.Sockets.TcpClient; $c.Connect('127.0.0.1',3306); $c.Close(); $ok=$true; break } catch { Start-Sleep -Seconds 1 } }; if (-not $ok) { exit 1 }"
if errorlevel 1 echo MySQL is not ready yet; the backend window may need a restart after MySQL finishes.
start "ZhiJian Business 8002" cmd /k call "%ROOT%start-business.bat"
timeout /t 2 /nobreak >nul
start "ZhiJian Backend 8080" cmd /k call "%ROOT%start-backend.bat"
timeout /t 2 /nobreak >nul
start "ZhiJian Frontend 5173" cmd /k call "%ROOT%start-frontend.bat"

echo Services are opening in separate windows.
echo The AI service is optional. Use start-ai.bat when its dependencies are ready.
timeout /t 8 /nobreak >nul
start "" http://127.0.0.1:5173/
endlocal
