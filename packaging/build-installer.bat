@echo off
REM ─────────────────────────────────────────────────────────────────────────────
REM  IMDS AutoQA — Windows App-Image Builder
REM  Produces:  dist\IMDS AutoQA\IMDS AutoQA.exe
REM
REM  Usage:
REM    packaging\build-installer.bat
REM
REM  Prerequisites:
REM    - JDK 17 at D:\Java\jdk-17.0.18+8  (jpackage.exe must exist)
REM    - Maven 3.9.6 at D:\Maven\apache-maven-3.9.6
REM    - Run from the repo root (imds-autoqa\)
REM ─────────────────────────────────────────────────────────────────────────────
setlocal

set JAVA_HOME=D:\Java\jdk-17.0.18+8
set M2_HOME=D:\Maven\apache-maven-3.9.6
set PATH=%JAVA_HOME%\bin;%M2_HOME%\bin;%PATH%

set SCRIPT_DIR=%~dp0
set ROOT_DIR=%SCRIPT_DIR%..
set DIST_DIR=%ROOT_DIR%\dist
set ICON=%ROOT_DIR%\src\main\resources\autoqa-icon.ico

echo.
echo ════════════════════════════════════════════════
echo   IMDS AutoQA — Windows Installer Build
echo ════════════════════════════════════════════════
echo.

REM ── Step 1: Build the fat JAR ─────────────────────────────────────────────
echo [1/2] Building fat JAR (mvn package -DskipTests) ...
pushd "%ROOT_DIR%"
call mvn package -DskipTests -q
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Maven build failed. Aborting.
    exit /b 1
)
echo        Done — target\autoqa.jar built.

REM ── Step 2: jpackage app-image ────────────────────────────────────────────
echo.
echo [2/2] Creating app-image with jpackage ...
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

"%JAVA_HOME%\bin\jpackage.exe" ^
  --input target ^
  --main-jar autoqa.jar ^
  --main-class autoqa.cli.WrapperCLI ^
  --name "IMDS AutoQA" ^
  --app-version 1.0.0 ^
  --icon "%ICON%" ^
  --type app-image ^
  --description "IMDS AutoQA - AI-Powered Test Automation with Self-Healing" ^
  --vendor "IMDS" ^
  --dest "%DIST_DIR%"

if %ERRORLEVEL% NEQ 0 (
    echo ERROR: jpackage failed. Aborting.
    exit /b 1
)

popd

echo.
echo ════════════════════════════════════════════════
echo   Build complete!
echo   Launcher: dist\IMDS AutoQA\IMDS AutoQA.exe
echo ════════════════════════════════════════════════
echo.
echo Run packaging\create-shortcuts.bat to add Desktop and Start Menu shortcuts.
endlocal
