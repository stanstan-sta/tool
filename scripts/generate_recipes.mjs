#!/usr/bin/env node
/**
 * Extract every vanilla recipe from minecraft-data and emit a compact JSON
 * suitable for the Fabric bridge mod's RECIPE_DATABASE.
 *
 * Output: tool/fabric-bridge-mod/src/main/resources/recipes_1.21.11.json
 *
 * The emitted JSON is a flat object mapping output item name → recipe:
 *   { "<item>": {
 *       "out": <outputCount>,
 *       "slots": [ {"i": <1-9>, "p": ["<pattern>", ...]}, ... ]
 *   } }
 *
 * Patterns are item IDs without the "minecraft:" prefix. Multiple patterns in
 * one slot act as OR — any match is acceptable (used for tag ingredients like
 * planks, logs, wools).
 *
 * Multi-variant recipes (the same item craftable multiple ways) are keyed by
 * "<item>" for the first variant and "<item>__altN" for the rest. The make
 * planner can try any variant; the first-listed is preferred.
 */

import { readFileSync, writeFileSync, mkdirSync } from 'fs';
import { dirname, resolve } from 'path';

const VERSION = '1.21.11';
const DATA_ROOT = resolve(process.cwd(), 'node_modules/minecraft-data/minecraft-data/data/pc', VERSION);
const OUT_PATH = resolve(process.cwd(), 'fabric-bridge-mod/src/main/resources/recipes_' + VERSION + '.json');

const items = JSON.parse(readFileSync(`${DATA_ROOT}/items.json`, 'utf8'));
const recipes = JSON.parse(readFileSync(`${DATA_ROOT}/recipes.json`, 'utf8'));
const id2name = new Map();
for (const i of items) id2name.set(i.id, i.name);

// Tag-like ingredient collapsing. The minecraft-data recipes already expand
// tags (e.g. "planks" becomes the specific plank type), but the craftin-table
// code can accept multiple variants in a single slot. We detect cases where
// the same (output, slot-position) has multiple item options across variants
// and merge them into a single OR pattern.
//
// Implementation: for each output item, collect every recipe variant. Group
// by canonical shape (slot positions that are filled vs empty). Within each
// group, merge the pattern lists.

/**
 * Normalize a mineflayer recipe into { slots: Map<1..9, Set<itemName>>, out }
 * or null if unparseable.
 */
function normalizeRecipe(mfr) {
    if (!mfr.result || mfr.result.count <= 0) return null;
    const outCount = mfr.result.count;
    const slots = new Map();

    if (Array.isArray(mfr.inShape)) {
        // Shaped: 3-row, 3-col matrix of item IDs (0 = empty).
        for (let r = 0; r < mfr.inShape.length; r++) {
            const row = mfr.inShape[r];
            for (let c = 0; c < row.length; c++) {
                const id = row[c];
                if (id == null || id === 0 || id === -1) continue;
                const idx = r * 3 + c + 1;
                const name = id2name.get(id);
                if (!name) continue;
                if (!slots.has(idx)) slots.set(idx, new Set());
                slots.get(idx).add(name);
            }
        }
    } else if (Array.isArray(mfr.ingredients)) {
        // Shapeless: ingredients can appear anywhere. Pack them into slots 1..N.
        let pos = 1;
        for (const ing of mfr.ingredients) {
            const id = Array.isArray(ing) ? ing[0] : ing;
            if (id == null || id === 0 || id === -1) continue;
            const name = id2name.get(id);
            if (!name) continue;
            if (!slots.has(pos)) slots.set(pos, new Set());
            slots.get(pos).add(name);
            pos++;
        }
    } else {
        return null;
    }

    if (slots.size === 0) return null;
    return { slots, out: outCount };
}

function signatureOf(slots) {
    // Signature = sorted list of filled positions. Same signature = same shape.
    return [...slots.keys()].sort((a, b) => a - b).join(',');
}

const out = {};
let skipped = 0;

for (const [itemIdStr, variants] of Object.entries(recipes)) {
    const itemId = Number(itemIdStr);
    const name = id2name.get(itemId);
    if (!name || name === 'air') continue;

    // Collect all variants keyed by their shape signature.
    const byShape = new Map(); // sig → { slots: Map<idx, Set<string>>, out: number }
    for (const v of variants) {
        const n = normalizeRecipe(v);
        if (!n) { skipped++; continue; }
        const sig = signatureOf(n.slots);
        if (!byShape.has(sig)) {
            byShape.set(sig, { slots: new Map(n.slots), out: n.out });
        } else {
            // Merge patterns at each slot.
            const existing = byShape.get(sig);
            for (const [idx, names] of n.slots.entries()) {
                if (!existing.slots.has(idx)) existing.slots.set(idx, new Set());
                const s = existing.slots.get(idx);
                for (const nm of names) s.add(nm);
            }
        }
    }

    if (byShape.size === 0) continue;

    // First shape → primary entry; extras → __altN.
    let i = 0;
    for (const [, shape] of byShape.entries()) {
        const key = i === 0 ? name : `${name}__alt${i}`;
        const slotList = [];
        for (const [idx, names] of shape.slots.entries()) {
            slotList.push({ i: idx, p: [...names].sort() });
        }
        slotList.sort((a, b) => a.i - b.i);
        out[key] = { out: shape.out, slots: slotList };
        i++;
    }
}

// Ensure output dir exists.
mkdirSync(dirname(OUT_PATH), { recursive: true });
writeFileSync(OUT_PATH, JSON.stringify(out));

const byteSize = JSON.stringify(out).length;
console.log(`Wrote ${Object.keys(out).length} recipes (${byteSize} bytes, ${skipped} skipped) → ${OUT_PATH}`);
