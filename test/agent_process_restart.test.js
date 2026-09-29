import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import process from 'node:process';
import { AgentProcess } from '../src/process/agent_process.js';
import { registerAgent, createMindServer, getIO } from '../src/mindcraft/mindserver.js';

// AgentProcess restart semantics: process.killed only means a signal was
// sent, not that the child exited. forceRestart must escalate to SIGKILL by
// child reference (not via the .killed flag, which is already true after
// SIGINT) and must never let a superseded child's late exit start a
// duplicate replacement.
//
// The stub below stands in for src/process/init_bridge_agent.js inside an
// isolated cwd. AP_STUB=ignore-sigint makes the child survive SIGINT so the
// escalation path is exercised.
const STUB = `
if (process.env.AP_STUB === 'ignore-sigint') process.on('SIGINT', () => {});
setInterval(() => {}, 1000);
`;

// After-hooks run in registration order, so teardown must happen in ONE hook:
// kill every spawned child, wait for the OS to release the temp directory,
// chdir back, then remove the directory with retries (Windows holds a
// cwd lock until the child fully exits).
const teardownProcs = [];

function setupRoot(t) {
    const root = mkdtempSync(path.join(tmpdir(), 'mindcraft-agent-restart-'));
    mkdirSync(path.join(root, 'src/process'), { recursive: true });
    writeFileSync(path.join(root, 'src/process/init_bridge_agent.js'), STUB);
    const previousCwd = process.cwd();
    process.chdir(root);
    t.after(async () => {
        const procs = teardownProcs.splice(0);
        for (const proc of procs) {
            const child = proc.process;
            if (child && child.exitCode === null && child.signalCode === null) {
                try { child.kill('SIGKILL'); } catch (err) { console.log(`Teardown SIGKILL raced child exit (${err.code}).`); }
            }
        }
        const deadline = Date.now() + 15000;
        while (procs.some(proc => proc.process && proc.process.exitCode === null && proc.process.signalCode === null)) {
            if (Date.now() > deadline) break;
            await new Promise(resolve => setTimeout(resolve, 100));
        }
        process.chdir(previousCwd);
        const rmDeadline = Date.now() + 15000;
        for (;;) {
            try {
                rmSync(root, { recursive: true, force: true });
                return;
            } catch (err) {
                if (Date.now() > rmDeadline) throw err;
                await new Promise(resolve => setTimeout(resolve, 250));
            }
        }
    });
    return root;
}

function waitFor(condition, timeoutMs, label) {
    const start = Date.now();
    return new Promise((resolve, reject) => {
        const check = () => {
            let done = false;
            try { done = condition(); } catch { done = false; }
            if (done) return resolve();
            if (Date.now() - start > timeoutMs) return reject(new Error(`timed out waiting for ${label}`));
            setTimeout(check, 100);
        };
        check();
    });
}

function alive(proc) {
    if (!proc || !proc.process || proc.process.exitCode !== null) return false;
    try { process.kill(proc.process.pid, 0); return true; }
    catch { return false; }
}

async function trackedStart(t, name) {
    // The exit path reports through the MindServer status broadcaster.
    createMindServer(false, 0);
    t.after(() => new Promise(resolve => getIO().close(resolve)));
    registerAgent({ profile: { name, model: 'fixture' }, profile_path: `./profiles/${name}.json` });
    const proc = new AgentProcess(name, 0, true);
    let starts = 0;
    const original = proc.start.bind(proc);
    proc.start = (...args) => { starts += 1; return original(...args); };
    const count = () => starts;
    teardownProcs.push(proc);
    proc.start();
    await waitFor(() => proc.process && proc.process.pid, 10000, 'child spawn');
    return { proc, count };
}

test('forceRestart restarts a SIGINT-responsive child exactly once', async t => {
    setupRoot(t);
    const { proc, count } = await trackedStart(t, `RestartFast${process.pid}`);
    const firstPid = proc.process.pid;
    proc.forceRestart();
    await waitFor(() => count() === 2 && alive(proc) && proc.process.pid !== firstPid, 10000, 'single replacement');
    await new Promise(resolve => setTimeout(resolve, 1500));
    assert.equal(count(), 2);
});

test('forceRestart escalates to SIGKILL when the child ignores SIGINT', async t => {
    setupRoot(t);
    process.env.AP_STUB = 'ignore-sigint';
    t.after(() => { delete process.env.AP_STUB; });
    const { proc, count } = await trackedStart(t, `RestartHung${process.pid}`);
    const firstPid = proc.process.pid;
    assert.ok(alive(proc));
    proc.forceRestart();
    // Old code never sends SIGKILL here (.killed is already true after
    // SIGINT), so the old child would stay alive and this times out.
    await waitFor(() => {
        let oldDead = true;
        try { process.kill(firstPid, 0); oldDead = false; } catch { oldDead = true; }
        return oldDead && count() === 2 && alive(proc);
    }, 20000, 'SIGKILL escalation with single replacement');
    await new Promise(resolve => setTimeout(resolve, 1500));
    assert.equal(count(), 2);
});

test('forceRestart handles a delayed SIGINT exit with exactly one replacement', async t => {
    setupRoot(t);
    const { proc, count } = await trackedStart(t, `RestartSlow${process.pid}`);
    const firstPid = proc.process.pid;
    // Delay the SIGINT delivery by 2s (stays well under the 5s SIGKILL
    // escalation): the exit still confirms before escalation, so exactly one
    // replacement must start with no overlap and no escalation.
    const oldChild = proc.process;
    const realKill = oldChild.kill.bind(oldChild);
    t.after(() => { oldChild.kill = realKill; });
    let delayed = false;
    oldChild.kill = (signal) => {
        if (signal === 'SIGINT' && !delayed) {
            delayed = true;
            setTimeout(() => {
                if (oldChild.exitCode === null && oldChild.signalCode === null) realKill('SIGINT');
            }, 2000);
            return true;
        }
        return realKill(signal);
    };
    const warnings = [];
    const realWarn = console.warn;
    console.warn = (...args) => { warnings.push(args.map(String).join(' ')); };
    t.after(() => { console.warn = realWarn; });
    const logs = [];
    const realLog = console.log;
    console.log = (...args) => { logs.push(args.map(String).join(' ')); };
    t.after(() => { console.log = realLog; });
    try {
        proc.forceRestart();
        await waitFor(() => count() === 2 && alive(proc) && proc.process.pid !== firstPid, 10000, 'delayed single replacement');
        let oldDead = true;
        try { process.kill(firstPid, 0); oldDead = false; } catch { oldDead = true; }
        assert.ok(oldDead);
        assert.match(logs.join('\n'), /Attempting to force restart/);
        assert.doesNotMatch(warnings.join('\n'), /Escalating to SIGKILL/);
        await new Promise(resolve => setTimeout(resolve, 1500));
        assert.equal(count(), 2);
    } finally {
        console.warn = realWarn;
        console.log = realLog;
        oldChild.kill = realKill;
    }
});

test('forceRestart blocks the replacement when the child refuses to die', async t => {
    setupRoot(t);
    const { proc, count } = await trackedStart(t, `RestartStuck${process.pid}`);
    const firstPid = proc.process.pid;
    // Simulate an unkillable child: swallow every signal so neither SIGINT
    // nor the SIGKILL escalation can produce a confirmed exit.
    const oldChild = proc.process;
    const realKill = oldChild.kill.bind(oldChild);
    t.after(() => { oldChild.kill = realKill; });
    oldChild.kill = () => true;
    const errors = [];
    const realError = console.error;
    console.error = (...args) => { errors.push(args.map(String).join(' ')); };
    t.after(() => { console.error = realError; });
    proc.forceRestart();
    // Bounded wait is 5s (SIGINT->SIGKILL) + 3s grace. The old child must
    // still be the only child afterwards: no overlap, no replacement.
    await new Promise(resolve => setTimeout(resolve, 10000));
    try {
        assert.equal(count(), 1);
        assert.ok(alive(proc));
        assert.equal(proc.process.pid, firstPid);
        assert.equal(proc._awaitingManualRestart, false);
        assert.match(errors.join('\n'), /refused to exit|Replacement blocked/i);
    } finally {
        console.error = realError;
        // The retried kill path still works once signals are honoured again.
        oldChild.kill = realKill;
    }
    proc.forceRestart();
    await waitFor(() => count() === 2 && alive(proc) && proc.process.pid !== firstPid, 10000, 'retry after signals honoured');
});

test('stale exit/error from a superseded child cannot mutate replacement state', async t => {
    setupRoot(t);
    const { proc, count } = await trackedStart(t, `RestartStale${process.pid}`);
    const oldChild = proc.process;
    const firstPid = oldChild.pid;
    proc.forceRestart();
    await waitFor(() => count() === 2 && alive(proc) && proc.process.pid !== firstPid, 10000, 'single replacement');
    const replacement = proc.process;
    // Replaying the old child's terminal events must be a no-op.
    oldChild.emit('exit', 0, null);
    oldChild.emit('error', new Error('synthetic stale error'));
    await new Promise(resolve => setTimeout(resolve, 500));
    assert.equal(count(), 2);
    assert.equal(proc.running, true);
    assert.equal(proc.process, replacement);
    assert.equal(proc.process.pid, replacement.pid);
});

test('concurrent forceRestart calls produce exactly one replacement', async t => {
    setupRoot(t);
    const { proc, count } = await trackedStart(t, `RestartRace${process.pid}`);
    const firstPid = proc.process.pid;
    proc.forceRestart();
    proc.forceRestart();
    await waitFor(() => count() === 2 && alive(proc) && proc.process.pid !== firstPid, 10000, 'single replacement after duplicate calls');
    await new Promise(resolve => setTimeout(resolve, 1500));
    assert.equal(count(), 2);
});
