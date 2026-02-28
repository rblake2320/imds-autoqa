@echo off
:: IMDS AutoQA — desktop launcher
:: Uses javaw so no console window appears.
:: Double-click this file, or create a shortcut to it on your Desktop.

set "SCRIPT_DIR=%~dp0"
set "JAR=%SCRIPT_DIR%target\imds-autoqa-1.0.0-SNAPSHOT.jar"

:: Prefer the JDK bundled with this project if present
if exist "%SCRIPT_DIR%jdk\bin\javaw.exe" (
    set "JAVAW=%SCRIPT_DIR%jdk\bin\javaw.exe"
    goto :run
)

:: Fall back to JAVA_HOME
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javaw.exe" (
        set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
        goto :run
    )
)

:: Last resort: hope javaw is on PATH
set "JAVAW=javaw"

:run
if not exist "%JAR%" (
    echo ERROR: JAR not found at %JAR%
    echo Run:  mvn package -DskipTests
    pause
    exit /b 1
)

:: Change to project directory so relative paths (recordings/, evidence/) resolve correctly
cd /d "%SCRIPT_DIR%"
start "" "%JAVAW%" -jar "%JAR%"
