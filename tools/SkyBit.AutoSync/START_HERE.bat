@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title SkyBit AutoSync - Start Here

if not exist "dist\SkyBit AutoSync.exe" (
  echo SkyBit AutoSync is not built yet. Building now...
  echo.
  call BUILD.bat
  if errorlevel 1 (
    echo.
    echo Build failed. Running diagnostics...
    call DIAGNOSTICS.bat
    exit /b 1
  )
)

echo Starting SkyBit AutoSync...
start "" "dist\SkyBit AutoSync.exe"
timeout /t 2 /nobreak >nul
exit /b 0
