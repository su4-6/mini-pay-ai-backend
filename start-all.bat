echo off
REM MiniPay one-click launcher — double-click to start everything
cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
pause
