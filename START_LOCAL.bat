@echo off
title Mindcraft – Local Bot
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

if not exist models (
    echo [start] No models/ directory found – running setup-local first...
    call npm run setup-local
    if errorlevel 1 (
        echo [start] ERROR: setup-local failed.
        pause
        exit /b 1
    )
)

echo [start] Starting Mindcraft with local model...
echo [start] Open http://localhost:8080 in your browser for the live console.
echo.
set "START_CMD=node main.js --profiles profiles/local.json"
:run_mindcraft
%START_CMD%
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% neq 0 (
    echo [start] Mindcraft exited with code %EXIT_CODE%. Restarting in 3 seconds...
    timeout /t 3 /nobreak >nul
    goto run_mindcraft
)
echo [start] Mindcraft exited normally.
pause
