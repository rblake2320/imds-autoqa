@echo off
REM ─────────────────────────────────────────────────────────────────────────────
REM  IMDS AutoQA — Shortcut Creator
REM  Creates Desktop and Start Menu shortcuts to the installed app-image.
REM
REM  Usage:
REM    packaging\create-shortcuts.bat
REM
REM  Run AFTER build-installer.bat has completed successfully.
REM ─────────────────────────────────────────────────────────────────────────────
setlocal

set SCRIPT_DIR=%~dp0
set ROOT_DIR=%SCRIPT_DIR%..
set APP_EXE=%ROOT_DIR%\dist\IMDS AutoQA\IMDS AutoQA.exe

REM Verify the exe exists
if not exist "%APP_EXE%" (
    echo ERROR: App-image not found at:
    echo   %APP_EXE%
    echo Run packaging\build-installer.bat first.
    exit /b 1
)

echo Creating shortcuts for IMDS AutoQA ...

REM ── Desktop shortcut ──────────────────────────────────────────────────────
powershell -NoProfile -Command ^
  "$ws = New-Object -ComObject WScript.Shell; " ^
  "$lnk = $ws.CreateShortcut([System.IO.Path]::Combine($env:USERPROFILE, 'Desktop', 'IMDS AutoQA.lnk')); " ^
  "$lnk.TargetPath = '%APP_EXE%'; " ^
  "$lnk.WorkingDirectory = '%ROOT_DIR%\dist\IMDS AutoQA'; " ^
  "$lnk.Description = 'IMDS AutoQA - AI Test Automation'; " ^
  "$lnk.Save()"

if %ERRORLEVEL% EQU 0 (
    echo   [OK] Desktop shortcut created.
) else (
    echo   [WARN] Desktop shortcut creation failed.
)

REM ── Start Menu shortcut ───────────────────────────────────────────────────
set START_DIR=%APPDATA%\Microsoft\Windows\Start Menu\Programs\IMDS
if not exist "%START_DIR%" mkdir "%START_DIR%"

powershell -NoProfile -Command ^
  "$ws = New-Object -ComObject WScript.Shell; " ^
  "$lnk = $ws.CreateShortcut('%START_DIR%\IMDS AutoQA.lnk'); " ^
  "$lnk.TargetPath = '%APP_EXE%'; " ^
  "$lnk.WorkingDirectory = '%ROOT_DIR%\dist\IMDS AutoQA'; " ^
  "$lnk.Description = 'IMDS AutoQA - AI Test Automation'; " ^
  "$lnk.Save()"

if %ERRORLEVEL% EQU 0 (
    echo   [OK] Start Menu shortcut created ^(IMDS\IMDS AutoQA^).
) else (
    echo   [WARN] Start Menu shortcut creation failed.
)

echo.
echo Shortcuts created. You can now launch IMDS AutoQA from the Desktop or Start Menu.
endlocal
