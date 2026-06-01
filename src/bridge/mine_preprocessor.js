/**
 * Deterministic mine-action preprocessing.
 *
 * Before any mine action reaches FabricBridge.sendBatch, this module:
 *   1. Normalizes the target name (strips minecraft:, lowercases, etc.)
 *   2. Expands ore variants (e.g. iron_ore → iron_ore, deepslate_iron_ore)
 *   3. Determines the preferred dimension of the target
 *   4. If the target dimension differs from the player's current dimension,
 *      probes nearby blocks via readBlocks to avoid unnecessary portal travel
 *   5. Prepends portal-travel actions when the target is not available locally
 */

// ── Ore variant expansion ──────────────────────────────────────────────
// Maps canonical ore name → list of all block variants that produce it.
const ORE_VARIANTS = {
    iron_ore:        ['iron_ore', 'deepslate_iron_ore'],
    copper_ore:      ['copper_ore', 'deepslate_copper_ore'],
    gold_ore:        ['gold_ore', 'deepslate_gold_ore', 'nether_gold_ore'],
    coal_ore:        ['coal_ore', 'deepslate_coal_ore'],
    diamond_ore:     ['diamond_ore', 'deepslate_diamond_ore'],
    redstone_ore:    ['redstone_ore', 'deepslate_redstone_ore'],
    lapis_ore:       ['lapis_ore', 'deepslate_lapis_ore'],
    emerald_ore:     ['emerald_ore', 'deepslate_emerald_ore'],
    nether_quartz_ore: ['nether_quartz_ore'],
    ancient_debris:  ['ancient_debris'],
};

// Shorthand / raw / alias → canonical ore name.
const TARGET_ALIASES = {
    raw_iron:        'iron_ore',
    raw_copper:      'copper_ore',
    raw_gold:        'gold_ore',
    coal:            'coal_ore',
    diamond:         'diamond_ore',
    emerald:         'emerald_ore',
    redstone:        'redstone_ore',
    lapis:           'lapis_ore',
    lapis_lazuli:    'lapis_ore',
    quartz:          'nether_quartz_ore',
    nether_quartz:   'nether_quartz_ore',
    netherite_scrap: 'ancient_debris',
};

// Reverse map: variant block → canonical ore name.
const VARIANT_TO_CANONICAL = new Map();
for (const [canonical, variants] of Object.entries(ORE_VARIANTS)) {
    for (const v of variants) {
        VARIANT_TO_CANONICAL.set(v, canonical);
    }
}

// ── Block → dimension mapping ──────────────────────────────────────────
// Blocks that exist only in specific dimensions (beyond the standard sets
// used by getItemDimension in bridge_agent.js).  Blocks not listed here
// are assumed overworld.
const DIMENSION_MAP = new Map([
    ['netherrack',              'nether'],
    ['nether_gold_ore',         'nether'],
    ['nether_quartz_ore',       'nether'],
    ['ancient_debris',          'nether'],
    ['glowstone',               'nether'],
    ['soul_sand',               'nether'],
    ['soul_soil',               'nether'],
    ['magma_block',             'nether'],
    ['blackstone',              'nether'],
    ['basalt',                  'nether'],
    ['smooth_basalt',           'nether'],
    ['crimson_nylium',          'nether'],
    ['warped_nylium',           'nether'],
    ['crimson_stem',            'nether'],
    ['warped_stem',             'nether'],
    ['crimson_hyphae',          'nether'],
    ['warped_hyphae',           'nether'],
    ['nether_bricks',           'nether'],
    ['red_nether_bricks',       'nether'],
    ['quartz_block',            'nether'],
    ['nether_wart_block',       'nether'],
    ['warped_wart_block',       'nether'],
    ['gilded_blackstone',       'nether'],
    ['end_stone',               'end'],
    ['end_stone_bricks',        'end'],
    ['chorus_plant',            'end'],
    ['chorus_flower',           'end'],
    ['dragon_egg',              'end'],
]);

// ── Normalization ──────────────────────────────────────────────────────

/**
 * Strip minecraft: prefix, lowercase, collapse non-alphanum to _.
 */
function normalizeBlockName(name) {
    return String(name || '')
        .replace(/^minecraft:/i, '')
        .toLowerCase()
        .replace(/[^a-z0-9_]+/g, '_')
        .replace(/_+/g, '_')
        .replace(/^_+|_+$/g, '');
}

/**
 * Normalize a dimension string to the short form used internally
 * (overworld / nether / end).  Minecraft sends "the_nether" and
 * "the_end" — we strip the leading "the_" for consistency with
 * getTargetDimension output.
 */
function normalizeDimension(dim) {
    const name = normalizeBlockName(dim);
    if (name === 'the_nether' || name === 'nether') return 'nether';
    if (name === 'the_end' || name === 'end') return 'end';
    return 'overworld';
}

/**
 * Given a raw mine target, return the canonical ore name and the full
 * list of block variants that satisfy the request.
 *
 * @param {string} rawTarget  e.g. "raw_iron", "diamond", "nether_quartz"
 * @returns {{ canonical: string, variants: string[] }}
 */
function resolveTarget(rawTarget) {
    const normalized = normalizeBlockName(rawTarget);

    // Direct alias lookup (raw_iron, coal, diamond, etc.)
    if (TARGET_ALIASES[normalized]) {
        const canonical = TARGET_ALIASES[normalized];
        return { canonical, variants: ORE_VARIANTS[canonical] || [canonical] };
    }

    // Already a canonical ore name?
    if (ORE_VARIANTS[normalized]) {
        return { canonical: normalized, variants: ORE_VARIANTS[normalized] };
    }

    // A variant we recognise?  Map back to canonical.
    const canonical = VARIANT_TO_CANONICAL.get(normalized);
    if (canonical) {
        return { canonical, variants: ORE_VARIANTS[canonical] || [canonical] };
    }

    // Unknown block — no expansion, treat the normalized name as canonical.
    return { canonical: normalized, variants: [normalized] };
}

/**
 * Determine which dimension a target block belongs to.
 * Uses the same logic as getItemDimension in bridge_agent.js
 * (NETHER_BLOCKS / END_BLOCKS sets) plus our DIMENSION_MAP.
 *
 * @param {string} canonical  Normalized block name.
 * @returns {'overworld'|'nether'|'end'}
 */
function getTargetDimension(canonical) {
    if (DIMENSION_MAP.has(canonical)) {
        return DIMENSION_MAP.get(canonical);
    }
    // Fall back to NETHER_BLOCKS / END_BLOCKS from bridge_agent.js.
    // Those sets are not exported, so we duplicate the membership check
    // here for the ore-specific blocks that matter for portal travel.
    // Netherrack, nether_gold_ore, nether_quartz_ore, ancient_debris
    // are all in DIMENSION_MAP above, so this is a safety net.
    return 'overworld';
}

function parseRawMineCommand(command) {
    const text = String(command || '').trim();
    const parts = text.split(/\s+/).filter(Boolean);
    if (parts.length < 2 || parts[0].toLowerCase() !== '#mine') return null;

    let targetIndex = 1;
    let count = null;
    if (/^\d+$/.test(parts[1])) {
        count = Number(parts[1]);
        targetIndex = 2;
    }

    const target = parts[targetIndex];
    if (!target) return null;
    return { target, count };
}

function getMineActionInfo(action) {
    if (!action || typeof action !== 'object') return null;
    if (action.type === 'mine' && action.target) {
        const { canonical, variants } = resolveTarget(action.target);
        return {
            canonical,
            variants,
            normalizedAction: { ...action, target: canonical },
        };
    }
    if (action.type === 'raw_command') {
        const parsed = parseRawMineCommand(action.command);
        if (!parsed) return null;
        const { canonical, variants } = resolveTarget(parsed.target);
        const canonicalCommand = parsed.count !== null
            ? `#mine ${parsed.count} ${canonical}`
            : `#mine ${canonical}`;
        return {
            canonical,
            variants,
            normalizedAction: { type: 'raw_command', provider: action.provider || 'baritone_chat', command: canonicalCommand },
        };
    }
    return null;
}

function isReturnActionForDimension(action, dimension) {
    if (!action || typeof action !== 'object') return false;
    if (dimension === 'overworld' && action.type === 'return_to_overworld') return true;
    if (action.type === 'portal_travel') {
        return normalizeDimension(action.dimension) === dimension;
    }
    return false;
}

function makeTravelAction(dimension) {
    if (dimension === 'overworld') {
        return { type: 'return_to_overworld', provider: 'baritone_chat' };
    }
    return { type: 'portal_travel', provider: 'baritone_chat', dimension };
}

// ── Nearby-block probe ─────────────────────────────────────────────────

/**
 * Check whether any of the candidate block IDs appear near the player.
 * Uses the /read_blocks endpoint with a 24×24×24 box (the mod-side cap).
 *
 * @param {import('./fabric_bridge.js').FabricBridge} bridge
 * @param {{ x: number, y: number, z: number }} playerPos
 * @param {string[]} candidateIds  Block IDs to look for (e.g. ["iron_ore","deepslate_iron_ore"]).
 * @returns {Promise<boolean>}  true if at least one candidate is found nearby.
 */
async function hasNearbyTarget(bridge, playerPos, candidateIds) {
    if (!bridge || typeof bridge.readBlocks !== 'function') return false;
    if (!playerPos || !Number.isFinite(playerPos.x) || !Number.isFinite(playerPos.y) || !Number.isFinite(playerPos.z)) return false;

    const x = Math.round(playerPos.x);
    const y = Math.round(playerPos.y);
    const z = Math.round(playerPos.z);
    const half = 12; // 24 / 2 = 12 block radius in each axis

    try {
        const result = await bridge.readBlocks({
            x: x - half,
            y: Math.max(-64, y - half),
            z: z - half,
            w: 24,
            h: 24,
            l: 24,
        });
        if (!result || !Array.isArray(result.blocks)) return false;

        const idSet = new Set(result.blocks.map(b => normalizeBlockName(b)));
        return candidateIds.some(id => idSet.has(id));
    } catch {
        // readBlocks unavailable or timed out — treat as "not found"
        // so the caller falls back to dimension travel.
        return false;
    }
}

// ── Public API ─────────────────────────────────────────────────────────

/**
 * Preprocess an array of actions, expanding mine variants and prepending
 * portal-travel actions when the target dimension differs from the
 * player's current dimension and no nearby variant is found.
 *
 * Non-mine actions pass through unchanged.
 *
 * @param {Array<object>} actions
 * @param {object} state  Current Fabric state (dimension, x, y, z).
 * @param {import('./fabric_bridge.js').FabricBridge} bridge  For readBlocks probe.
 * @returns {Promise<Array<object>>}  Expanded action list.
 */
export async function preprocessMineActions(actions, state, bridge) {
    if (!Array.isArray(actions) || actions.length === 0) return actions;

    const currentDim = normalizeDimension(state?.dimension || 'minecraft:overworld');
    const playerPos = { x: state?.x, y: state?.y, z: state?.z };

    const out = [];
    let virtualDim = currentDim;
    let mustReturnToDim = null;

    for (const action of actions) {
        const mineInfo = getMineActionInfo(action);

        if (!mineInfo) {
            if (mustReturnToDim && !isReturnActionForDimension(action, mustReturnToDim)) {
                out.push(makeTravelAction(mustReturnToDim));
                virtualDim = mustReturnToDim;
                mustReturnToDim = null;
            } else if (mustReturnToDim && isReturnActionForDimension(action, mustReturnToDim)) {
                virtualDim = mustReturnToDim;
                mustReturnToDim = null;
            }

            if (action?.type === 'portal_travel') {
                virtualDim = normalizeDimension(action.dimension);
            } else if (action?.type === 'return_to_overworld') {
                virtualDim = 'overworld';
            }
            out.push(action);
            continue;
        }

        const { canonical, variants, normalizedAction } = mineInfo;
        const targetDim = getTargetDimension(canonical);

        if (targetDim === virtualDim) {
            // Same dimension — no portal travel needed.
            out.push(normalizedAction);
            continue;
        }

        // Different dimension — probe nearby blocks before committing
        // to an expensive portal trip.
        const nearby = virtualDim === currentDim
            ? await hasNearbyTarget(bridge, playerPos, variants)
            : false;

        if (nearby) {
            // At least one variant exists within 24 blocks — skip portal.
            out.push(normalizedAction);
            continue;
        }

        // No nearby target — prepend portal travel.
        out.push(makeTravelAction(targetDim));
        virtualDim = targetDim;
        if (mustReturnToDim === targetDim) {
            mustReturnToDim = null;
        } else if (mustReturnToDim === null && currentDim !== targetDim) {
            mustReturnToDim = currentDim;
        }
            // Target is overworld but player is in nether/end — return first.
        out.push(normalizedAction);
    }

    if (mustReturnToDim) {
        out.push(makeTravelAction(mustReturnToDim));
    }

    return out;
}

// ── Named exports (also useful for testing) ────────────────────────────
export { resolveTarget, getTargetDimension, normalizeBlockName, parseRawMineCommand };
