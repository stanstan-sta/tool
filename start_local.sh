#!/usr/bin/env bash
set -euo pipefail

echo "[start] Checking Node.js..."
if ! command -v node &>/dev/null; then
    echo "[start] ERROR: Node.js is not installed or not on PATH."
    echo "        Download it from https://nodejs.org/ (v18 or v20 LTS recommended)"
    exit 1
fi

if [ ! -d "node_modules" ]; then
    echo "[start] node_modules not found – running npm install first..."
    npm install
fi

if [ ! -d "models" ]; then
    echo "[start] No models/ directory found – running setup-local first..."
    npm run setup-local
fi

echo "[start] Starting Mindcraft with local model..."
echo "[start] Open http://localhost:8080 in your browser for the live console."
echo ""
node main.js --profiles profiles/local.json
