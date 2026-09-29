import { readFileSync, writeFileSync, renameSync, existsSync } from 'fs';

const MAX_SIGHTINGS = 200;
const MAX_WAYPOINT_NAME_LENGTH = 80;
const MAX_WAYPOINT_NOTE_LENGTH = 240;
const norm = n => String(n || '').trim().replace(/^minecraft:/i, '').toLowerCase();
const cleanStoredText = (value, maxLength) => String(value ?? '')
    .replace(/[\r\n]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, maxLength);
const dist2 = (a, b) => (a.x-b.x)**2 + (a.y-b.y)**2 + (a.z-b.z)**2;

function normalizeWaypoint(raw, fallbackName = '') {
    if (!raw || typeof raw !== 'object') return null;
    if (![raw.x, raw.y, raw.z].every(Number.isFinite)) return null;
    const name = cleanStoredText(raw.name ?? fallbackName, MAX_WAYPOINT_NAME_LENGTH).toLowerCase();
    if (!name) return null;
    return {
        name,
        x: Math.round(raw.x),
        y: Math.round(raw.y),
        z: Math.round(raw.z),
        dimension: cleanStoredText(raw.dimension || 'minecraft:overworld', 80) || 'minecraft:overworld',
        note: cleanStoredText(raw.note, MAX_WAYPOINT_NOTE_LENGTH),
        updatedAt: Number.isFinite(Number(raw.updatedAt)) ? Number(raw.updatedAt) : Date.now(),
    };
}

export class WorldMemory {
    constructor(filePath) {
        this.filePath = filePath;
        this.data = { version: 1, waypoints: Object.create(null), sightings: [], home: null };
    }

    setWaypoint(name, pos, note = '') {
        const key = cleanStoredText(name, MAX_WAYPOINT_NAME_LENGTH).toLowerCase();
        if (!key || !pos) return null;
        const wp = normalizeWaypoint({
            name: key,
            x: pos.x,
            y: pos.y,
            z: pos.z,
            dimension: pos.dimension,
            note,
            updatedAt: Date.now(),
        }, key);
        if (!wp) return null;
        this.data.waypoints[key] = wp;
        if (key === 'home' || key === 'base') this.data.home = wp;
        this.save();
        return wp;
    }

    getWaypoint(name) { return this.data.waypoints[cleanStoredText(name, MAX_WAYPOINT_NAME_LENGTH).toLowerCase()] || null; }
    listWaypoints() { return Object.values(this.data.waypoints); }

    recordSighting(kind, pos, dimension, persist = true) {
        const k = norm(kind);
        if (!k || !pos) return false;
        dimension = dimension || 'minecraft:overworld';
        const now = Date.now();
        for (const s of this.data.sightings) {
            if (s.kind === k && s.dimension === dimension && dist2(s, pos) <= 64) {
                if (now - s.seenAt < 60000) return false;
                s.seenAt = now;
                if (persist) this.save();
                return true;
            }
        }
        this.data.sightings.push({ kind: k, x: Math.round(pos.x), y: Math.round(pos.y), z: Math.round(pos.z),
                                   dimension, seenAt: now });
        if (this.data.sightings.length > MAX_SIGHTINGS) this.data.sightings.shift();
        if (persist) this.save();
        return true;
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
                if (raw && typeof raw === 'object' && (raw.version == null || raw.version === 1)) {
                    const waypoints = Object.create(null);
                    for (const [legacyKey, waypoint] of Object.entries(raw.waypoints || {})) {
                        const normalized = normalizeWaypoint(waypoint, legacyKey);
                        if (normalized) waypoints[normalized.name] = normalized;
                    }
                    this.data.waypoints = waypoints;
                    this.data.sightings = Array.isArray(raw.sightings) ? raw.sightings.slice(-MAX_SIGHTINGS) : [];
                    const home = normalizeWaypoint(raw.home);
                    this.data.home = home && this.data.waypoints[home.name]
                        ? this.data.waypoints[home.name]
                        : home;
                }
            }
        } catch { /* ignore */ }
        return this.data;
    }

    save() {
        try {
            const temporary = `${this.filePath}.tmp`;
            writeFileSync(temporary, JSON.stringify(this.data, null, 2));
            renameSync(temporary, this.filePath);
        } catch { /* non-fatal */ }
    }
}

export const NOTABLE_KINDS = new Set([
    'chest','barrel','furnace','blast_furnace','smoker','crafting_table','smithing_table',
    'enchanting_table','anvil','brewing_stand','bed','beacon','ender_chest','lodestone',
    'diamond_ore','deepslate_diamond_ore','ancient_debris','emerald_ore','deepslate_emerald_ore','spawner',
]);

export function extractNotablePositions(state) {
    const out = [];
    const blocks = [
        ...(Array.isArray(state?.nearby_block_entities) ? state.nearby_block_entities : []),
        ...(Array.isArray(state?.surface_map?.cells) ? state.surface_map.cells : []),
        ...(Array.isArray(state?.nearby_blocks) ? state.nearby_blocks : []),
        ...(Array.isArray(state?.blocks) ? state.blocks : []),
    ];
    const seen = new Set();
    for (const b of blocks) {
        const name = norm(b?.block || b?.name || b?.type || b?.id);
        if (!NOTABLE_KINDS.has(name)) continue;
        const x = Number(b.x), y = Number(b.y), z = Number(b.z);
        if (![x,y,z].every(Number.isFinite)) continue;
        const key = `${name}:${x}:${y}:${z}`;
        if (seen.has(key)) continue;
        seen.add(key);
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
