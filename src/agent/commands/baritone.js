/**
 * Advanced pathfinding commands powered by @miner-org/mineflayer-baritone.
 *
 * mineflayer-baritone is a pure-JavaScript Baritone-inspired pathfinder for
 * Mineflayer that adds support for parkour jumps, block breaking/placing,
 * swimming, and ladder climbing — capabilities beyond mineflayer-pathfinder.
 *
 * Inspiration: https://github.com/miner-org/mineflayer-baritone
 * Original Baritone: https://github.com/cabaletta/baritone
 *
 * Requirements:
 *   - Set `use_baritone: true` in settings.js
 *   - The bot's ashfinder is loaded at startup when the setting is enabled
 *
 * These commands are disabled by default to avoid polluting the model's
 * tool list in non-baritone setups.
 */

import { createRequire } from 'module';
import { Vec3 } from 'vec3';

// CJS interop: @miner-org/mineflayer-baritone uses CommonJS
const require = createRequire(import.meta.url);
let _baritoneGoals = null;

function getGoals() {
    if (!_baritoneGoals) {
        _baritoneGoals = require('@miner-org/mineflayer-baritone').goals;
    }
    return _baritoneGoals;
}

/** Check that the bot has the ashfinder plugin loaded. */
function ensureAshfinder(bot) {
    if (!bot.ashfinder) {
        return 'Baritone pathfinder (ashfinder) is not loaded. Ensure use_baritone is true in settings.js.';
    }
    return null;
}

export const baritoneList = [
    {
        name: '!baritoneGoto',
        description: 'Navigate to x, y, z using the advanced Baritone-style pathfinder. Unlike !goToCoordinates, this supports parkour jumps and more complex terrain.',
        params: {
            'x': { type: 'float', description: 'Target X coordinate.', domain: [-Infinity, Infinity] },
            'y': { type: 'float', description: 'Target Y coordinate.', domain: [-64, 320] },
            'z': { type: 'float', description: 'Target Z coordinate.', domain: [-Infinity, Infinity] },
        },
        perform: async function (agent, x, y, z) {
            const bot = agent.bot;
            const err = ensureAshfinder(bot);
            if (err) return err;
            try {
                const goals = getGoals();
                await bot.ashfinder.goto(new goals.GoalNear(new Vec3(x, y, z), 2));
                const dist = bot.entity.position.distanceTo(new Vec3(x, y, z));
                if (dist <= 4) {
                    return `Arrived at (${x}, ${y}, ${z}).`;
                }
                return `Navigation ended ${Math.round(dist)} blocks from (${x}, ${y}, ${z}).`;
            } catch (e) {
                return `Baritone navigation stopped: ${e.message}`;
            }
        }
    },
    {
        name: '!baritoneCancel',
        description: 'Cancel the current Baritone navigation task.',
        perform: async function (agent) {
            const bot = agent.bot;
            const err = ensureAshfinder(bot);
            if (err) return err;
            bot.ashfinder.stop();
            return 'Baritone navigation cancelled.';
        }
    },
    {
        name: '!baritoneFollow',
        description: 'Navigate to a player\'s current position using the Baritone pathfinder (parkour-capable). This is a one-shot navigation to where the player is now, not continuous following.',
        params: {
            'player_name': { type: 'string', description: 'The name of the player to navigate toward.' },
        },
        perform: async function (agent, player_name) {
            const bot = agent.bot;
            const err = ensureAshfinder(bot);
            if (err) return err;
            const playerEntry = bot.players[player_name];
            if (!playerEntry || !playerEntry.entity) {
                return `Player '${player_name}' is not visible nearby.`;
            }
            try {
                const goals = getGoals();
                const pos = playerEntry.entity.position;
                await bot.ashfinder.goto(new goals.GoalNear(pos, 3));
                return `Reached ${player_name}.`;
            } catch (e) {
                return `Baritone follow stopped: ${e.message}`;
            }
        }
    },
    {
        name: '!baritoneExplore',
        description: 'Navigate to a random unexplored location within a given radius using the Baritone pathfinder.',
        params: {
            'radius': { type: 'int', description: 'Exploration radius in blocks.', domain: [10, 500] },
        },
        perform: async function (agent, radius) {
            const bot = agent.bot;
            const err = ensureAshfinder(bot);
            if (err) return err;
            const angle = Math.random() * Math.PI * 2;
            const dist = Math.round(radius * (0.5 + Math.random() * 0.5));
            const tx = Math.round(bot.entity.position.x + Math.cos(angle) * dist);
            const tz = Math.round(bot.entity.position.z + Math.sin(angle) * dist);
            const ty = Math.round(bot.entity.position.y);
            try {
                const goals = getGoals();
                await bot.ashfinder.goto(new goals.GoalXZNear(new Vec3(tx, ty, tz), 5));
                return `Explored to (${tx}, ${ty}, ${tz}).`;
            } catch (e) {
                return `Baritone explore stopped: ${e.message}`;
            }
        }
    },
    {
        name: '!baritoneMine',
        description: 'Navigate to and mine the nearest block of the given type using the Baritone pathfinder. Collects up to `count` blocks total.',
        params: {
            'block_type': { type: 'BlockName', description: 'The block type to mine (e.g. iron_ore, oak_log).' },
            'count': { type: 'int', description: 'Number of blocks to collect.', domain: [1, 64] },
        },
        perform: async function (agent, block_type, count) {
            const bot = agent.bot;
            const err = ensureAshfinder(bot);
            if (err) return err;
            const goals = getGoals();
            let collected = 0;
            while (collected < count && !bot.interrupt_code) {
                const block = bot.findBlock({
                    matching: b => b.name === block_type,
                    maxDistance: 128,
                });
                if (!block) {
                    if (collected === 0) return `No ${block_type} found within 128 blocks.`;
                    break;
                }
                // Navigate to block
                try {
                    await bot.ashfinder.goto(new goals.GoalNear(block.position, 3));
                } catch (navErr) {
                    break; // can't reach, stop
                }
                // Dig the block
                try {
                    await bot.dig(block);
                    collected++;
                } catch {
                    // block may have moved or been mined already; continue loop
                }
            }
            return `Collected ${collected}/${count} ${block_type}.`;
        }
    },
    {
        name: '!espLocate',
        description: 'Scan all loaded chunks for a block type and return the nearest occurrences sorted by distance. Mineflayer receives full chunk data from the server — equivalent to X-ray/ESP for blocks.',
        params: {
            'block_type': { type: 'BlockName', description: 'The block type to search for (e.g. diamond_ore, ancient_debris).' },
            'max_range': { type: 'int', description: 'Maximum search radius in blocks.', domain: [8, 512] },
            'count': { type: 'int', description: 'Max number of results to return.', domain: [1, 20] },
        },
        perform: async function (agent, block_type, max_range = 128, count = 5) {
            const bot = agent.bot;
            const blocks = bot.findBlocks({
                matching: b => b.name === block_type,
                maxDistance: max_range,
                count: count,
            });
            if (!blocks || blocks.length === 0) {
                return `No ${block_type} found within ${max_range} blocks.`;
            }
            const sorted = blocks
                .map(pos => ({ pos, dist: Math.round(bot.entity.position.distanceTo(pos)) }))
                .sort((a, b) => a.dist - b.dist);
            const lines = sorted.map((r, i) =>
                `${i + 1}. (${r.pos.x}, ${r.pos.y}, ${r.pos.z}) — ${r.dist}m away`
            );
            return `Found ${sorted.length} ${block_type}:\n${lines.join('\n')}`;
        }
    },
    {
        name: '!espGoto',
        description: 'ESP-scan loaded chunks for a block type, then navigate to the nearest one using the Baritone pathfinder (supports parkour). Combines ESP detection with advanced pathfinding in one command.',
        params: {
            'block_type': { type: 'BlockName', description: 'The block type to seek out and navigate to.' },
            'max_range': { type: 'int', description: 'Maximum search radius in blocks.', domain: [8, 512] },
        },
        perform: async function (agent, block_type, max_range = 256) {
            const bot = agent.bot;
            const err = ensureAshfinder(bot);
            if (err) return err;

            const blocks = bot.findBlocks({
                matching: b => b.name === block_type,
                maxDistance: max_range,
                count: 1,
            });
            if (!blocks || blocks.length === 0) {
                return `No ${block_type} found within ${max_range} blocks.`;
            }
            const target = blocks[0];
            try {
                const goals = getGoals();
                await bot.ashfinder.goto(new goals.GoalNear(target, 3));
                return `Reached ${block_type} at (${target.x}, ${target.y}, ${target.z}).`;
            } catch (e) {
                return `Navigation to ${block_type} at (${target.x}, ${target.y}, ${target.z}) stopped: ${e.message}`;
            }
        }
    },
];
