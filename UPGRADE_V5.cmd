@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0"
python scripts\apply_application_v5.py
pause
