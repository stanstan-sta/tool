import { getServer } from '../../mindcraft/mcserver.js';

export async function preparePacketRuntime(settings) {
    const next = { ...settings };
    try {
        const server = await getServer(next.host, next.port, next.minecraft_version);
        next.host = server.host;
        next.port = server.port;
        next.minecraft_version = server.version;
    } catch (error) {
        console.warn(`Error getting server:`, error);
        if (next.minecraft_version === 'auto') {
            next.minecraft_version = null;
        }
        console.warn(`Attempting to connect anyway...`);
    }
    return {
        settings: next,
        runtime: 'packet',
    };
}

