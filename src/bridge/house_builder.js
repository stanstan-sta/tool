/**
 * High-level house builder.
 *
 * Flow:
 *   1. The LLM emits a {type:"build_house"} action. The builder inspects
 *      episodic memory for a pending clarifying dialog; if unanswered, it
 *      stashes the request and returns a follow-up question for the user.
 *   2. Once all required parameters are known, it picks a template,
 *      generates an in-memory schematic, and dispatches a single
 *      {type:"build_schematic"} action to the mod.
 *   3. Node polls the mod's /state. When `builder.active` transitions from
 *      true → missing, the build is done. Node then calls /read_blocks over
 *      the bounding box and runs the layer-1 validator.
 *   4. Validator report is injected as a system message so the LLM can react
 *      (apologize, patch, or call it done).
 *
 * No disk NBT is written — the schematic proxy is built in-memory inside the mod.
 */

import { TEMPLATES, pickTemplate, inferBiome } from './house_templates.js';

// Required dialog slots for a build_house request. The LLM can fill any
// subset; anything missing becomes a follow-up question to the user.
const REQUIRED_SLOTS = ['template', 'size', 'material'];

/**
 * Walk the schematic's block array and count how many of each block type
 * are actually placed (excluding air). Returns a Map<itemId, count>.
 *
 * Note: block IDs are translated to item IDs where they differ (for example,
 * `redstone_wire` → `redstone`, doors keep their identifier). The mapping
 * here is deliberately minimal; the planner falls back to the block id
 * directly when no translation exists.
 */
const BLOCK_TO_ITEM_OVERRIDES = {
    'minecraft:redstone_wire': 'redstone',
    'minecraft:wall_torch': 'torch',
    'minecraft:wall_sign': 'oak_sign',
    'minecraft:red_bed_head': 'red_bed',
    'minecraft:red_bed_foot': 'red_bed',
};

function blockIdToItemId(blockId) {
    const raw = String(blockId || '').replace(/^minecraft:/, '').toLowerCase();
    const qualified = `minecraft:${raw}`;
    if (BLOCK_TO_ITEM_OVERRIDES[qualified]) return BLOCK_TO_ITEM_OVERRIDES[qualified];
    return raw;
}

export function tallySchematicMaterials(schematic) {
    const { size, palette, blocksU16 } = schematic;
    const raw = new Map();
    for (let i = 0; i < size.x * size.y * size.z; i++) {
        const idx = blocksU16.readUInt16LE(i * 2);
        if (idx < 0 || idx >= palette.length) continue;
        const blockId = palette[idx];
        if (!blockId || blockId === 'minecraft:air') continue;
        const item = blockIdToItemId(blockId);
        raw.set(item, (raw.get(item) || 0) + 1);
    }
    // Halve counts for multi-cell items (doors, beds, tall plants).
    const counts = new Map();
    for (const [item, n] of raw.entries()) {
        if (isTwoCellItem(item)) {
            counts.set(item, Math.ceil(n / 2));
        } else {
            counts.set(item, n);
        }
    }
    return counts;
}

function isTwoCellItem(item) {
    return item.endsWith('_door')
        || item.endsWith('_bed')
        || item === 'sunflower' || item === 'lilac' || item === 'rose_bush' || item === 'peony'
        || item === 'large_fern' || item === 'tall_grass' || item === 'small_dripleaf';
}

/**
 * Recursively roll up a shopping list into its raw-resource prerequisites.
 * Given placed-block counts, this walks the wiki recipe graph, and returns
 * a new Map of items to bundles at each tier:
 *
 *   { raw: Map<item, count>, crafts: Map<item, count> }
 *
 * `raw` is the set of items that have no recipe (logs, cobblestone, ores,
 * sand, etc.) — these are what we need to mine/smelt to satisfy the build.
 * `crafts` is every intermediate (planks, sticks, doors, chests, etc.) that
 * we'll have to craft from raws.
 *
 * The rollup uses `wiki.data.recipes.crafting` and smelting; if an item has
 * no recipe and no smelt, it's treated as raw.
 */
export function rollUpMaterials(placedCounts, wikiData) {
    const craftRecipes = wikiData?.recipes?.crafting || {};
    const smeltRecipes = wikiData?.recipes?.smelting || {};

    const raw = new Map();
    const crafts = new Map();

    // Queue of items still to resolve.
    const todo = [];
    for (const [item, count] of placedCounts.entries()) {
        todo.push({ item, count });
    }

    while (todo.length > 0) {
        const { item, count } = todo.shift();
        if (count <= 0) continue;

        const craftRecipe = craftRecipes[item];
        if (craftRecipe && craftRecipe.ingredients) {
            // Record this craft-intermediate.
            crafts.set(item, (crafts.get(item) || 0) + count);
            const output = craftRecipe.output || 1;
            const batches = Math.ceil(count / output);
            for (const [ingredient, perBatch] of Object.entries(craftRecipe.ingredients)) {
                const resolved = resolveIngredientChoice(ingredient, placedCounts);
                todo.push({ item: resolved, count: batches * perBatch });
            }
            continue;
        }

        const smeltRecipe = smeltRecipes[item];
        if (smeltRecipe && smeltRecipe.input) {
            crafts.set(item, (crafts.get(item) || 0) + count);
            todo.push({ item: smeltRecipe.input, count });
            // Fuel is handled by the mod's planner at craft time; not folded in here.
            continue;
        }

        // No recipe or smelt: treat as raw.
        raw.set(item, (raw.get(item) || 0) + count);
    }

    return { raw, crafts };
}

/**
 * Resolve a generic ingredient slot (e.g. "oak_planks" in a chest recipe) to
 * the actual item we'll use. Preference order:
 *   1. A matching item already in the placed-blocks tally (so we pick the
 *      same wood species for consistency).
 *   2. The literal ingredient string.
 * Later: factor in current inventory preference.
 */
function resolveIngredientChoice(ingredient, placedCounts) {
    // The wiki DB uses "oak_planks" as a stand-in for "any planks" in some
    // recipes. If the placed schematic already uses a specific planks type,
    // route to that type for coherence.
    if (ingredient === 'oak_planks') {
        for (const item of placedCounts.keys()) {
            if (item.endsWith('_planks') && item !== 'oak_planks') return item;
        }
    }
    return ingredient;
}

/**
 * Subtract the current inventory from the required materials. Returns a Map
 * of item → shortage count (only items we need more of).
 */
export function computeMaterialShortages(required, inventory) {
    const have = new Map();
    for (const stack of inventory || []) {
        if (!stack || !stack.item) continue;
        const name = String(stack.item).replace(/^minecraft:/, '').toLowerCase();
        have.set(name, (have.get(name) || 0) + (Number(stack.count) || 0));
    }
    const shortages = new Map();
    for (const [item, needed] of required.entries()) {
        const owned = have.get(item) || 0;
        if (owned < needed) shortages.set(item, needed - owned);
    }
    return shortages;
}

/**
 * Produce a list of craft actions that will gather/craft the shortages, in the
 * order they should run. Order doesn't strictly matter — the bridge planner
 * inside CommandExecutor handles prerequisites per-craft — but keeping bulky
 * blocks last avoids stash overflow.
 */
export function planBuildMaterialActions(shortages) {
    const out = [];
    for (const [item, count] of shortages.entries()) {
        out.push({
            type: 'craft',
            provider: 'baritone_chat',
            item,
            count,
        });
    }
    return out;
}

export function packageMaterialSchematic(schematic, origin, inventory, wikiData) {
    const schematicAction = {
        type: 'build_schematic',
        provider: 'baritone_chat',
        name: schematic.name,
        origin: { x: Math.round(origin.x), y: Math.round(origin.y), z: Math.round(origin.z) },
        size: schematic.size,
        palette: schematic.palette,
        blocks: schematic.blocksU16.toString('base64'),
    };
    const placed = tallySchematicMaterials(schematic);
    const { raw, crafts } = wikiData ? rollUpMaterials(placed, wikiData) : { raw: placed, crafts: new Map() };
    // Combine raw + crafts into one required-map keyed by item.
    const required = new Map();
    for (const [k, v] of raw.entries()) required.set(k, (required.get(k) || 0) + v);
    for (const [k, v] of crafts.entries()) required.set(k, (required.get(k) || 0) + v);
    const shortages = computeMaterialShortages(required, inventory);
    const prereqs = planBuildMaterialActions(shortages);
    return { schematicAction, required, shortages, prereqs };
}

/**
 * Package a generated schematic into the JSON shape expected by the mod's
 * build_schematic action.
 */
function packageSchematic(schematic, origin) {
    return {
        type: 'build_schematic',
        provider: 'baritone_chat',
        name: schematic.name,
        origin: { x: Math.round(origin.x), y: Math.round(origin.y), z: Math.round(origin.z) },
        size: schematic.size,
        palette: schematic.palette,
        blocks: schematic.blocksU16.toString('base64'),
    };
}

/**
 * Merge user answers into a pending build request. Called when the LLM
 * emits a build_house action again with updated fields, or when the user
 * replies to the clarifying question in chat.
 */
export function mergeBuildRequest(existing, next) {
    return { ...(existing || {}), ...(next || {}) };
}

/**
 * Given the known request + state, return either
 *   { ready: false, question: "<follow-up>" }  to ask
 *   { ready: true,  schematic, origin }         to build
 * Picks sensible defaults: biome from state, material from biome, size="small",
 * template="cabin" if unspecified, origin = 2 blocks in front of the player.
 */
export function resolveBuildRequest(request, state) {
    const req = request || {};

    // Which dialog slots are still unanswered?
    const missing = REQUIRED_SLOTS.filter(slot => req[slot] == null);
    if (missing.length > 0) {
        // Ask one question at a time, in a fixed order.
        const next = missing[0];
        const questions = {
            template: 'Cabin, tower, or pit shelter?',
            size:     'Small, medium, or large?',
            material: 'What material should the walls be? (oak, spruce, cobblestone, sandstone)',
        };
        return { ready: false, question: questions[next] };
    }

    const template = pickTemplate(req.template);
    const savedPrefix = typeof req.template === 'string' && req.template.toLowerCase().startsWith('saved:')
        ? req.template.slice(6)
        : null;
    if (!template) {
        // Check saved/scanned templates (user-supplied via scan_building).
        const saved = savedPrefix
            ? loadSavedTemplate(savedPrefix)
            : (req.template ? loadSavedTemplate(req.template) : null);
        if (!saved) {
            const avail = listSavedTemplates();
            const hint = avail.length > 0 ? ` Saved templates: ${avail.join(', ')}.` : '';
            return { ready: false, question: `I don't have a "${req.template}" template. Try cabin, tower, pit, or saved:<name>.${hint}` };
        }
        req._savedSchematic = saved;
    }

    const biome = req.biome || inferBiome(state);
    const materialOverride = {};
    if (req.material) {
        const m = String(req.material).toLowerCase();
        if (['oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'cherry', 'mangrove', 'bamboo'].includes(m)) {
            materialOverride.wall = `${m}_planks`;
            materialOverride.log  = `${m}_log`;
            materialOverride.roof = `${m}_planks`;
            materialOverride.floor = `${m}_planks`;
        } else if (m === 'cobblestone' || m === 'cobble' || m === 'stone') {
            materialOverride.wall = 'cobblestone';
            materialOverride.roof = 'stone_bricks';
            materialOverride.floor = 'stone';
        } else if (m === 'sandstone' || m === 'sand') {
            materialOverride.wall = 'sandstone';
            materialOverride.roof = 'smooth_sandstone';
            materialOverride.floor = 'sandstone';
        }
    }

    const size = ['small', 'medium', 'large'].includes(String(req.size).toLowerCase())
        ? String(req.size).toLowerCase()
        : 'small';

    let schematic;
    try {
        if (req._savedSchematic) {
            schematic = req._savedSchematic;
        } else {
            schematic = template({
                size,
                biome,
                material: materialOverride,
                floors: req.floors || 3,
                window: req.window !== false,
            });
        }
    } catch (err) {
        return { ready: false, question: `Template generation failed: ${err.message}` };
    }

    // Origin: 2 blocks in the direction the player is facing, at current y.
    // When the state doesn't expose yaw we fall back to 2 blocks north (+Z).
    let ox, oy, oz;
    if (req.origin && Number.isFinite(req.origin.x)) {
        ox = req.origin.x;
        oy = req.origin.y;
        oz = req.origin.z;
    } else if (state && Number.isFinite(state.x)) {
        ox = state.x + 2;
        oy = state.y;
        oz = state.z + 2;
    } else {
        return { ready: false, question: 'I need a starting position. Where should I build? (say "here" or give x y z)' };
    }

    return {
        ready: true,
        template: req.template,
        size,
        material: req.material || null,
        biome,
        schematic,
        origin: { x: ox, y: oy, z: oz },
        action: packageSchematic(schematic, { x: ox, y: oy, z: oz }),
        // Caller merges inventory and decides whether to prepend crafts.
        required: tallySchematicMaterials(schematic),
    };
}

/**
 * Layer-1 house validator. Takes the box read-back from /read_blocks and the
 * expected schematic bounds. Returns { score, pass, failures: [...] }.
 *
 * Checks:
 *   1. Enclosure: every interior air cell has a non-transparent block on all
 *      six axis-aligned boundaries.
 *   2. Roof: every interior column has a non-air block directly above it.
 *   3. Floor: every interior column has a non-air block directly below it.
 *   4. Door: at least one door block exists in the perimeter.
 *   5. Headroom: interior y-span ≥ 2.
 */
export function validateHouse(boxRead, expected) {
    if (!boxRead || !Array.isArray(boxRead.blocks)) {
        return { score: 0, pass: false, failures: ['no_block_readback'] };
    }
    const [w, h, l] = boxRead.size;
    const blocks = boxRead.blocks;
    const at = (x, y, z) => {
        if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= l) return null;
        return blocks[(y * l + z) * w + x];
    };
    const isAir = (id) => id === 'minecraft:air' || id === 'minecraft:cave_air' || id === 'minecraft:void_air';
    const isDoor = (id) => id && id.endsWith('_door');
    const isGlass = (id) => id && (id.endsWith('_glass') || id === 'minecraft:glass' || id.endsWith('_glass_pane'));

    const failures = [];

    // 1. Enclosure: walk the interior (1..w-2, 1..h-2, 1..l-2) looking for air cells
    //    that touch the outside via an air/missing neighbor on the perimeter face.
    //    Air cells directly above a door block are legitimate openings — ignore them.
    const doorColumns = new Set(); // "x,z" strings where a door exists on the perimeter
    for (let y = 0; y < h; y++) {
        for (let z = 0; z < l; z++) {
            for (let x = 0; x < w; x++) {
                const onEdge = (x === 0 || x === w - 1 || z === 0 || z === l - 1);
                if (!onEdge) continue;
                if (isDoor(at(x, y, z))) doorColumns.add(`${x},${z}`);
            }
        }
    }

    let interiorCells = 0;
    let leakCells = 0;
    for (let y = 1; y < h - 1; y++) {
        for (let z = 1; z < l - 1; z++) {
            for (let x = 1; x < w - 1; x++) {
                const cell = at(x, y, z);
                if (!isAir(cell)) continue;
                interiorCells++;
                const outsideAirs = [
                    x - 1 === 0      ? { n: at(0, y, z),      px: 0,      pz: z      } : null,
                    x + 1 === w - 1  ? { n: at(w - 1, y, z),  px: w - 1,  pz: z      } : null,
                    y - 1 === 0      ? { n: at(x, 0, z),      px: x,      pz: z      } : null,
                    y + 1 === h - 1  ? { n: at(x, h - 1, z),  px: x,      pz: z      } : null,
                    z - 1 === 0      ? { n: at(x, y, 0),      px: x,      pz: 0      } : null,
                    z + 1 === l - 1  ? { n: at(x, y, l - 1),  px: x,      pz: l - 1  } : null,
                ];
                for (const entry of outsideAirs) {
                    if (!entry) continue;
                    if (!isAir(entry.n)) continue;
                    // Air on the perimeter column of a door counts as the doorway.
                    if (doorColumns.has(`${entry.px},${entry.pz}`)) continue;
                    leakCells++;
                    break;
                }
            }
        }
    }
    if (interiorCells === 0) failures.push('no_interior');
    if (leakCells > 0) failures.push(`enclosure_leaks:${leakCells}`);

    // 2. Roof: every interior column has a non-air block somewhere at y = h-1
    //    (top slab). Counts air cells in the roof plane.
    let roofAir = 0;
    for (let z = 1; z < l - 1; z++) {
        for (let x = 1; x < w - 1; x++) {
            if (isAir(at(x, h - 1, z))) roofAir++;
        }
    }
    if (roofAir > 0) failures.push(`roof_holes:${roofAir}`);

    // 3. Floor (y=0 plane).
    let floorAir = 0;
    for (let z = 1; z < l - 1; z++) {
        for (let x = 1; x < w - 1; x++) {
            if (isAir(at(x, 0, z))) floorAir++;
        }
    }
    if (floorAir > 0) failures.push(`floor_holes:${floorAir}`);

    // 4. Door presence.
    let doors = 0;
    for (let y = 0; y < h; y++) {
        for (let z = 0; z < l; z++) {
            for (let x = 0; x < w; x++) {
                const onEdge = (x === 0 || x === w - 1 || z === 0 || z === l - 1);
                if (!onEdge) continue;
                if (isDoor(at(x, y, z))) doors++;
            }
        }
    }
    if (doors === 0) failures.push('no_door');

    // 5. Headroom.
    if (h < 3) failures.push(`low_headroom:${h}`);

    // ─── Layer 2: functional fixtures ────────────────────────────────────
    const fixtures = {
        bed: false,
        craft: false,
        furnace: false,
        chest: false,
        torch: 0,
    };
    for (let y = 0; y < h; y++) {
        for (let z = 0; z < l; z++) {
            for (let x = 0; x < w; x++) {
                const id = at(x, y, z);
                if (!id) continue;
                const short = id.replace(/^minecraft:/, '');
                if (short.endsWith('_bed') || short === 'bed') fixtures.bed = true;
                else if (short === 'crafting_table') fixtures.craft = true;
                else if (short === 'furnace' || short === 'blast_furnace' || short === 'smoker') fixtures.furnace = true;
                else if (short === 'chest' || short === 'barrel' || short === 'trapped_chest') fixtures.chest = true;
                else if (short === 'torch' || short === 'wall_torch' || short === 'soul_torch' || short === 'lantern') fixtures.torch++;
            }
        }
    }
    if (!fixtures.bed)     failures.push('no_bed');
    if (!fixtures.craft)   failures.push('no_crafting_table');
    if (!fixtures.furnace) failures.push('no_furnace');
    if (!fixtures.chest)   failures.push('no_storage');
    if (fixtures.torch === 0) failures.push('no_light_source');

    // ─── Layer 3: style coherence ────────────────────────────────────────
    const nonAirUses = new Map();
    let windows = 0;
    for (let y = 0; y < h; y++) {
        for (let z = 0; z < l; z++) {
            for (let x = 0; x < w; x++) {
                const id = at(x, y, z);
                if (!id || isAir(id)) continue;
                nonAirUses.set(id, (nonAirUses.get(id) || 0) + 1);
                if (isGlass(id)) windows++;
            }
        }
    }
    const distinctBlocks = nonAirUses.size;
    if (distinctBlocks >= 10) failures.push(`palette_too_noisy:${distinctBlocks}`);
    if (windows === 0) failures.push('no_window');

    // Symmetry: for each y layer, how often the XZ slice is a palindrome on X.
    let symmetricLayers = 0;
    let totalLayers = 0;
    for (let y = 0; y < h; y++) {
        totalLayers++;
        let mirrored = true;
        for (let z = 0; z < l && mirrored; z++) {
            for (let x = 0; x < Math.floor(w / 2); x++) {
                if (at(x, y, z) !== at(w - 1 - x, y, z)) { mirrored = false; break; }
            }
        }
        if (mirrored) symmetricLayers++;
    }
    const symmetryRatio = totalLayers > 0 ? symmetricLayers / totalLayers : 0;
    if (symmetryRatio < 0.4) failures.push(`low_symmetry:${symmetryRatio.toFixed(2)}`);

    // Total checks across all three layers (5 + 5 + 3 style = 13).
    const totalChecks = 13;
    const passCount = totalChecks - failures.length;
    const score = passCount / totalChecks;
    return {
        score,
        pass: failures.length === 0,
        failures,
        stats: {
            interiorCells, leakCells, roofAir, floorAir, doors,
            headroom: h,
            fixtures,
            distinctBlocks,
            windows,
            symmetryRatio: Number(symmetryRatio.toFixed(2)),
        },
    };
}

/**
 * Build the AABB query params for /read_blocks from a dispatched build.
 */
export function buildBoxReadParams(origin, size) {
    return { x: origin.x, y: origin.y, z: origin.z, w: size.x, h: size.y, l: size.z };
}

// ─── Site survey: pick a sensible origin Y ─────────────────────────────────

const DANGER_BLOCKS = new Set([
    'minecraft:lava', 'minecraft:flowing_lava', 'minecraft:fire', 'minecraft:soul_fire',
    'minecraft:magma_block', 'minecraft:campfire', 'minecraft:soul_campfire',
    'minecraft:powder_snow', 'minecraft:sweet_berry_bush', 'minecraft:cactus',
    'minecraft:wither_rose',
]);

const LIQUID_BLOCKS = new Set([
    'minecraft:water', 'minecraft:flowing_water',
    'minecraft:lava', 'minecraft:flowing_lava',
]);

// Blocks that are considered "natural ground" (replaceable-ish without
// destroying player work). Everything NOT in this set is treated as
// player-placed and will trigger a "would overlap existing build" reject.
const NATURAL_GROUND = new Set([
    'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air',
    'minecraft:grass_block', 'minecraft:dirt', 'minecraft:coarse_dirt', 'minecraft:rooted_dirt',
    'minecraft:podzol', 'minecraft:mycelium', 'minecraft:farmland', 'minecraft:dirt_path',
    'minecraft:stone', 'minecraft:cobblestone', 'minecraft:granite', 'minecraft:diorite',
    'minecraft:andesite', 'minecraft:tuff', 'minecraft:deepslate',
    'minecraft:cobbled_deepslate', 'minecraft:gravel',
    'minecraft:sand', 'minecraft:red_sand', 'minecraft:sandstone',
    'minecraft:snow', 'minecraft:snow_block',
    'minecraft:netherrack', 'minecraft:soul_sand', 'minecraft:soul_soil', 'minecraft:basalt',
    'minecraft:blackstone', 'minecraft:end_stone',
    // Crops/foliage that are cheap to clear
    'minecraft:short_grass', 'minecraft:tall_grass', 'minecraft:fern', 'minecraft:large_fern',
    'minecraft:dandelion', 'minecraft:poppy', 'minecraft:oxeye_daisy', 'minecraft:cornflower',
    'minecraft:azure_bluet', 'minecraft:blue_orchid', 'minecraft:allium', 'minecraft:lily_of_the_valley',
    'minecraft:orange_tulip', 'minecraft:pink_tulip', 'minecraft:red_tulip', 'minecraft:white_tulip',
    'minecraft:dead_bush', 'minecraft:sugar_cane', 'minecraft:lily_pad',
    // Tree leaves — Baritone can clear these trivially
    'minecraft:oak_leaves', 'minecraft:spruce_leaves', 'minecraft:birch_leaves',
    'minecraft:jungle_leaves', 'minecraft:acacia_leaves', 'minecraft:dark_oak_leaves',
    'minecraft:cherry_leaves', 'minecraft:mangrove_leaves', 'minecraft:azalea_leaves',
    'minecraft:flowering_azalea_leaves',
]);

function isAirLike(id) {
    return id === 'minecraft:air' || id === 'minecraft:cave_air' || id === 'minecraft:void_air';
}

/**
 * Read a vertical column at (x, z) from a pre-fetched scan buffer, and return
 * the Y of the highest non-air, non-liquid, non-foliage block (the ground).
 * Returns null if the scan doesn't cover that column.
 */
function topSurfaceY(scan, x, z) {
    if (!scan) return null;
    const [ox, oy, oz] = scan.origin;
    const [w, h, l] = scan.size;
    const dx = x - ox, dz = z - oz;
    if (dx < 0 || dx >= w || dz < 0 || dz >= l) return null;
    for (let dy = h - 1; dy >= 0; dy--) {
        const id = scan.blocks[(dy * l + dz) * w + dx];
        if (!id) continue;
        if (isAirLike(id)) continue;
        if (LIQUID_BLOCKS.has(id)) continue;
        // Skip foliage that would count as "above ground".
        if (id === 'minecraft:short_grass' || id === 'minecraft:tall_grass'
                || id === 'minecraft:fern' || id === 'minecraft:large_fern'
                || id === 'minecraft:snow') continue;
        return oy + dy;
    }
    return null;
}

/**
 * Survey the footprint of a planned house and decide whether the candidate
 * origin is a good place to build. Asks the bridge to scan a pillar-shaped
 * volume spanning the house footprint, then:
 *
 *   1. Finds the highest natural surface at each (x, z) cell.
 *   2. Computes the median surface Y — that's the proposed floor Y.
 *   3. Rejects the site if:
 *      - height variance is too large (> 3 blocks): too uneven to build on
 *      - any liquid or danger block is inside the footprint or 2 blocks above
 *      - >20% of the footprint is already occupied by non-natural blocks
 *        (i.e. player-placed structures we'd be demolishing)
 *
 * Returns:
 *   { ok: true,  origin: {x,y,z}, reason: '' }
 *   { ok: false, origin: null, reason: '<human text>' }
 *
 * The caller is expected to re-try with a different candidate or surface the
 * reason to the LLM so it can ask the user "how about over there?".
 */
export async function surveyBuildSite(bridge, schematic, candidateOrigin, state) {
    if (!bridge || typeof bridge.readBlocks !== 'function') {
        return { ok: true, origin: candidateOrigin, reason: 'no-bridge-skipping-survey' };
    }
    const { size } = schematic;
    // Ground scan: 8 blocks down through the top of the house, across the full
    // footprint. Clamped to 24 by the mod side; house sizes are <= 11 so this
    // fits with headroom.
    const w = size.x, l = size.z;
    const scanH = Math.max(size.y + 4, 12);
    const scanYBase = candidateOrigin.y - 8;
    const scan = await bridge.readBlocks({
        x: candidateOrigin.x,
        y: scanYBase,
        z: candidateOrigin.z,
        w, h: Math.min(scanH, 24), l,
    });
    if (!scan) {
        return { ok: false, origin: null, reason: "can't read the ground here; move a bit and try again" };
    }

    // 1. Per-cell top-surface Y.
    const surfaceYs = [];
    let liquidHits = 0;
    let dangerHits = 0;
    let placedHits = 0;
    let cells = 0;
    for (let dz = 0; dz < l; dz++) {
        for (let dx = 0; dx < w; dx++) {
            cells++;
            const x = candidateOrigin.x + dx;
            const z = candidateOrigin.z + dz;
            const top = topSurfaceY(scan, x, z);
            if (top != null) surfaceYs.push(top);
            // Scan the whole vertical slice for hazards / existing build.
            for (let dy = 0; dy < scan.size[1]; dy++) {
                const id = scan.blocks[(dy * l + dz) * w + dx];
                if (!id) continue;
                if (LIQUID_BLOCKS.has(id)) liquidHits++;
                if (DANGER_BLOCKS.has(id)) dangerHits++;
                // "Placed": non-air, non-natural. Doors, planks, stone bricks, etc.
                if (!isAirLike(id) && !NATURAL_GROUND.has(id) && !LIQUID_BLOCKS.has(id) && !DANGER_BLOCKS.has(id)) {
                    placedHits++;
                }
            }
        }
    }

    if (dangerHits > 0) {
        return { ok: false, origin: null, reason: `hazard blocks in footprint (lava/fire/magma ×${dangerHits})` };
    }
    if (liquidHits > Math.max(3, cells * 0.1)) {
        return { ok: false, origin: null, reason: `too much water/lava in footprint (${liquidHits} cells)` };
    }
    const placedRatio = placedHits / Math.max(1, cells * scan.size[1]);
    if (placedRatio > 0.20) {
        return { ok: false, origin: null, reason: `looks like something's already here (${(placedRatio * 100).toFixed(0)}% of the volume is built up)` };
    }
    if (surfaceYs.length < cells * 0.75) {
        return { ok: false, origin: null, reason: `not enough solid ground under this spot` };
    }

    // 2. Floor Y = median surface + 1 (house floor sits on top of surface).
    surfaceYs.sort((a, b) => a - b);
    const median = surfaceYs[Math.floor(surfaceYs.length / 2)];
    const minY = surfaceYs[0];
    const maxY = surfaceYs[surfaceYs.length - 1];
    if (maxY - minY > 3) {
        return { ok: false, origin: null, reason: `ground is too uneven here (${maxY - minY}-block variance)` };
    }

    const floorY = median + 1;
    return {
        ok: true,
        origin: { x: candidateOrigin.x, y: floorY, z: candidateOrigin.z },
        reason: `median surface y=${median}, variance=${maxY - minY}`,
        stats: { median, minY, maxY, cells, liquidHits, dangerHits, placedRatio: Number(placedRatio.toFixed(2)) },
    };
}

/**
 * Try a rich grid of candidate spots until one passes the survey. Tracks the
 * best failure reason so the caller can differentiate "can't build anywhere"
 * from "all nearby spots have obstacles."
 *
 * Search pattern: spiral outward in rings at radii 0, 2, 5, 8, 12, 16, 20.
 * At each ring, eight compass directions. Plus a rotated-90° variant of each
 * footprint (swap size.x and size.z) so a 7×5 cabin can fit narrow ledges too.
 *
 * Returns:
 *   { ok: true,  origin, schematic, reason, stats }
 *   { ok: false, origin: null, reason, categoryByReason: Map<reason,count> }
 */
export async function findBuildableSite(bridge, schematic, candidateOrigin, state, tries = 32) {
    const rings = [0, 2, 5, 8, 12, 16, 20];
    // Eight compass directions per ring.
    const dirs = [
        { x:  1, z:  0 }, { x: -1, z:  0 }, { x:  0, z:  1 }, { x:  0, z: -1 },
        { x:  1, z:  1 }, { x:  1, z: -1 }, { x: -1, z:  1 }, { x: -1, z: -1 },
    ];

    const offsets = [{ x: 0, z: 0 }];
    for (const r of rings) {
        if (r === 0) continue;
        for (const d of dirs) {
            offsets.push({ x: d.x * r, z: d.z * r });
        }
    }

    // Track category counts so the caller knows which kind of failure dominated.
    const categoryByReason = new Map();
    let firstReason = 'no candidate tried';
    let attempts = 0;

    for (const o of offsets) {
        if (attempts >= tries) break;

        // Try both orientations: original, and rotated 90° (swap X/Z).
        const orientations = [
            { schematic, tag: 'orig' },
        ];
        if (schematic.size.x !== schematic.size.z) {
            orientations.push({ schematic: rotateSchematicY(schematic), tag: 'rot90' });
        }

        for (const orient of orientations) {
            if (attempts >= tries) break;
            attempts++;
            const try_ = {
                x: candidateOrigin.x + o.x,
                y: candidateOrigin.y,
                z: candidateOrigin.z + o.z,
            };
            const result = await surveyBuildSite(bridge, orient.schematic, try_, state);
            if (result.ok) {
                return {
                    ok: true,
                    origin: result.origin,
                    schematic: orient.schematic,
                    reason: `${result.reason} (${orient.tag} @ offset ${o.x},${o.z})`,
                    stats: result.stats,
                };
            }
            if (firstReason === 'no candidate tried') firstReason = result.reason;
            const category = categorizeReason(result.reason);
            categoryByReason.set(category, (categoryByReason.get(category) || 0) + 1);
        }
    }

    // Pick the most common category for a concise top-level reason.
    let bestCategory = 'mixed';
    let bestCount = 0;
    for (const [cat, n] of categoryByReason.entries()) {
        if (n > bestCount) { bestCount = n; bestCategory = cat; }
    }

    return {
        ok: false,
        origin: null,
        reason: `tried ${attempts} spots near (${candidateOrigin.x}, ${candidateOrigin.z}); most common issue: ${bestCategory}. First failure: ${firstReason}`,
        categoryByReason,
    };
}

/**
 * Map a specific survey reason string to a coarse category so the caller can
 * decide whether to terraform, move the player, or give up.
 */
function categorizeReason(reason) {
    const r = String(reason || '').toLowerCase();
    if (r.includes('hazard') || r.includes('lava') || r.includes('fire') || r.includes('magma')) return 'hazard';
    if (r.includes('water') || r.includes('liquid')) return 'water';
    if (r.includes('already here') || r.includes('built up')) return 'built_up';
    if (r.includes('uneven')) return 'uneven';
    if (r.includes('solid ground') || r.includes('not enough ground')) return 'floating';
    if (r.includes("can't read")) return 'unreadable';
    return 'other';
}

/**
 * Rotate a schematic 90° around the Y axis (swap X and Z). Blocks are
 * re-indexed but block IDs are not updated — Minecraft's direction-bearing
 * blocks (stairs, doors, beds) will keep their default facing. For our
 * current templates that's fine because defaults pick a sensible south-facing
 * orientation regardless.
 */
function rotateSchematicY(schematic) {
    const { size, palette, blocksU16 } = schematic;
    const newSizeX = size.z;
    const newSizeZ = size.x;
    const newBuf = Buffer.alloc(newSizeX * size.y * newSizeZ * 2);
    // For each (x', z') in the new schematic, sample from (z', size.x - 1 - x') in the old.
    for (let y = 0; y < size.y; y++) {
        for (let nz = 0; nz < newSizeZ; nz++) {
            for (let nx = 0; nx < newSizeX; nx++) {
                const ox = nz;                 // old X = new Z
                const oz = newSizeX - 1 - nx;  // old Z = (newSizeX - 1 - new X)
                const oldIdx = ((y * size.z + oz) * size.x + ox) * 2;
                const newIdx = ((y * newSizeZ + nz) * newSizeX + nx) * 2;
                newBuf.writeUInt16LE(blocksU16.readUInt16LE(oldIdx), newIdx);
            }
        }
    }
    return {
        ...schematic,
        name: schematic.name + '_rot90',
        size: { x: newSizeX, y: size.y, z: newSizeZ },
        palette,
        blocksU16: newBuf,
    };
}

// ─── Saved templates (Option B: "build one like that") ────────────────────

import { mkdirSync as _mkdir, readFileSync as _readFile, writeFileSync as _writeFile, existsSync as _exists, readdirSync as _readdir } from 'fs';
import * as _path from 'path';

const SAVED_ROOT = './bots/_shared/house_templates';

function ensureSavedDir() {
    _mkdir(SAVED_ROOT, { recursive: true });
}

/**
 * Convert a /read_blocks response into an in-memory schematic identical in
 * shape to what templates produce. The palette is deduplicated.
 */
export function readbackToSchematic(readback, name) {
    if (!readback || !Array.isArray(readback.blocks)) return null;
    const [w, h, l] = readback.size;
    const palette = ['minecraft:air']; // slot 0 reserved for air
    const paletteIndex = new Map();
    paletteIndex.set('minecraft:air', 0);
    const buf = Buffer.alloc(w * h * l * 2);
    for (let i = 0; i < readback.blocks.length; i++) {
        const id = readback.blocks[i] || 'minecraft:air';
        let idx = paletteIndex.get(id);
        if (idx === undefined) {
            idx = palette.length;
            palette.push(id);
            paletteIndex.set(id, idx);
        }
        buf.writeUInt16LE(idx, i * 2);
    }
    return {
        name: name || `scanned_${Date.now()}`,
        size: { x: w, y: h, z: l },
        palette,
        blocksU16: buf,
    };
}

export function saveTemplate(name, schematic) {
    ensureSavedDir();
    const safe = String(name || '').replace(/[^a-z0-9_]/gi, '_').toLowerCase();
    if (!safe) return null;
    const file = _path.join(SAVED_ROOT, `${safe}.json`);
    const payload = {
        name: schematic.name,
        size: schematic.size,
        palette: schematic.palette,
        blocksB64: schematic.blocksU16.toString('base64'),
        savedAt: Date.now(),
    };
    _writeFile(file, JSON.stringify(payload));
    return safe;
}

export function loadSavedTemplate(name) {
    ensureSavedDir();
    const safe = String(name || '').replace(/[^a-z0-9_]/gi, '_').toLowerCase();
    if (!safe) return null;
    const file = _path.join(SAVED_ROOT, `${safe}.json`);
    if (!_exists(file)) return null;
    try {
        const data = JSON.parse(_readFile(file, 'utf8'));
        const buf = Buffer.from(data.blocksB64, 'base64');
        return {
            name: data.name,
            size: data.size,
            palette: data.palette,
            blocksU16: buf,
        };
    } catch {
        return null;
    }
}

export function listSavedTemplates() {
    ensureSavedDir();
    try {
        return _readdir(SAVED_ROOT).filter(f => f.endsWith('.json')).map(f => f.slice(0, -5));
    } catch {
        return [];
    }
}

// ─── Ratings / preference memory (Option D) ────────────────────────────────

const RATING_FILE = './bots/_shared/house_ratings.json';

function loadRatings() {
    try {
        if (!_exists(RATING_FILE)) return { templates: {}, builds: [] };
        return JSON.parse(_readFile(RATING_FILE, 'utf8'));
    } catch {
        return { templates: {}, builds: [] };
    }
}

function saveRatings(data) {
    ensureSavedDir();
    try {
        _writeFile(RATING_FILE, JSON.stringify(data, null, 2));
    } catch {}
}

/**
 * Record a user rating for a completed build. Score should be -1 (hated),
 * 0 (neutral), or +1 (loved). Stores per-template rolling averages so future
 * template picks can be biased toward well-liked ones.
 */
export function recordBuildRating({ templateName, score, comment }) {
    const data = loadRatings();
    const n = Math.max(-1, Math.min(1, Number(score) || 0));
    const entry = { templateName: String(templateName || 'unknown'), score: n, comment: String(comment || ''), at: Date.now() };
    data.builds = data.builds || [];
    data.builds.push(entry);
    if (data.builds.length > 200) data.builds = data.builds.slice(-200);
    data.templates = data.templates || {};
    const key = entry.templateName.split('_')[0]; // bucket by family (cabin/tower/pit/scanned_*)
    const cur = data.templates[key] || { total: 0, count: 0 };
    cur.total += n;
    cur.count += 1;
    data.templates[key] = cur;
    saveRatings(data);
    return entry;
}

export function getTemplatePreference(family) {
    const data = loadRatings();
    const cur = data.templates?.[family];
    if (!cur || cur.count === 0) return 0;
    return cur.total / cur.count;
}

export function summarizeRatings() {
    const data = loadRatings();
    const out = [];
    for (const [family, cur] of Object.entries(data.templates || {})) {
        if (!cur.count) continue;
        out.push(`${family}: ${(cur.total / cur.count).toFixed(2)} (n=${cur.count})`);
    }
    return out.join(', ') || 'no ratings yet';
}
