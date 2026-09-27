@echo off
rem Stops the server started by start-server.bat.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\server.ps1" stop
set "code=%errorlevel%"
pause
exit /b %code%
