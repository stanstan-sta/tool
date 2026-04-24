export function serializeSurfaceMap(surfaceMap, options = {}) {
    if (!surfaceMap || !Array.isArray(surfaceMap.cells)) {
        return 'No surface map available.';
    }

    const format = String(options.format || 'coordinate_list').toLowerCase();
    const radius = Number.isFinite(surfaceMap.radius) ? surfaceMap.radius : null;
    const center = surfaceMap.center
        ? `center=${surfaceMap.center.x},${surfaceMap.center.z}`
        : null;

    if (format === 'grid') {
        const cellsByPosition = new Map();
        let minX = Infinity;
        let maxX = -Infinity;
        let minZ = Infinity;
        let maxZ = -Infinity;

        for (const cell of surfaceMap.cells) {
            const key = `${cell.x},${cell.z}`;
            cellsByPosition.set(key, cell);
            minX = Math.min(minX, cell.x);
            maxX = Math.max(maxX, cell.x);
            minZ = Math.min(minZ, cell.z);
            maxZ = Math.max(maxZ, cell.z);
        }

        const rows = [];
        for (let z = maxZ; z >= minZ; z--) {
            const rowCells = [];
            for (let x = minX; x <= maxX; x++) {
                const cell = cellsByPosition.get(`${x},${z}`);
                if (!cell) {
                    rowCells.push('air@?');
                } else {
                    rowCells.push(`${cell.block}@${cell.y}`);
                }
            }
            rows.push(`Z=${z}: ${rowCells.join(', ')}`);
        }

        return [`Surface map${center ? ` (${center})` : ''}${radius !== null ? ` radius=${radius}` : ''}:`, ...rows].join('\n');
    }

    const lines = surfaceMap.cells
        .slice()
        .sort((a, b) => a.z - b.z || a.x - b.x)
        .map(cell => `X=${cell.x}, Z=${cell.z} => y=${cell.y}, ${cell.block}`);

    const header = [`Surface map${center ? ` (${center})` : ''}${radius !== null ? ` radius=${radius}` : ''}:`, ...lines];
    return header.join('\n');
}

export function formatSurfaceMap(surfaceMap, options = {}) {
    return serializeSurfaceMap(surfaceMap, options);
}
