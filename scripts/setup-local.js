#!/usr/bin/env node
/**
 * setup-local.js
 * Prepares the project for fully local, offline operation:
 *   1. Creates the models/ directory if it does not exist.
 *   2. Downloads a small default GGUF model from Hugging Face if none is present.
 *   3. Installs node-llama-cpp if it is not already listed in dependencies.
 */

import { existsSync, mkdirSync, createWriteStream, readdirSync, readFileSync, writeFileSync } from 'fs';
import { join, resolve } from 'path';
import { exec } from 'child_process';
import { promisify } from 'util';

const execAsync = promisify(exec);

const MODELS_DIR = 'models';
const DEFAULT_MODEL_FILENAME = 'qwen2.5-0.5b-instruct-q4_k_m.gguf';
const DEFAULT_MODEL_URL =
    'https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf';
const PACKAGE_JSON_PATH = 'package.json';

// ── helpers ────────────────────────────────────────────────────────────────

function log(msg) {
    process.stdout.write(`[setup-local] ${msg}\n`);
}

function findGgufFiles(dir) {
    try {
        return readdirSync(dir).filter(f => f.toLowerCase().endsWith('.gguf'));
    } catch {
        return [];
    }
}

async function downloadFile(url, destPath) {
    log(`Downloading ${url}`);
    log(`  → ${resolve(destPath)}`);

    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`HTTP ${response.status} while downloading model`);
    }

    const totalBytes = Number(response.headers.get('content-length') || 0);
    let downloaded = 0;
    let lastPct = -1;

    const fileStream = createWriteStream(destPath);
    const reader = response.body.getReader();

    await new Promise((res, rej) => {
        fileStream.on('error', rej);
        fileStream.on('finish', res);

        (async () => {
            try {
                while (true) {
                    const { done, value } = await reader.read();
                    if (done) break;
                    fileStream.write(value);
                    downloaded += value.length;
                    if (totalBytes > 0) {
                        const pct = Math.floor((downloaded / totalBytes) * 100);
                        if (pct !== lastPct && pct % 5 === 0) {
                            process.stdout.write(`\r[setup-local]   ${pct}% (${(downloaded / 1024 / 1024).toFixed(1)} MB)`);
                            lastPct = pct;
                        }
                    }
                }
                fileStream.end();
            } catch (err) {
                rej(err);
            }
        })();
    });

    process.stdout.write('\n');
    log('Download complete.');
}

// ── main ───────────────────────────────────────────────────────────────────

async function main() {
    // 1. Ensure models/ directory exists
    if (!existsSync(MODELS_DIR)) {
        mkdirSync(MODELS_DIR, { recursive: true });
        log(`Created directory: ${MODELS_DIR}/`);
    } else {
        log(`Directory already exists: ${MODELS_DIR}/`);
    }

    // 2. Download default model if no .gguf files are present
    const ggufFiles = findGgufFiles(MODELS_DIR);
    if (ggufFiles.length > 0) {
        log(`Found existing model(s): ${ggufFiles.join(', ')} — skipping download.`);
    } else {
        log('No GGUF model found. Downloading default model (~400 MB)...');
        const dest = join(MODELS_DIR, DEFAULT_MODEL_FILENAME);
        await downloadFile(DEFAULT_MODEL_URL, dest);
        log(`Model saved to ${dest}`);
    }

    // 3. Install node-llama-cpp if missing from dependencies
    const pkg = JSON.parse(readFileSync(PACKAGE_JSON_PATH, 'utf8'));
    const hasDep = pkg.dependencies?.['node-llama-cpp'] !== undefined;
    const hasDevDep = pkg.devDependencies?.['node-llama-cpp'] !== undefined;

    if (hasDep || hasDevDep) {
        log('node-llama-cpp is already listed in package.json — checking installation…');
    } else {
        log('Installing node-llama-cpp…');
    }

    // Always ensure the package is actually installed (covers both cases)
    try {
        await import('node-llama-cpp');
        log('node-llama-cpp is already installed.');
    } catch {
        log('Running: npm install node-llama-cpp');
        const { stdout, stderr } = await execAsync('npm install node-llama-cpp');
        if (stdout) process.stdout.write(stdout);
        if (stderr) process.stderr.write(stderr);

        // Persist the new dependency in package.json
        const updatedPkg = JSON.parse(readFileSync(PACKAGE_JSON_PATH, 'utf8'));
        if (!updatedPkg.dependencies) updatedPkg.dependencies = {};
        if (!updatedPkg.dependencies['node-llama-cpp']) {
            // Read the installed version and record it
            try {
                const installed = JSON.parse(
                    readFileSync(join('node_modules', 'node-llama-cpp', 'package.json'), 'utf8')
                );
                updatedPkg.dependencies['node-llama-cpp'] = `^${installed.version}`;
                writeFileSync(PACKAGE_JSON_PATH, JSON.stringify(updatedPkg, null, 4) + '\n');
                log(`Added node-llama-cpp@${installed.version} to package.json dependencies.`);
            } catch {
                log('Warning: could not update package.json with node-llama-cpp version.');
            }
        }
    }

    log('');
    log('✅  Local setup complete!');
    log('   Run `npm run start-local` (or START_LOCAL.bat / start_local.sh) to launch the bot.');
}

main().catch(err => {
    console.error('[setup-local] Fatal error:', err.message || err);
    process.exit(1);
});
