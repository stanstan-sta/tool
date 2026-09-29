import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export function mindserverFixture(t, before) {
    const temporary = mkdtempSync(path.join(tmpdir(), 'mindcraft-startup-'));
    const root = path.join(temporary, 'app');
    mkdirSync(path.join(root, 'src/mindcraft/public'), { recursive: true });
    mkdirSync(path.join(root, 'profiles'), { recursive: true });
    mkdirSync(path.join(root, 'src/bridge'), { recursive: true });
    symlinkSync(path.join(repo, 'node_modules'), path.join(root, 'node_modules'), 'junction');
    for (const relative of ['main.js', 'src/mindcraft/mindserver.js', 'src/mindcraft/startup_profiles.js', 'src/mindcraft/public/settings_spec.json', 'src/bridge/fabric_bridge.js', 'src/bridge/state_summary.js']) {
        const baseline = before && path.join(before, path.basename(relative));
        copyFileSync(baseline && existsSync(baseline) ? baseline : path.join(repo, relative), path.join(root, relative));
    }
    writeFileSync(path.join(root, 'package.json'), '{"type":"module"}');
    writeFileSync(path.join(root, 'settings.js'), 'export default { profiles: [], auto_open_ui: false };');
    writeFileSync(path.join(root, 'src/mindcraft/mindcraft.js'), 'export function init() {}\nexport function startAgent() {}\nexport function destroyAgent() {}\nexport function createAgent(settings) { console.log("START:" + settings.profile.name); return Promise.resolve({success:true}); }');
    const write = (relative, value) => writeFileSync(path.join(root, relative), typeof value === 'string' ? value : JSON.stringify(value));
    write('profiles/good.json', { name: 'Good', model: 'fixture' });
    write('miku.json', { name: 'Legacy', model: 'fixture' });
    write('profiles/bad.json', '{broken');
    write('profiles/invalid.json', { model: 'fixture' });
    write('keys.json', { name: 'NotAProfile', model: 'fixture' });
    writeFileSync(path.join(temporary, 'outside.json'), JSON.stringify({ name: 'Outside', model: 'fixture' }));
    t.after(() => {
        const resolved = path.resolve(temporary);
        assert.equal(path.dirname(resolved), path.resolve(tmpdir()));
        assert.ok(path.basename(resolved).startsWith('mindcraft-startup-'));
        rmSync(resolved, { recursive: true, force: true });
    });
    return { root, temporary, write };
}
