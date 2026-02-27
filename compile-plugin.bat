@echo off
title Speech Bubbles Plugin - Compile
echo.
echo ========================================
echo   Speech Bubbles Plugin Compiler
echo ========================================
echo.

:: Set JAVA_HOME to Java 25 (Adoptium)
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot

echo Using Java: %JAVA_HOME%
echo.

:: MVN install dependencies
echo [1/3] Installing dependencies...
call mvn install -q
if %errorlevel% neq 0 (
    echo.
    echo ========================================
    echo   DEPENDENCIES INSTALLATION FAILED!
    echo ========================================
    pause
    exit /b 1
)

:: Clean and compile with Maven
echo [2/3] Compiling...
call mvn compile -q
if %errorlevel% neq 0 (
    echo.
    echo ========================================
    echo   COMPILATION FAILED!
    echo ========================================
    pause
    exit /b 1
)

:: Package the plugin
echo [3/3] Packaging plugin...
call mvn package -DskipTests -q

if %errorlevel% neq 0 (
    echo.
    echo ========================================
    echo   PACKAGING FAILED!
    echo ========================================
    pause
    exit /b 1
)

echo.
echo ========================================
echo   BUILD SUCCESSFUL!
echo ========================================
echo.
echo Output: target\hycompanion-speech-bubbles.jar
echo.

pause
