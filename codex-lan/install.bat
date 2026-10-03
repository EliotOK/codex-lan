@echo off
setlocal
title Codex Light Setup
echo Installing Codex Light desktop service...
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1" %*
set "setup_result=%errorlevel%"
echo.
if not "%setup_result%"=="0" echo Setup did not finish. Keep this window open to read the error above.
pause
exit /b %setup_result%
