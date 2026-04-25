@echo off
title Mindcraft – Fabric Bridge Bot
echo [start] Checking Node.js...
node --version >nul 2>&1
if errorlevel 1 (
    echo [start] ERROR: Node.js is not installed or not on PATH.
    echo         Download it from https://nodejs.org/ ^(v18 or v20 LTS recommended^)
    pause
    exit /b 1
)

if not exist node_modules (
    echo [start] node_modules not found – running npm install first...
    call npm install
    if errorlevel 1 (
        echo [start] ERROR: npm install failed.
        pause
        exit /b 1
    )
)

echo [start] Starting Mindcraft in Fabric bridge mode...

:: Read mindserver_port from settings.js dynamically
for /f "usebackq delims=" %%i in (`node -e "import('./settings.js').then(m => console.log(m.default.mindserver_port))" 2^>nul`) do set MIND_PORT=%%i
if "%MIND_PORT%"=="" set MIND_PORT=8080
echo [start] Open http://localhost:%MIND_PORT% in your browser for the live console.
echo.
node main.js
pause

