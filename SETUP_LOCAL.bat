@echo off
title Mindcraft – Local Setup
echo [setup] Checking Node.js...
node --version >nul 2>&1
if errorlevel 1 (
    echo [setup] ERROR: Node.js is not installed or not on PATH.
    echo         Download it from https://nodejs.org/ ^(v18 or v20 LTS recommended^)
    pause
    exit /b 1
)

echo [setup] Installing npm dependencies...
call npm install
if errorlevel 1 (
    echo [setup] ERROR: npm install failed. See output above.
    pause
    exit /b 1
)

echo [setup] Running local model setup...
call npm run setup-local
if errorlevel 1 (
    echo [setup] ERROR: setup-local failed. See output above.
    pause
    exit /b 1
)

echo.
echo [setup] All done! Run START_LOCAL.bat to launch the bot.
pause
