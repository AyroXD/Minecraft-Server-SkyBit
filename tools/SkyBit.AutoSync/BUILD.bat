@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title SkyBit AutoSync - Build

set "CI_MODE=0"
if /I "%~1"=="CI" set "CI_MODE=1"

set "CSC64=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
set "CSC32=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe"
set "CSC="
if exist "%CSC64%" set "CSC=%CSC64%"
if not defined CSC if exist "%CSC32%" set "CSC=%CSC32%"

if not defined CSC (
  echo [ERROR] Windows .NET Framework C# compiler not found.
  echo Enable/install .NET Framework 4.8 and run BUILD.bat again.
  if "%CI_MODE%"=="0" pause
  exit /b 1
)

if not exist "src\Program.cs" (
  echo [ERROR] Source files are missing.
  if "%CI_MODE%"=="0" pause
  exit /b 1
)

if not exist "dist" mkdir "dist"
del /q "dist\SkyBit AutoSync.exe" >nul 2>&1
del /q "build-errors.txt" >nul 2>&1

echo ============================================================
echo   SkyBit AutoSync v2.0 - BUILD
echo ============================================================
echo Compiler: %CSC%
echo.

"%CSC%" /nologo /target:winexe /platform:x64 /optimize+ /warn:4 /out:"dist\SkyBit AutoSync.exe" ^
 /reference:System.dll ^
 /reference:System.Core.dll ^
 /reference:System.Drawing.dll ^
 /reference:System.Windows.Forms.dll ^
 src\*.cs 2>"build-errors.txt"

if errorlevel 1 (
  color 0C
  echo.
  echo ==================== BUILD FAILED ====================
  type "build-errors.txt"
  echo ======================================================
  if "%CI_MODE%"=="0" pause
  exit /b 1
)

if not exist "dist\SkyBit AutoSync.exe" (
  echo [ERROR] Compiler returned success but EXE is missing.
  if "%CI_MODE%"=="0" pause
  exit /b 1
)

for %%I in ("dist\SkyBit AutoSync.exe") do set "SIZE=%%~zI"
color 0A
echo.
echo ==================== BUILD SUCCESS ====================
echo EXE: %CD%\dist\SkyBit AutoSync.exe
echo Size: %SIZE% bytes
echo ======================================================

if "%CI_MODE%"=="1" exit /b 0
set /p RUNNOW="Run app now? [Y/N]: "
if /I "%RUNNOW%"=="Y" start "" "dist\SkyBit AutoSync.exe"
exit /b 0
