@echo off
REM Build the EchoLink Windows installer (single .exe).
REM Runs the PowerShell script with ExecutionPolicy Bypass so it works on
REM machines where script execution is disabled by default.
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-installer.ps1" %*
if errorlevel 1 (
  echo.
  echo Build failed.
  exit /b 1
)
endlocal
