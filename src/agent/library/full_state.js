import { 
    getPosition,
    getBiomeName,
    getNearbyPlayerNames,
    getInventoryCounts,
    getNearbyEntityTypes,
    getBlockAtPosition,
    getFirstBlockAboveHead
} from "./world.js";
import convoManager from '../conversation.js';

export function getFullState(agent) {
    const bot = agent?.bot;
    const pos = getPosition(bot);
    const ready = !!pos;

    const position = pos ? {
        x: Number(pos.x.toFixed(2)),
        y: Number(pos.y.toFixed(2)),
        z: Number(pos.z.toFixed(2))
    } : null;

    const isIdle = typeof agent?.isIdle === 'function' ? agent.isIdle() : true;
    let bots = [];
    if (typeof convoManager.getInGameAgents === 'function') {
        bots = convoManager.getInGameAgents().filter(b => b !== agent?.name);
    }

    const state = {
        name: agent?.name ?? null,
        ready,
        gameplay: {
            position,
            dimension: bot?.game?.dimension ?? null,
            gamemode: bot?.game?.gameMode ?? null,
            health: Number.isFinite(bot?.health) ? Math.round(bot.health) : null,
            hunger: Number.isFinite(bot?.food) ? Math.round(bot.food) : null,
            biome: null,
            weather: 'Clear',
            timeOfDay: bot?.time?.timeOfDay ?? null,
            timeLabel: null
        },
        action: {
            current: isIdle ? 'Idle' : (agent?.actions?.currentActionLabel ?? null),
            isIdle
        },
        surroundings: {
            below: null,
            legs: null,
            head: null,
            firstBlockAboveHead: null
        },
        inventory: {
            counts: {},
            stacksUsed: 0,
            totalSlots: bot?.inventory?.slots?.length ?? 0,
            equipment: {
                helmet: null,
                chestplate: null,
                leggings: null,
                boots: null,
                mainHand: bot?.heldItem?.name ?? null
            }
        },
        nearby: {
            humanPlayers: [],
            botPlayers: bots,
            entityTypes: []
        },
        modes: {
            summary: bot?.modes?.getMiniDocs ? bot.modes.getMiniDocs() : null
        }
    };

    if (!ready) return state;

    if (bot.thunderState > 0) state.gameplay.weather = 'Thunderstorm';
    else if (bot.rainState > 0) state.gameplay.weather = 'Rain';

    if (Number.isFinite(state.gameplay.timeOfDay)) {
        state.gameplay.timeLabel = 'Night';
        if (state.gameplay.timeOfDay < 6000) state.gameplay.timeLabel = 'Morning';
        else if (state.gameplay.timeOfDay < 12000) state.gameplay.timeLabel = 'Afternoon';
    }

    try {
        state.gameplay.biome = getBiomeName(bot);
    } catch {
        // Ignore transient world-read errors during startup/reconnect.
    }

    try {
        state.surroundings.below = getBlockAtPosition(bot, 0, -1, 0)?.name ?? null;
        state.surroundings.legs = getBlockAtPosition(bot, 0, 0, 0)?.name ?? null;
        state.surroundings.head = getBlockAtPosition(bot, 0, 1, 0)?.name ?? null;
        state.surroundings.firstBlockAboveHead = getFirstBlockAboveHead(bot, null, 32);
    } catch {
        // Ignore transient world-read errors during startup/reconnect.
    }

    try {
        let players = getNearbyPlayerNames(bot);
        players = players.filter(p => !bots.includes(p));
        state.nearby.humanPlayers = players;
    } catch {
        // Ignore transient entity-read errors during startup/reconnect.
    }

    try {
        state.inventory.counts = getInventoryCounts(bot);
    } catch {
        // Ignore transient inventory-read errors during startup/reconnect.
    }
    state.inventory.stacksUsed = bot?.inventory?.items ? bot.inventory.items().length : 0;
    state.inventory.totalSlots = bot?.inventory?.slots?.length ?? 0;

    const slots = bot?.inventory?.slots ?? [];
    const helmet = slots[5];
    const chestplate = slots[6];
    const leggings = slots[7];
    const boots = slots[8];
    state.inventory.equipment.helmet = helmet ? helmet.name : null;
    state.inventory.equipment.chestplate = chestplate ? chestplate.name : null;
    state.inventory.equipment.leggings = leggings ? leggings.name : null;
    state.inventory.equipment.boots = boots ? boots.name : null;

    try {
        state.nearby.entityTypes = getNearbyEntityTypes(bot).filter(t => t !== 'player' && t !== 'item');
    } catch {
        // Ignore transient entity-read errors during startup/reconnect.
    }

    return state;
}
