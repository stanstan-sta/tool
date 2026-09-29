import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';

test('SES lockdown wrapper hardens intrinsics in an isolated process', () => {
    const moduleUrl = new URL('../src/agent/library/lockdown.js', import.meta.url).href;
    const script = `
        import { lockdown } from ${JSON.stringify(moduleUrl)};
        lockdown();
        if (!Object.isFrozen(Object.prototype) || !Object.isFrozen(Array.prototype)) {
            process.exit(7);
        }
    `;
    const result = spawnSync(process.execPath, ['--input-type=module', '--eval', script], {
        encoding: 'utf8',
        cwd: process.cwd(),
    });
    assert.equal(result.status, 0, result.stderr || result.stdout || `child exited ${result.status}`);
});
