@echo off
echo ==========================================
echo   Mindcraft Bridge - Fabric Build Script
echo ==========================================
echo.

call gradlew.bat build
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Build failed!
    exit /b %ERRORLEVEL%
)

echo.
echo ==========================================
echo   BUILD SUCCESSFUL!
echo ==========================================
echo.
echo Output JAR: build\libs\mindcraft-bridge-1.0.0.jar