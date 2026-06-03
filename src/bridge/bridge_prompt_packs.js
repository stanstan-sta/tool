const DEFAULT_FORMAT_MAX_CHARS = 2400;

function freezePack(pack) {
    return Object.freeze({
        ...pack,
        actions: Object.freeze([...pack.actions]),
        triggers: Object.freeze([...pack.triggers]),
        requires: Object.freeze([...pack.requires]),
    });
}

function clonePack(pack) {
    if (!pack) return null;
    return {
        ...pack,
        actions: [...pack.actions],
        triggers: [...pack.triggers],
        requires: [...pack.requires],
    };
}

function compactText(text) {
    return String(text || '').replace(/\s+/g, ' ').trim();
}

function packFromInput(packOrId) {
    if (typeof packOrId === 'string') {
        return BRIDGE_PROMPT_PACKS_BY_ID.get(packOrId) || null;
    }
    if (packOrId && typeof packOrId === 'object' && typeof packOrId.id === 'string') {
        return BRIDGE_PROMPT_PACKS_BY_ID.get(packOrId.id) || packOrId;
    }
    return null;
}

function trimToMaxChars(text, maxChars) {
    if (!Number.isFinite(maxChars)) return text;
    const limit = Math.max(0, Math.floor(maxChars));
    if (text.length <= limit) return text;
    if (limit <= 3) return text.slice(0, limit);
    return `${text.slice(0, limit - 3).trimEnd()}...`;
}

export const BRIDGE_PROMPT_PACKS = Object.freeze([
    freezePack({
        id: 'mining',
        title: 'Mining and Block Gathering',
        actions: ['mine', 'obtain'],
        triggers: ['mine', 'mining', 'gather', 'ore', 'diamond', 'iron', 'netherrack', 'ancient_debris', 'quartz', 'glowstone'],
        requires: ['dimension_travel', 'return_waypoints'],
        priority: 90,
        token_budget: 140,
        content: [
            'Use mine only when the user explicitly asks for blocks or ore. Keep target as a concrete Minecraft id without minecraft:.',
            'Direct mine actions are normalized by Node; ore aliases expand to variants and nether/end targets may prepend portal travel.',
            'Use obtain for final item goals that may need farming, fishing, mobs, trading, brewing, smithing, or mixed providers.',
            'For nether blocks such as netherrack, quartz, glowstone, or ancient_debris, include travel/return guidance when the later plan returns to overworld coordinates.',
        ].join(' '),
    }),
    freezePack({
        id: 'crafting',
        title: 'Crafting Final Items',
        actions: ['craft', 'obtain'],
        triggers: ['craft', 'make', 'build item', 'recipe', 'tools', 'armor', 'planks', 'sticks', 'pickaxe'],
        requires: ['mining', 'smelting'],
        priority: 95,
        token_budget: 150,
        content: [
            'Use one final craft action for concrete craftable/material goals and let the bridge planner resolve prerequisites.',
            'Do not manually emit prerequisite mine, smelt, tool, fuel, or table actions before a craft action.',
            'For requested sets, emit one action per concrete item in the same batch; category words like tools or iron_armor are not item ids.',
            'Use obtain instead of craft when the target is broader than normal crafting or is handled by a provider.',
        ].join(' '),
    }),
    freezePack({
        id: 'smelting',
        title: 'Smelting and Furnace Work',
        actions: ['craft', 'obtain', 'raw_command'],
        triggers: ['smelt', 'smelting', 'cook', 'furnace', 'glass', 'ingot', 'raw_iron', 'raw_gold', 'raw_copper'],
        requires: ['mining', 'crafting'],
        priority: 70,
        token_budget: 120,
        content: [
            'Prefer a final craft or obtain request for finished outputs; the bridge planner inserts #task smelt with fuel and furnace handling.',
            'Use raw_command #task smelt only for an explicit standalone request to smelt a known input.',
            'Do not add separate fuel, furnace, or ingredient actions unless the user explicitly asked to manage them.',
        ].join(' '),
    }),
    freezePack({
        id: 'smithing',
        title: 'Smithing Table Operations',
        actions: ['smith', 'obtain'],
        triggers: ['smith', 'smithing', 'netherite', 'upgrade', 'armor trim', 'trim', 'template', 'smithing_template'],
        requires: ['containers', 'crafting', 'mining', 'dimension_travel'],
        priority: 100,
        token_budget: 170,
        content: [
            'Use smith only for a fully specified smithing-table operation with template, base, and addition.',
            'Netherite upgrade is netherite_upgrade_smithing_template plus diamond gear plus netherite_ingot; iron gear cannot upgrade to netherite.',
            'Armor trims need a real *_armor_trim_smithing_template, an armor piece, and a requested trim material.',
            'If the user says smith my armor without upgrade/trim details, ask a short clarification and emit no actions.',
            'Smithing tables are workstation blocks, not entities; the worker finds and opens a nearby smithing table.',
        ].join(' '),
    }),
    freezePack({
        id: 'containers',
        title: 'Containers, Slots, and Looting',
        actions: ['open_block', 'close_screen', 'screen_click_slot', 'container_deposit', 'container_withdraw', 'container_quick_move', 'loot'],
        triggers: ['container', 'chest', 'barrel', 'shulker', 'screen', 'slots', 'inventory', 'deposit', 'withdraw', 'loot'],
        requires: [],
        priority: 80,
        token_budget: 150,
        content: [
            'Use loot for finding a requested item in nearby chests, barrels, or other containers.',
            'Use container_* actions only when a container screen is open or the target slot/context is known.',
            'When available, preserve sync_id for slot actions and prefer quick_move for shift-click transfers.',
            'If the user asks what is in a container or screen, use screen inspection guidance rather than guessing.',
        ].join(' '),
    }),
    freezePack({
        id: 'dimension_travel',
        title: 'Dimension and Portal Travel',
        actions: ['portal_travel', 'return_to_overworld', 'find_portal', 'light_portal'],
        triggers: ['dimension', 'portal', 'nether', 'end', 'overworld', 'netherrack', 'ancient_debris', 'quartz', 'return'],
        requires: ['return_waypoints'],
        priority: 90,
        token_budget: 130,
        content: [
            'Use portal_travel for explicit dimension changes and return_to_overworld when coming back from Nether or End.',
            'Nether/end mining or provider plans may add travel automatically; do not duplicate travel that is already in the queued plan.',
            'Never use beds in the Nether; sleeping there is unsafe and unsupported.',
            'Before moving to overworld coordinates after nether/end work, insert return_to_overworld first.',
        ].join(' '),
    }),
    freezePack({
        id: 'return_waypoints',
        title: 'Return Waypoints After Travel',
        actions: ['return_to_overworld', 'portal_travel', 'move'],
        triggers: ['return', 'come back', 'home', 'waypoint', 'origin', 'after mining', 'overworld coordinates'],
        requires: [],
        priority: 85,
        token_budget: 120,
        content: [
            'When a task leaves the origin dimension for Nether or End gathering, preserve the return waypoint if x, y, z, dimension, or playerName are available.',
            'If a later action moves to overworld coordinates, place return_to_overworld before that movement.',
            'Return metadata is guidance for the bridge/preprocessor; keep user-facing replies short and action-focused.',
        ].join(' '),
    }),
    freezePack({
        id: 'vision_inspection',
        title: 'Vision and Screen Inspection',
        actions: ['inspect_view_with_vision', 'inspect_screen_with_vision', 'look_and_inspect', 'look_at'],
        triggers: ['look', 'inspect', 'see', 'vision', 'screenshot', 'screen', 'gui', 'slots', 'what am i looking at'],
        requires: [],
        priority: 75,
        token_budget: 130,
        content: [
            'Use vision inspection only when the user explicitly asks to inspect the current view, a target, or an open screen.',
            'Use inspect_screen_with_vision for GUI, inventory, chest, brewing, smithing, or slot questions.',
            'Use look_and_inspect when the bot should look at a target and then inspect; include a concise question in the action.',
            'Do not infer unseen visual details without an inspection action.',
        ].join(' '),
    }),
    freezePack({
        id: 'combat',
        title: 'Combat and Hostile Safety',
        actions: ['attack', 'hunt_mob', 'clear_hostiles', 'ranged_attack', 'defend', 'retreat', 'flee', 'equip_best'],
        triggers: ['combat', 'attack', 'fight', 'hostile', 'zombie', 'skeleton', 'creeper', 'clear', 'hunt', 'raid', 'wither', 'dragon'],
        requires: [],
        priority: 95,
        token_budget: 140,
        content: [
            'Use clear_hostiles for clearing nearby threats and hunt_mob or attack for a specific mob target.',
            'Use retreat or flee when health is low, gear is missing, or the user asks to back off.',
            'Use ranged_attack for distant threats when ranged gear is relevant.',
            'Never respond to danger by mining trees, ores, or unrelated blocks; stabilize safety first.',
        ].join(' '),
    }),
    freezePack({
        id: 'brewing',
        title: 'Brewing Potions',
        actions: ['brew', 'obtain', 'craft'],
        triggers: ['brew', 'brewing', 'potion', 'water_bottle', 'awkward_potion', 'blaze_powder', 'nether_wart'],
        requires: ['containers', 'crafting', 'mining'],
        priority: 65,
        token_budget: 130,
        content: [
            'Use brew when the potion operation is known or use obtain for a final supported potion goal.',
            'The brew worker finds a nearby brewing stand and uses the screen; container guidance applies to stand slots.',
            'Let provider planning resolve bottles, blaze powder, nether wart, and mined ingredients when possible.',
            'Do not invent potion recipes; ask a short clarification when potion type or ingredient is ambiguous.',
        ].join(' '),
    }),
    freezePack({
        id: 'farming',
        title: 'Farming and Crop Harvesting',
        actions: ['farm', 'obtain'],
        triggers: ['farm', 'harvest', 'crop', 'wheat', 'carrot', 'potato', 'beetroot', 'melon', 'pumpkin', 'replant'],
        requires: [],
        priority: 70,
        token_budget: 110,
        content: [
            'Use farm for an explicit harvestable crop request; count is optional and the worker replants when configured.',
            'Use obtain for broader food or crop goals that may need provider selection.',
            'Do not confuse farming with mining plant blocks unless the user explicitly asks for block destruction.',
        ].join(' '),
    }),
]);

export const BRIDGE_PROMPT_PACKS_BY_ID = Object.freeze(new Map(
    BRIDGE_PROMPT_PACKS.map(pack => [pack.id, pack])
));

export function listBridgePromptPacks() {
    return BRIDGE_PROMPT_PACKS.map(clonePack);
}

export function getBridgePromptPack(id) {
    return clonePack(BRIDGE_PROMPT_PACKS_BY_ID.get(id) || null);
}

export function expandBridgePromptPackSelection(selected) {
    const queue = Array.isArray(selected) ? [...selected] : [];
    const out = [];
    const seen = new Set();

    while (queue.length) {
        const pack = packFromInput(queue.shift());
        if (!pack || seen.has(pack.id)) continue;

        seen.add(pack.id);
        out.push(clonePack(pack));

        for (const dependencyId of pack.requires) {
            if (!seen.has(dependencyId)) queue.push(dependencyId);
        }
    }

    return out;
}

export function formatBridgePromptPacks(selected, options = {}) {
    const packs = Array.isArray(selected)
        ? selected.map(packFromInput).filter(Boolean)
        : [];
    if (packs.length === 0) return '';

    const maxChars = options.maxChars === undefined
        ? DEFAULT_FORMAT_MAX_CHARS
        : Number(options.maxChars);
    const lines = [];
    if (options.includeHeader !== false) {
        lines.push('BRIDGE GUIDANCE PACKS:');
    }

    for (const pack of packs) {
        const requires = pack.requires.length ? pack.requires.join(',') : '-';
        lines.push(`[${pack.id}] ${pack.title} | actions:${pack.actions.join(',')} | requires:${requires} | p:${pack.priority} | b:${pack.token_budget}`);
        lines.push(`  ${compactText(pack.content)}`);
    }

    return trimToMaxChars(lines.join('\n'), maxChars);
}

export function getBridgePromptPackEmbeddingText(packOrId) {
    const pack = packFromInput(packOrId);
    if (!pack) return '';

    return [
        `id: ${pack.id}`,
        `title: ${pack.title}`,
        `actions: ${pack.actions.join(' ')}`,
        `triggers: ${pack.triggers.join(' ')}`,
        `requires: ${pack.requires.join(' ')}`,
        `priority: ${pack.priority}`,
        compactText(pack.content),
    ].filter(Boolean).join('\n');
}

export function listBridgePromptPackEmbeddingDocuments() {
    return BRIDGE_PROMPT_PACKS.map(pack => ({
        id: pack.id,
        text: getBridgePromptPackEmbeddingText(pack),
        metadata: {
            title: pack.title,
            actions: [...pack.actions],
            triggers: [...pack.triggers],
            requires: [...pack.requires],
            priority: pack.priority,
            token_budget: pack.token_budget,
        },
    }));
}
