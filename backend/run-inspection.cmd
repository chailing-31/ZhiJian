@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0"
if not defined DB_USER (
  echo Set DB_USER in this CMD first. Do not paste your password into chat.
  exit /b 1
)
if not defined DB_PASSWORD (
  echo Set DB_PASSWORD in this CMD first. This script does not store credentials.
  exit /b 1
)
if not defined AI_SERVICE_BASE_URL set "AI_SERVICE_BASE_URL=http://127.0.0.1:8001"
if not defined INSPECTION_STORAGE_ROOT set "INSPECTION_STORAGE_ROOT=D:\ZhiJianData\backend\inspection-artifacts"
if not exist "%INSPECTION_STORAGE_ROOT%" mkdir "%INSPECTION_STORAGE_ROOT%"
if not exist "%INSPECTION_STORAGE_ROOT%" exit /b 1
echo Starting local backend profile: inspection
echo Keep the ready AI service on port 8001 running.
call mvn spring-boot:run -Dspring-boot.run.profiles=inspection
exit /b %errorlevel%
