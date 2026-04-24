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
echo [start] Open http://localhost:8080 in your browser for the live console.
echo.
node main.js
pause

