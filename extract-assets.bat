@echo off
REM Extracts the Speech Bubbles asset pack from the JAR to the mods folder
REM Run this BEFORE starting the Hytale server

echo ========================================
echo   Speech Bubbles Asset Pack Extractor
echo ========================================
echo.

set PLUGIN_JAR=target\hycompanion-speech-bubbles.jar
set MODS_DIR=%1

if "%MODS_DIR%"=="" (
    echo Usage: extract-assets.bat ^<path-to-mods-folder^>
    echo Example: extract-assets.bat "C:\HytaleServer\mods"
    exit /b 1
)

if not exist "%PLUGIN_JAR%" (
    echo ERROR: Plugin JAR not found at %PLUGIN_JAR%
    echo Please build the plugin first with: compile-plugin.bat
    exit /b 1
)

set ASSET_PACK_DIR=%MODS_DIR%\dev.hycompanion.speech_SpeechBubbles

echo Extracting asset pack to: %ASSET_PACK_DIR%

if not exist "%ASSET_PACK_DIR%" mkdir "%ASSET_PACK_DIR%"

cd /d "%ASSET_PACK_DIR%"

REM Extract manifest.json and Common/ folder from JAR
jar xf "%CD%\..\..\hycompanion-speech-bubbles\%PLUGIN_JAR%" manifest.json Common/

if %errorlevel% neq 0 (
    echo ERROR: Failed to extract assets
    exit /b 1
)

echo.
echo ========================================
echo   Asset pack extracted successfully!
echo ========================================
echo.
echo You can now start the Hytale server.
echo.

pause
