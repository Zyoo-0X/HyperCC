@echo off
setlocal
cd /d "%~dp0"
where powershell.exe >nul 2>nul
if errorlevel 1 (
  echo ERROR: Windows PowerShell is required.
  pause
  exit /b 1
)
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
set "BUILD_EXIT=%ERRORLEVEL%"
if not "%BUILD_EXIT%"=="0" (
  echo.
  echo Build failed with exit code %BUILD_EXIT%.
) else (
  echo.
  echo Build finished. Check app\build\outputs\apk\debug\app-debug.apk
)
pause
exit /b %BUILD_EXIT%
