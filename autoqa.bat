@echo off
:: IMDS AutoQA — portable Windows launcher
::
:: HOW TO USE
::   1. Put autoqa.jar and autoqa.bat in the same folder (anywhere on your PC).
::   2. Double-click autoqa.bat  — the GUI opens, no console window.
::
:: REQUIREMENTS
::   Java 17+ must be installed (https://adoptium.net/).
::   The wrapper auto-finds java using JAVA_HOME or PATH.

setlocal

set "SCRIPT_DIR=%~dp0"

:: Look next to this script first (portable distribution), then in Maven's target/
set "JAR=%SCRIPT_DIR%autoqa.jar"
if not exist "%JAR%" set "JAR=%SCRIPT_DIR%target\autoqa.jar"

:: ── Find javaw ──────────────────────────────────────────────────────────────

:: 1. Bundled JRE next to the JAR (optional — pack a JRE folder named 'jre' here)
if exist "%SCRIPT_DIR%jre\bin\javaw.exe" (
    set "JAVAW=%SCRIPT_DIR%jre\bin\javaw.exe"
    goto :run
)

:: 2. JAVA_HOME environment variable
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javaw.exe" (
        set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
        goto :run
    )
)

:: 3. java.exe on PATH (javaw may not be on PATH but java.exe is)
where javaw >nul 2>&1 && set "JAVAW=javaw" && goto :run
where java  >nul 2>&1 && set "JAVAW=java"  && goto :run

:: No Java found
echo.
echo  ERROR: Java 17+ is required but was not found.
echo.
echo  Install it from:  https://adoptium.net/
echo  Then re-run this launcher.
echo.
pause
exit /b 1

:: ── Launch ──────────────────────────────────────────────────────────────────
:run
if not exist "%JAR%" (
    echo.
    echo  ERROR: autoqa.jar not found in %SCRIPT_DIR%
    echo.
    echo  Make sure autoqa.jar and autoqa.bat are in the same folder.
    echo.
    pause
    exit /b 1
)

:: cd to the launcher folder so recordings/, evidence/ etc. land there
cd /d "%SCRIPT_DIR%"

:: 'start ""' detaches the process — window closes immediately, GUI stays open
start "" "%JAVAW%" -jar "%JAR%"
endlocal
