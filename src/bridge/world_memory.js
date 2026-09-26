import { readFileSync, writeFileSync, existsSync } from 'fs';

const MAX_SIGHTINGS = 200;
const norm = n => String(n || '').replace(/^minecraft:/i, '').toLowerCase();
const dist2 = (a, b) => (a.x-b.x)**2 + (a.y-b.y)**2 + (a.z-b.z)**2;

export class WorldMemory {
    constructor(filePath) {
        this.filePath = filePath;
        this.data = { waypoints: {}, sightings: [], home: null };
    }

    setWaypoint(name, pos, note = '') {
        const key = String(name || '').trim().toLowerCase();
        if (!key || !pos) return null;
        const wp = { name: key, x: Math.round(pos.x), y: Math.round(pos.y), z: Math.round(pos.z),
                     dimension: pos.dimension || 'minecraft:overworld', note, updatedAt: Date.now() };
        this.data.waypoints[key] = wp;
        if (key === 'home' || key === 'base') this.data.home = wp;
        this.save();
        return wp;
    }

    getWaypoint(name) { return this.data.waypoints[String(name||'').trim().toLowerCase()] || null; }
    listWaypoints() { return Object.values(this.data.waypoints); }

    recordSighting(kind, pos, dimension) {
        const k = norm(kind);
        if (!k || !pos) return;
        for (const s of this.data.sightings) {
            if (s.kind === k && s.dimension === dimension && dist2(s, pos) <= 64) { s.seenAt = Date.now(); this.save(); return; }
        }
        this.data.sightings.push({ kind: k, x: Math.round(pos.x), y: Math.round(pos.y), z: Math.round(pos.z),
                                   dimension: dimension || 'minecraft:overworld', seenAt: Date.now() });
        if (this.data.sightings.length > MAX_SIGHTINGS) this.data.sightings.shift();
        this.save();
    }

    nearestSighting(kind, fromPos, dimension) {
        const k = norm(kind);
        let best = null, bestD = Infinity;
        for (const s of this.data.sightings) {
            if (s.kind !== k) continue;
            if (dimension && s.dimension !== dimension) continue;
            const d = dist2(s, fromPos);
            if (d < bestD) { bestD = d; best = s; }
        }
        return best;
    }

    describe(limit = 6) {
        const parts = [];
        const wps = this.listWaypoints().slice(0, limit).map(w => `${w.name} (${w.x},${w.y},${w.z})`);
        if (wps.length) parts.push(`Waypoints: ${wps.join('; ')}`);
        if (this.data.sightings.length) {
            const kinds = {};
            for (const s of this.data.sightings) kinds[s.kind] = (kinds[s.kind] || 0) + 1;
            const top = Object.entries(kinds).sort((a,b)=>b[1]-a[1]).slice(0, limit).map(([k,n]) => `${k} x${n}`);
            parts.push(`Notable blocks seen: ${top.join(', ')}`);
        }
        return parts.length ? parts.join('\n') : 'No places remembered yet.';
    }

    load() {
        try {
            if (existsSync(this.filePath)) {
                const raw = JSON.parse(readFileSync(this.filePath, 'utf8'));
                if (raw && typeof raw === 'object') {
                    this.data.waypoints = raw.waypoints || {};
                    this.data.sightings = Array.isArray(raw.sightings) ? raw.sightings : [];
                    this.data.home = raw.home || null;
                }
            }
        } catch { /* ignore */ }
        return this.data;
    }

    save() {
        try { writeFileSync(this.filePath, JSON.stringify(this.data, null, 2)); } catch { /* non-fatal */ }
    }
}

export const NOTABLE_KINDS = new Set([
    'chest','barrel','furnace','blast_furnace','smoker','crafting_table','smithing_table',
    'enchanting_table','anvil','brewing_stand','bed','beacon','ender_chest','lodestone',
    'diamond_ore','deepslate_diamond_ore','ancient_debris','emerald_ore','deepslate_emerald_ore','spawner',
]);

export function extractNotablePositions(state) {
    const out = [];
    const blocks = Array.isArray(state?.nearby_blocks) ? state.nearby_blocks
                 : Array.isArray(state?.blocks) ? state.blocks : [];
    for (const b of blocks) {
        const name = norm(b?.name || b?.block || b?.type || b?.id);
        if (!NOTABLE_KINDS.has(name)) continue;
        const x = Number(b.x), y = Number(b.y), z = Number(b.z);
        if (![x,y,z].every(Number.isFinite)) continue;
        out.push({ kind: name, pos: { x, y, z } });
    }
    return out;
}

export function parseWaypointCommand(message) {
    const t = String(message || '').trim();
    let m = t.match(/^waypoint\s*[:=]\s*(.+)$/i);
    if (m) return { kind: 'set', name: m[1].trim() };
    if (/^waypoints$/i.test(t)) return { kind: 'list' };
    m = t.match(/^(?:goto|go to)\s+waypoint\s+(.+)$/i);
    if (m) return { kind: 'goto', name: m[1].trim() };
    return null;
}
