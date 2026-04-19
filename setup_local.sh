#!/usr/bin/env bash
set -euo pipefail

echo "[setup] Checking Node.js..."
if ! command -v node &>/dev/null; then
    echo "[setup] ERROR: Node.js is not installed or not on PATH."
    echo "        Download it from https://nodejs.org/ (v18 or v20 LTS recommended)"
    exit 1
fi

echo "[setup] Installing npm dependencies..."
npm install

echo "[setup] Running local model setup..."
npm run setup-local

echo ""
echo "[setup] All done! Run ./start_local.sh to launch the bot."
