@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title SkyBit AutoSync - Diagnostics

echo ============================================================
echo   SkyBit AutoSync - Diagnostics
echo ============================================================
echo.
echo --- Windows ---
ver
echo.

echo --- Git ---
where git 2>nul
if errorlevel 1 (
  echo [FAIL] git.exe not found in PATH.
) else (
  git --version
)
echo.

echo --- .NET Framework C# compiler ---
if exist "%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" (
  echo [OK] 64-bit compiler found.
) else if exist "%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe" (
  echo [OK] 32-bit compiler found.
) else (
  echo [FAIL] csc.exe not found. Enable/install .NET Framework 4.8.
)
echo.

echo --- Built application ---
if exist "dist\SkyBit AutoSync.exe" (
  echo [OK] dist\SkyBit AutoSync.exe exists.
) else (
  echo [INFO] App is not built yet. Run BUILD.bat.
)
echo.

echo --- Startup log ---
echo %APPDATA%\SkyBit AutoSync\startup-error.log
if exist "%APPDATA%\SkyBit AutoSync\startup-error.log" (
  powershell -NoProfile -Command "Get-Content -LiteralPath ($env:APPDATA+'\SkyBit AutoSync\startup-error.log') -Tail 30"
) else (
  echo No startup-error.log yet.
)
echo.

echo --- Runtime log ---
echo %APPDATA%\SkyBit AutoSync\autosync.log
if exist "%APPDATA%\SkyBit AutoSync\autosync.log" (
  powershell -NoProfile -Command "Get-Content -LiteralPath ($env:APPDATA+'\SkyBit AutoSync\autosync.log') -Tail 20"
) else (
  echo No autosync.log yet.
)
echo.
pause
