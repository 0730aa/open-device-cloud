@echo off
rem Runs the agent in this window (scripts\agent.ps1); closing the window stops it.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\agent.ps1"
set "code=%errorlevel%"
pause
exit /b %code%
