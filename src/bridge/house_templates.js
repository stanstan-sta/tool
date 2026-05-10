/**
 * Parameterized house templates.
 *
 * Each generator returns an in-memory schematic:
 *   { name, size:{x,y,z}, palette:[id,...], blocksU16 }
 * where blocksU16 is a Node Buffer of little-endian uint16 palette indexes,
 * packed in the Sponge/Baritone order: idx = (y*length + z)*width + x
 *
 * Generators:
 *   cabin({ size, material, window, biome })   - ground-level square cabin with a door and roof
 *   tower({ floors, material, biome })         - narrow multi-story with slit windows
 *   pit({ size, material, biome })             - sunken shelter, useful first-night survival
 *
 * Biome presets (name → palette hint) ship with sensible defaults for the
 * common overworld biomes.  The caller can override any material explicitly.
 */

const BIOME_PALETTES = {
    plains:   { wall: 'oak_planks',     log: 'oak_log',      roof: 'oak_planks',     floor: 'oak_planks' },
    forest:   { wall: 'spruce_planks',  log: 'spruce_log',   roof: 'spruce_planks',  floor: 'spruce_planks' },
    taiga:    { wall: 'spruce_planks',  log: 'spruce_log',   roof: 'spruce_planks',  floor: 'spruce_planks' },
    desert:   { wall: 'sandstone',      log: 'cut_sandstone',roof: 'smooth_sandstone', floor: 'sandstone' },
    savanna:  { wall: 'acacia_planks',  log: 'acacia_log',   roof: 'acacia_planks',  floor: 'acacia_planks' },
    jungle:   { wall: 'jungle_planks',  log: 'jungle_log',   roof: 'jungle_planks',  floor: 'jungle_planks' },
    mountain: { wall: 'cobblestone',    log: 'oak_log',      roof: 'stone_bricks',   floor: 'stone' },
    snowy:    { wall: 'spruce_planks',  log: 'spruce_log',   roof: 'spruce_planks',  floor: 'spruce_planks' },
    swamp:    { wall: 'mangrove_planks',log: 'mangrove_log', roof: 'mangrove_planks',floor: 'mangrove_planks' },
    cherry:   { wall: 'cherry_planks',  log: 'cherry_log',   roof: 'cherry_planks',  floor: 'cherry_planks' },
    nether:   { wall: 'nether_bricks',  log: 'blackstone',   roof: 'nether_bricks',  floor: 'nether_bricks' },
};

function qualify(id) {
    if (!id) return 'minecraft:air';
    return id.includes(':') ? id : `minecraft:${id}`;
}

function resolvePalette(biome, override = {}) {
    const base = BIOME_PALETTES[biome] || BIOME_PALETTES.plains;
    return {
        wall:  qualify(override.wall  || base.wall),
        log:   qualify(override.log   || base.log),
        roof:  qualify(override.roof  || base.roof),
        floor: qualify(override.floor || base.floor),
        glass: qualify(override.glass || 'glass'),
        door:  qualify(override.door  || 'oak_door'),
        torch: qualify(override.torch || 'torch'),
        bed:   qualify(override.bed   || 'red_bed'),
        craft: qualify('crafting_table'),
        furnace: qualify('furnace'),
        chest: qualify('chest'),
        air:   qualify('air'),
    };
}

/**
 * Build an empty schematic of the given size and return an index helper.
 * Blocks default to palette index 0 (air must be entry 0).
 */
function allocate(width, height, length, paletteIds) {
    const total = width * height * length;
    const buf = Buffer.alloc(total * 2); // uint16 LE
    const idxOf = (name) => {
        const id = qualify(name);
        const found = paletteIds.indexOf(id);
        if (found >= 0) return found;
        paletteIds.push(id);
        return paletteIds.length - 1;
    };
    const offset = (x, y, z) => ((y * length + z) * width + x) * 2;
    const set = (x, y, z, name) => {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= length) return;
        buf.writeUInt16LE(idxOf(name), offset(x, y, z));
    };
    const get = (x, y, z) => {
        if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= length) return -1;
        return buf.readUInt16LE(offset(x, y, z));
    };
    return { buf, idxOf, set, get, width, height, length };
}

/**
 * Cabin: rectangular box with log corners, plank walls, plank/roof pitched
 * cap, one door, 4 windows, a floor, torches in all four interior corners,
 * bed + crafting table + furnace + chest indoors.
 *
 * Dimensions are clamped. Minimum interior footprint is 5x5, maximum 11x11.
 */
export function cabinTemplate(opts = {}) {
    const size = opts.size || 'small';
    const biome = opts.biome || 'plains';
    const palette = resolvePalette(biome, opts.material || {});
    const windowEnabled = opts.window !== false;

    const w = size === 'small' ? 7 : (size === 'medium' ? 9 : 11); // X
    const l = w;                                                    // Z
    const h = size === 'small' ? 5 : 6;                             // Y incl. floor + roof

    const paletteIds = [palette.air]; // air must be index 0
    const s = allocate(w, h, l, paletteIds);

    // Floor (y=0) - solid slab of floor material.
    for (let x = 0; x < w; x++) {
        for (let z = 0; z < l; z++) {
            s.set(x, 0, z, palette.floor);
        }
    }

    // Walls (y=1 .. h-2). Corner posts use logs; everything else is planks.
    const topWallY = h - 2;
    for (let y = 1; y <= topWallY; y++) {
        for (let x = 0; x < w; x++) {
            for (let z = 0; z < l; z++) {
                const onEdge = (x === 0 || x === w - 1 || z === 0 || z === l - 1);
                if (!onEdge) continue;
                const corner = (x === 0 || x === w - 1) && (z === 0 || z === l - 1);
                s.set(x, y, z, corner ? palette.log : palette.wall);
            }
        }
    }

    // Windows: 1-block gaps at y=topWallY-1, centered on each side.
    if (windowEnabled) {
        const midX = Math.floor(w / 2);
        const midZ = Math.floor(l / 2);
        const wy = Math.max(1, topWallY - 1);
        const winBlock = palette.glass;
        s.set(midX, wy, 0, winBlock);
        s.set(midX, wy, l - 1, winBlock);
        s.set(0, wy, midZ, winBlock);
        s.set(w - 1, wy, midZ, winBlock);
    }

    // Door on the south face (z=0). Two air columns so the bot can walk through,
    // and a door block for visual integrity. Doors are two blocks tall in Minecraft
    // but place from ONE item — and Baritone will re-mine any cell that doesn't
    // match the schematic, so both the lower AND upper cell must be marked as
    // door in the template. Otherwise Baritone places the door, sees the upper
    // half in the world, sees air in the schematic, and breaks the door.
    const doorX = Math.floor(w / 2);
    s.set(doorX, 1, 0, palette.door);
    s.set(doorX, 2, 0, palette.door);

    // Roof cap (y=h-1): pitched via simple stepped shape = full cover of roof mat.
    for (let x = 0; x < w; x++) {
        for (let z = 0; z < l; z++) {
            s.set(x, h - 1, z, palette.roof);
        }
    }

    // Ceiling layer (y=topWallY+1 beneath roof) stays air so interior is hollow.
    // Nothing to do — allocate() defaults to air.

    // Interior fixtures. Only place in small+ so there is space.
    const cx = Math.floor(w / 2);
    const cz = Math.floor(l / 2);
    // Bed (two blocks in +X direction from interior corner).
    s.set(2, 1, 2, palette.bed);
    s.set(3, 1, 2, palette.bed);
    // Crafting table
    s.set(w - 3, 1, 2, palette.craft);
    // Furnace
    s.set(w - 3, 1, 3, palette.furnace);
    // Chest
    s.set(w - 3, 1, l - 3, palette.chest);
    // Corner torches (1 block inside the wall corners) on top of the floor.
    s.set(1, 1, 1, palette.torch);
    s.set(w - 2, 1, 1, palette.torch);
    s.set(1, 1, l - 2, palette.torch);
    s.set(w - 2, 1, l - 2, palette.torch);

    return {
        name: `cabin_${biome}_${size}`,
        size: { x: w, y: h, z: l },
        palette: paletteIds,
        blocksU16: s.buf,
        doorX,
        doorFacing: 'south',
        interior: { yFloor: 1, yCeil: topWallY, minX: 1, maxX: w - 2, minZ: 1, maxZ: l - 2 },
    };
}

/**
 * Tower: 3x3 footprint, multi-story with slit windows on each floor and a
 * capped roof. Always ships with crafting table, furnace, chest, bed, torches.
 */
export function towerTemplate(opts = {}) {
    const biome = opts.biome || 'plains';
    const palette = resolvePalette(biome, opts.material || {});
    const floors = Math.max(2, Math.min(6, opts.floors || 3));

    const w = 5; // interior 3x3 + wall
    const l = 5;
    // Each floor = 3 blocks tall (space, space, ceiling).
    const h = 2 + floors * 3; // floor (1) + N*(3) + roof (1)

    const paletteIds = [palette.air];
    const s = allocate(w, h, l, paletteIds);

    // Base floor
    for (let x = 0; x < w; x++) {
        for (let z = 0; z < l; z++) s.set(x, 0, z, palette.floor);
    }

    // Tower walls
    for (let f = 0; f < floors; f++) {
        const yBase = 1 + f * 3;
        for (let dy = 0; dy < 3; dy++) {
            const y = yBase + dy;
            for (let x = 0; x < w; x++) {
                for (let z = 0; z < l; z++) {
                    const edge = (x === 0 || x === w - 1 || z === 0 || z === l - 1);
                    if (!edge) continue;
                    const corner = (x === 0 || x === w - 1) && (z === 0 || z === l - 1);
                    s.set(x, y, z, corner ? palette.log : palette.wall);
                }
            }
            // Floor divider at each new storey's base (except ground floor which is palette.floor)
            if (f > 0 && dy === 0) {
                for (let x = 1; x < w - 1; x++) {
                    for (let z = 1; z < l - 1; z++) s.set(x, y, z, palette.floor);
                }
            }
        }
        // Slit windows centered on each wall, middle row of the floor
        const wy = yBase + 1;
        s.set(2, wy, 0, palette.glass);
        s.set(2, wy, l - 1, palette.glass);
        s.set(0, wy, 2, palette.glass);
        s.set(w - 1, wy, 2, palette.glass);
    }

    // Roof cap
    for (let x = 0; x < w; x++) {
        for (let z = 0; z < l; z++) s.set(x, h - 1, z, palette.roof);
    }

    // Ground floor door (south). Both halves must be marked as door — if we
    // leave the upper half as air, Baritone breaks the door the tick after
    // placing it (schematic said air, door's upper half is not-air).
    s.set(2, 1, 0, palette.door);
    s.set(2, 2, 0, palette.door);

    // Ground-floor fixtures
    s.set(1, 1, 1, palette.torch);
    s.set(w - 2, 1, 1, palette.torch);
    s.set(1, 1, l - 2, palette.bed);
    s.set(2, 1, l - 2, palette.bed);
    s.set(w - 2, 1, l - 2, palette.craft);

    // Second floor (if present) — furnace + chest
    if (floors >= 2) {
        s.set(1, 4, 1, palette.furnace);
        s.set(w - 2, 4, 1, palette.chest);
        s.set(2, 4, 2, palette.torch);
    }

    return {
        name: `tower_${biome}_${floors}f`,
        size: { x: w, y: h, z: l },
        palette: paletteIds,
        blocksU16: s.buf,
        doorX: 2,
        doorFacing: 'south',
        interior: { yFloor: 1, yCeil: h - 2, minX: 1, maxX: w - 2, minZ: 1, maxZ: l - 2 },
    };
}

/**
 * Pit shelter: dug-in 5x5x3 interior, roofed and lit. Good first-night spot.
 * Origin refers to the bottom-southwest corner of the *interior*; the bot
 * should stand 1 block north of origin for entry.
 */
export function pitTemplate(opts = {}) {
    const biome = opts.biome || 'plains';
    const palette = resolvePalette(biome, opts.material || {});

    const w = 5, l = 5, h = 4;
    const paletteIds = [palette.air];
    const s = allocate(w, h, l, paletteIds);

    // Solid floor (y=0)
    for (let x = 0; x < w; x++) {
        for (let z = 0; z < l; z++) s.set(x, 0, z, palette.floor);
    }
    // Walls (y=1,2)
    for (let y = 1; y <= 2; y++) {
        for (let x = 0; x < w; x++) {
            for (let z = 0; z < l; z++) {
                const edge = (x === 0 || x === w - 1 || z === 0 || z === l - 1);
                if (edge) s.set(x, y, z, palette.wall);
            }
        }
    }
    // Roof (y=3)
    for (let x = 0; x < w; x++) {
        for (let z = 0; z < l; z++) s.set(x, h - 1, z, palette.roof);
    }
    // Door (south). Both halves must be marked as door — see cabin template comment.
    s.set(2, 1, 0, palette.door);
    s.set(2, 2, 0, palette.door);
    // Corner torches + bed + crafting table
    s.set(1, 1, 1, palette.torch);
    s.set(w - 2, 1, 1, palette.torch);
    s.set(1, 1, l - 2, palette.bed);
    s.set(2, 1, l - 2, palette.bed);
    s.set(w - 2, 1, l - 2, palette.craft);

    return {
        name: `pit_${biome}`,
        size: { x: w, y: h, z: l },
        palette: paletteIds,
        blocksU16: s.buf,
        doorX: 2,
        doorFacing: 'south',
        interior: { yFloor: 1, yCeil: h - 2, minX: 1, maxX: w - 2, minZ: 1, maxZ: l - 2 },
    };
}

export const TEMPLATES = {
    cabin: cabinTemplate,
    tower: towerTemplate,
    pit:   pitTemplate,
};

export const TEMPLATE_NAMES = Object.keys(TEMPLATES);

export function pickTemplate(name) {
    const lower = String(name || '').toLowerCase();
    return TEMPLATES[lower] || null;
}

/**
 * Infer a biome label from a FabricState (best effort — we only know a few).
 * Currently we don't ship biome info in /state, so this defaults to "plains".
 * Downstream code uses this as a *hint*; dialog can override.
 */
export function inferBiome(state) {
    const dim = String(state?.dimension || '').toLowerCase();
    if (dim.includes('nether')) return 'nether';
    if (dim.includes('end')) return 'plains'; // no end palette; fall back
    return 'plains';
}
