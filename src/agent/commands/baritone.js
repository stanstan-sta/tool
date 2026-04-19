/**
 * Baritone bridge commands.
 *
 * Baritone (https://github.com/cabaletta/baritone) is a powerful Java-based
 * pathfinding AI mod for Minecraft clients (Forge / Fabric). It is NOT built into
 * the Mineflayer bot itself — mineflayer-pathfinder already handles all normal
 * bot navigation through the existing `!goToCoordinates`, `!searchForBlock`, etc.
 * commands.
 *
 * These commands are useful when:
 *   - The bot is running through a Baritone-enabled Forge/Fabric client that
 *     responds to the `#` chat prefix.
 *   - A server-side Baritone plugin (e.g. BaritoneHook) is installed.
 *
 * To enable these commands, set `use_baritone: true` in settings.js.
 * They are blocked by default so they do not appear in the model's tool list
 * for normal Mineflayer setups.
 */

export const baritoneList = [
    {
        name: '!baritoneGoto',
        description: 'Use Baritone to navigate to given x, y, z coordinates (requires a Baritone-enabled Minecraft client or server plugin).',
        params: {
            'x': { type: 'float', description: 'The x coordinate.', domain: [-Infinity, Infinity] },
            'y': { type: 'float', description: 'The y coordinate.', domain: [-64, 320] },
            'z': { type: 'float', description: 'The z coordinate.', domain: [-Infinity, Infinity] },
        },
        perform: async function (agent, x, y, z) {
            agent.bot.chat(`#goto ${x} ${y} ${z}`);
            return `Issued Baritone goto (${x}, ${y}, ${z}).`;
        }
    },
    {
        name: '!baritoneCancel',
        description: 'Cancel the current Baritone task (requires Baritone).',
        perform: async function (agent) {
            agent.bot.chat('#cancel');
            return 'Baritone task cancelled.';
        }
    },
    {
        name: '!baritioneMine',
        description: 'Use Baritone to mine a given block type (requires Baritone). Baritone will automatically seek out and mine the specified block.',
        params: {
            'block_type': { type: 'BlockName', description: 'The block type to mine (e.g. diamond_ore).' },
            'count': { type: 'int', description: 'The number of blocks to mine.', domain: [1, Number.MAX_SAFE_INTEGER] },
        },
        perform: async function (agent, block_type, count) {
            agent.bot.chat(`#mine ${count} ${block_type}`);
            return `Issued Baritone mine ${count} ${block_type}.`;
        }
    },
    {
        name: '!baritoneFollow',
        description: 'Use Baritone to follow a player (requires Baritone).',
        params: {
            'player_name': { type: 'string', description: 'The name of the player to follow.' },
        },
        perform: async function (agent, player_name) {
            agent.bot.chat(`#follow player ${player_name}`);
            return `Issued Baritone follow player ${player_name}.`;
        }
    },
    {
        name: '!baritoneExplore',
        description: 'Use Baritone to systematically explore the world from the current position (requires Baritone).',
        perform: async function (agent) {
            agent.bot.chat('#explore');
            return 'Issued Baritone explore.';
        }
    },
];
