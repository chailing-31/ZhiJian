@echo off
setlocal
cd /d "%~dp0"

where docker >nul 2>&1
if not errorlevel 1 (
  echo Starting MySQL 8 container with the project migrations...
  docker compose up mysql
  echo MySQL window ended. Press any key to close.
  pause
  endlocal
  exit /b %errorlevel%
)

echo Docker Desktop was not found. Using the installed Windows MySQL service.
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$s=Get-Service | Where-Object { $_.Name -match 'mysql' -or $_.DisplayName -match 'mysql' } | Select-Object -First 1; if (-not $s) { Write-Host 'No MySQL Windows service was found.'; exit 1 }; if ($s.Status -ne 'Running') { Start-Service -Name $s.Name; $s.WaitForStatus('Running','00:00:30') }; Write-Host ('MySQL service running: ' + $s.Name)"
if errorlevel 1 (
  echo Could not start a MySQL Windows service.
  echo Install MySQL 8 or start it manually, then double-click start-project.bat again.
  pause
  exit /b 1
)
echo Existing MySQL data was not changed.
pause
endlocal
