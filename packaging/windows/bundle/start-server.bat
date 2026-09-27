@echo off
rem Starts the server on this PC (scripts\server.ps1), then leaves this window open to read.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\server.ps1" start
set "code=%errorlevel%"
pause
exit /b %code%
