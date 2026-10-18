@echo off
setlocal
cd /d "%~dp0backend"

if not defined DB_HOST set "DB_HOST=127.0.0.1"
if not defined DB_PORT set "DB_PORT=3306"
if not defined DB_NAME set "DB_NAME=smart_fresh_demo"
if not defined DB_USER set "DB_USER=demo"
if not defined DB_PASSWORD set "DB_PASSWORD=local_demo_password"
if not defined BUSINESS_SERVICE_BASE_URL set "BUSINESS_SERVICE_BASE_URL=http://127.0.0.1:8002"

if not exist "mvnw.cmd" (
  echo Maven wrapper was not found in the backend folder.
  pause
  exit /b 1
)

echo Starting Spring Boot backend at http://127.0.0.1:8080
echo Database: %DB_HOST%:%DB_PORT%/%DB_NAME%
call mvnw.cmd spring-boot:run
echo Backend stopped.
pause
endlocal
