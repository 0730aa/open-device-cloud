@echo off
rem Shows whether the phone is connected and allowed to be debugged.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\agent.ps1" -PhoneOnly
set "code=%errorlevel%"
pause
exit /b %code%
