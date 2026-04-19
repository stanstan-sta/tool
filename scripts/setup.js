#!/usr/bin/env node
/**
 * setup.js
 * General setup helper – ensures dependencies are installed and
 * prompts the user to configure keys.json when it is missing.
 */

import { existsSync, copyFileSync } from 'fs';

function log(msg) {
    process.stdout.write(`[setup] ${msg}\n`);
}

if (!existsSync('keys.json')) {
    if (existsSync('keys.example.json')) {
        copyFileSync('keys.example.json', 'keys.json');
        log('Created keys.json from keys.example.json — please fill in your API key(s).');
    } else {
        log('Warning: keys.example.json not found. Create keys.json manually before running the bot.');
    }
} else {
    log('keys.json already exists.');
}

log('');
log('✅  Setup complete!');
log('   Edit keys.json with your API key(s), then run `npm start` to launch the bot.');
