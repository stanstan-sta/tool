import { serializeSurfaceMap } from '../../utils/topography.js';

const DEFAULT_RADIUS = 8;
const DEFAULT_HEIGHT_PADDING = 32;

function getBlockAtAbsolute(bot, x, y, z) {
    const center = bot.entity?.position;
    if (!center) return null;
    const offsetX = x - Math.floor(center.x);
    const offsetY = y - Math.floor(center.y);
    const offsetZ = z - Math.floor(center.z);
    return bot.blockAt(center.offset(offsetX, offsetY, offsetZ));
}

export function getHighestSurfaceBlock(bot, x, z, startY = 256, minY = 0) {
    if (!bot || !bot.entity) return { x, z, y: null, block: 'unknown' };

    for (let y = startY; y >= minY; y--) {
        const block = getBlockAtAbsolute(bot, x, y, z);
        if (block && block.name && block.name !== 'air' && block.name !== 'cave_air') {
            return { x, z, y, block: block.name };
        }
    }

    return { x, z, y: minY, block: 'air' };
}

export function buildSurfaceMap(bot, radius = DEFAULT_RADIUS, options = {}) {
    if (!bot || !bot.entity || !bot.entity.position) return null;

    const centerX = Math.floor(bot.entity.position.x);
    const centerZ = Math.floor(bot.entity.position.z);
    const centerY = Math.floor(bot.entity.position.y);
    const heightPad = Number.isFinite(options.heightPad) ? options.heightPad : DEFAULT_HEIGHT_PADDING;
    const startY = Math.min(255, centerY + heightPad);
    const minY = Number.isFinite(options.minY) ? options.minY : 0;

    const cells = [];
    for (let dz = -radius; dz <= radius; dz++) {
        for (let dx = -radius; dx <= radius; dx++) {
            const x = centerX + dx;
            const z = centerZ + dz;
            const top = getHighestSurfaceBlock(bot, x, z, startY, minY);
            cells.push(top);
        }
    }

    return {
        center: { x: centerX, y: centerY, z: centerZ },
        radius,
        cells
    };
}

export function surfaceMapToText(surfaceMap, options = {}) {
    return serializeSurfaceMap(surfaceMap, options);
}
