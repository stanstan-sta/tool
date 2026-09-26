#!/usr/bin/env node
/**
 * Smoke driver for the Mindcraft / Fabric Bridge Agent.
 *
 * Usage:
 *   node .claude/skills/run-tool/smoke.mjs [--bridge-url URL] [--console-url URL]
 *
 * Runs a sequence of checks against a live instance:
 *   1. Web console responds (port 8080)
 *   2. Fabric bridge /ping responds (port 8765)
 *   3. /state returns connected player data
 *   4. /capabilities lists expected action types
 *   5. bridge:probe preflight passes (via the existing script)
 *
 * Exits 0 on all pass, 1 on any failure.
 */

import { execSync } from 'child_process';

const BRIDGE_URL = process.env.BRIDGE_URL || 'http://localhost:8765';
const CONSOLE_URL = process.env.CONSOLE_URL || 'http://localhost:8080';

let pass = 0;
let fail = 0;

function check(label, fn) {
    try {
        fn();
        console.log(`[PASS] ${label}`);
        pass++;
    } catch (e) {
        console.error(`[FAIL] ${label}: ${e.message}`);
        fail++;
    }
}

function get(url) {
    const out = execSync(`curl -sf "${url}"`, { encoding: 'utf8', timeout: 5000 });
    return out;
}

function getJSON(url) {
    return JSON.parse(get(url));
}

// 1. Web console
check('web console responds', () => {
    const html = get(CONSOLE_URL);
    if (!html.includes('<title>Mindcraft</title>')) throw new Error('unexpected HTML');
});

// 2. Bridge ping
check('bridge /ping', () => {
    const d = getJSON(`${BRIDGE_URL}/ping`);
    if (!d.ok) throw new Error('ok != true');
});

// 3. Bridge state — agent connected
check('bridge /state: connected player', () => {
    const d = getJSON(`${BRIDGE_URL}/state`);
    if (!d.connected) throw new Error('connected != true');
    if (!d.player_name) throw new Error('missing player_name');
    console.log(`         player=${d.player_name} health=${d.health} pos=${d.x},${d.y},${d.z}`);
});

// 4. Capabilities
check('bridge /capabilities: action types present', () => {
    const d = getJSON(`${BRIDGE_URL}/capabilities`);
    const required = ['move', 'mine', 'follow', 'cancel'];
    for (const a of required) {
        if (!d.action_types.includes(a)) throw new Error(`missing action type: ${a}`);
    }
});

// 5. Bridge probe preflight
check('bridge:probe preflight', () => {
    execSync(`node scripts/bridge_action_probe.mjs`, {
        cwd: new URL('../../..', import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1'),
        encoding: 'utf8',
        timeout: 15000,
        stdio: 'pipe',
    });
});

console.log(`\n${pass} passed, ${fail} failed.`);
process.exit(fail > 0 ? 1 : 0);
