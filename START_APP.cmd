@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0"
python scripts\app_local.py start
if errorlevel 1 echo Check the STOP message above. No database was reset.
pause
