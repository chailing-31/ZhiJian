@echo off
setlocal DisableDelayedExpansion
cd /d "%~dp0"
rem Select this repository's A15 explicitly, ignoring a stale inherited model path.
set "AI_MODEL_MANIFEST=%~dp0models\a15\model-manifest.json"
call "%~dp0run-cpu-local.cmd"
exit /b %errorlevel%
