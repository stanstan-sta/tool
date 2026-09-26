// Accessors for server-companion data the Fabric mod folds into /state
// (server_companion, server_players, server_facts, server_events). All fields are
// optional: they are null/empty unless a companion mod or Paper plugin is present.

const norm = n => String(n || '').trim().toLowerCase();

export function hasServerData(state) {
    if (!state || typeof state !== 'object') return false;
    return !!state.server_companion
        || (Array.isArray(state.server_players) && state.server_players.length > 0)
        || !!state.server_facts;
}

export function getServerPlayers(state) {
    return Array.isArray(state?.server_players) ? state.server_players.filter(p => p && p.name) : [];
}

export function findServerPlayer(state, name) {
    const want = norm(name);
    if (!want) return null;
    return getServerPlayers(state).find(p => norm(p.name) === want) || null;
}

export function getServerEvents(state) {
    return Array.isArray(state?.server_events) ? state.server_events.filter(Boolean) : [];
}

export function getServerFacts(state) {
    const f = state?.server_facts;
    return f && typeof f === 'object' ? f : null;
}
