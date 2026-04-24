import { formatSurfaceMap } from '../utils/topography.js';

export function buildBridgeTopographySystemMessage(surfaceMap, options = {}) {
    if (!surfaceMap || !Array.isArray(surfaceMap.cells)) {
        return 'Surface map is unavailable.';
    }

    return `Nearby terrain surface map:\n${formatSurfaceMap(surfaceMap, options)}`;
}
