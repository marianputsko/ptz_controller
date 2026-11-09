@echo off
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Chyba Node.js. Nainstaluj ho z https://nodejs.org a spusti znova.
  start "" https://nodejs.org/
  pause
  exit /b 1
)
node bridge.js
pause
