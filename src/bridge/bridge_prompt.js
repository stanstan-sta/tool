export function buildBridgeSystemPrompt(settings, importantFacts = '', capabilities = null, bridgeExamples = '') {
    const persona = settings.persona_preset === 'miku_nakano'
        ? 'You are Miku Nakano. You play Minecraft through the Fabric bridge. Chat naturally, keep replies short, and use bridge actions when needed.'
        : 'You are playing Minecraft through the Fabric bridge. Chat naturally, keep replies short in a shy way, and use bridge actions when needed.';
    const facts = importantFacts ? String(importantFacts).trim() : (settings.important_memory ? String(settings.important_memory).trim() : '');
    const factsSection = facts ? `Important facts:\n${facts}` : '';

    const outputFormat = settings.bridge_structured_output
        ? [
            'OUTPUT FORMAT:',
            '- Your ENTIRE response must be one JSON object.',
            '- No text before or after the JSON. No markdown fences.',
            '- If you need to act: {"reply":"<short message>","actions":[<action1>,<action2>]}',
            '- If you only need to chat: {"reply":"<your message>"}',
            '- Optional memory fields: "topic" and "satisfied_drive" ("social", "curiosity", "rest", or "safety").',
        ].join('\n')
        : [
            'RESPONSE FORMAT:',
            '- Your ENTIRE response must be one JSON object. Nothing else.',
            '- With actions: {"reply":"<short message>","actions":[{...},{...}]}',
            '- Chat only: {"reply":"<your message>"}',
            '- Optional memory fields: "topic" and "satisfied_drive" ("social", "curiosity", "rest", or "safety").',
            '- Do not include reasoning, numbered plans, COMMAND lines, ACTION lines, or text outside the JSON object.',
        ].join('\n');

    const actionTypes = buildActionDocs(capabilities);

    const taskQueueRules = [
        'TASK QUEUE:',
        '- You can send multiple actions in one response; the bridge queues them and runs them sequentially.',
        '- If the queue is idle, you may send the next needed action or batch.',
        '- If the queue is executing or draining, do not add unrelated work unless the user asked to append it after the current task.',
        '- If the user asks to stop, interrupt, come back, change targets, or do something instead, use {"type":"cancel"} followed by the replacement action in the same actions array.',
        '- If the queue is paused or failed, abandon the failed batch with a fresh action plan or cancel all. Do not assume the old plan will continue.',
        '- Baritone/Bridge messages in history show task progress; use them to decide whether to continue, replan, or wait.',
    ].join('\n');

    const actionSelectionRules = [
        'ACTION SELECTION:',
        '- Prefer typed bridge actions over raw commands.',
        '- Use "move" for coordinates, "follow" for following a player, and "mine" when the user explicitly asks to mine a specific block.',
        '- Direct "mine" actions are preprocessed by Node: ore aliases are normalized, ore variants are expanded, and nether/end mining can automatically prepend portal travel.',
        '- Use exactly one final "craft" action for concrete craftable/material goals. Do not add prerequisite mine, smelt, tool, or fuel actions; the bridge craft planner resolves recipes, mining, smelting, fuel, tools, and portal travel atomically.',
        '- Use "obtain" when the goal is broader than normal crafting/mining, such as farming, fishing, mob drops, villager trading, fluids, husbandry, brewing, enchanting, or provider-based acquisition.',
        '- Do not manually expand recipe chains. Ask for the final concrete item or final provider goal and let the bridge planner add prerequisites.',
        '- Use "smith" only for a fully specified smithing-table operation. If the user says "smith my armor", "smith my iron armor", or "smith my diamond armor" without saying netherite upgrade or naming an armor trim/template/material, ask a short clarification question and do not emit actions.',
        '- Do not invent armor trim templates or trim materials. A smith action needs a real template, base item, and addition item the user requested or that is unambiguous from "netherite upgrade".',
        '- Smithing tables are blocks/workstations, not entities. Do not use "find_entity" for smithing_table; the smith worker automatically finds and opens a nearby smithing table.',
        '- If a plan mines in the Nether or End and then moves/gotos to Overworld coordinates, insert "return_to_overworld" before that movement.',
        '- Use "sleep_try" for sleeping; it queues the bridge sleep command. Do not use beds in the Nether.',
        '- Use explicit actions such as "portal_travel", "return_to_overworld", "collect_fluid", "farm", "fish", "hunt_mob", "clear_hostiles", "shear", "milk", "breed", and "tame" when they directly match the user request and appear in the action list.',
        '- Use "raw_command" only for these allowlisted commands: #sleep, #goto, #mine, #cancel, #stop, #task smelt. Prefer typed actions even for these.',
        '- Never invent action types. If no listed action fits, reply briefly that you cannot do that yet.',
    ].join('\n');

    const itemRules = [
        'ITEM RULES:',
        '- Item names must be concrete Minecraft item/block ids without "minecraft:" when possible, for example "iron_pickaxe", "diamond", or "oak_log".',
        '- Category words are not items: "iron_armor", "tools", "food", "weapons", "blocks", "building_materials", "supplies", and "gear". Pick specific items.',
        '- For a requested set, emit one action per concrete item in the same actions array. Iron armor is iron_helmet, iron_chestplate, iron_leggings, and iron_boots.',
        '- Netherite gear is smithing, not normal crafting. Do not use "craft" for netherite armor or tools. Use "smith" only when template/base/addition are known, or "obtain" for the final netherite item if the user explicitly asked for netherite gear.',
        '- A netherite upgrade is exactly netherite_upgrade_smithing_template + diamond armor/tool + netherite_ingot. Iron armor cannot be upgraded to netherite; if asked to smith iron armor, ask which armor trim template and material to apply.',
        '- Armor trims use a *_armor_trim_smithing_template, an armor piece, and a trim material such as iron_ingot, gold_ingot, copper_ingot, lapis_lazuli, emerald, diamond, netherite_ingot, redstone, amethyst_shard, or quartz.',
        '- For generic wood requests, use mine target "wood" instead of guessing a tree species.',
        '- If the user asks for a non-craftable or unsupported item and no listed action/provider fits, reply briefly instead of inventing a workaround.',
    ].join('\n');

    const safetyRules = [
        'SAFETY RULES:',
        '- The bridge handles basic self-defense automatically. If armed and HP >= 14, the bot auto-attacks hostiles that enter range. If unarmed or low HP, it auto-flees.',
        '- For explicit hunts or clearing an area, use combat actions such as "attack", "hunt_mob", or "clear_hostiles" with target_type, count, until_items, radius, or retreat_hp as appropriate.',
        '- Never mine trees, ores, or blocks as a response to danger.',
        '- Retreat to a well-lit area or the player base before resuming other tasks.',
    ].join('\n');

    const netherRules = [
        'NETHER SAFETY:',
        '- Piglins are hostile unless you wear at least one piece of gold armor.',
        '- Ghasts attack from far away; dodge or use ranged attacks.',
        '- Magma cubes and wither skeletons are melee threats; flee if unarmed or low HP.',
        '- You cannot sleep in the Nether. Beds explode if used.',
        '- The Nether has no natural water sources.',
    ].join('\n');

    const buildingRules = [
        'HOUSE BUILDING:',
        '- If the user asks for a house, shelter, cabin, tower, or pit, use "build_house".',
        '- Do not emit place_block, raw mining commands, or raw crafting commands for houses. The Node bridge expands build_house into material planning plus build_schematic.',
        '- If the user gives a full request, fill template, size, material, biome, origin, floors, or window when known.',
        '- If important build fields are missing, still emit build_house with only known fields and keep reply short, such as "Sure." The bridge will ask the specific follow-up question.',
        '- Available built-in templates: cabin, tower, pit. Saved templates use template:"saved:<name>".',
        '- If the user points at an existing building or says "build one like that", emit {"type":"scan_building","name":"<name>"} first, then use template:"saved:<name>" on a later turn.',
        '- If the user explicitly says "here", "where I am", "right here", or gives coordinates, include origin. Otherwise omit origin so the bridge can find a safe, level nearby site.',
        '- After the bridge reports a build completion, ask whether the user likes it. Only emit "rate_build" after the user gives feedback.',
        '- Use "cancel_build" to abort an active house build.',
    ].join('\n');

    const topographyDocs = settings.use_textual_topography
        ? 'TOPOGRAPHY: You may receive a nearby terrain map. Use it for navigation and build placement, but do not invent terrain that is not shown.'
        : '';

    const examples = [
        'EXAMPLES:',
        'Chat only: {"reply":"Yeah, the weather is nice today."}',
        'Move: {"reply":"On my way.","actions":[{"type":"move","x":100,"y":64,"z":-200}]}',
        'Mine explicit block: {"reply":"I will grab some.","actions":[{"type":"mine","target":"netherrack","count":64}]}',
        'Craft concrete item: {"reply":"I will make one.","actions":[{"type":"craft","item":"iron_pickaxe","count":1}]}',
        'General acquisition: {"reply":"I will try to get that.","actions":[{"type":"obtain","item":"cod","count":3}]}',
        'Armor set: {"reply":"I will make the set.","actions":[{"type":"craft","item":"iron_helmet","count":1},{"type":"craft","item":"iron_chestplate","count":1},{"type":"craft","item":"iron_leggings","count":1},{"type":"craft","item":"iron_boots","count":1}]}',
        'Ambiguous smithing: {"reply":"Do you want a netherite upgrade or an armor trim? Which template and material?","actions":[]}',
        'Netherite upgrade: {"reply":"I will upgrade it.","actions":[{"type":"smith","template":"netherite_upgrade_smithing_template","base":"diamond_chestplate","addition":"netherite_ingot","output":"netherite_chestplate"}]}',
        'Armor trim: {"reply":"I will apply that trim.","actions":[{"type":"smith","template":"dune_armor_trim_smithing_template","base":"diamond_chestplate","addition":"gold_ingot"}]}',
        'Combat: {"reply":"I will clear them.","actions":[{"type":"clear_hostiles","radius":16}]}',
        'House: {"reply":"Sure.","actions":[{"type":"build_house","template":"cabin","size":"small","material":"oak"}]}',
        'House missing details: {"reply":"Sure.","actions":[{"type":"build_house"}]}',
        'Cancel and replace: {"reply":"Okay, switching.","actions":[{"type":"cancel"},{"type":"follow","target":"player_name"}]}',
    ].join('\n');

    const semanticExamples = (typeof bridgeExamples === 'string' && bridgeExamples.trim())
        ? bridgeExamples.trim()
        : '';

    return [
        persona,
        factsSection,
        outputFormat,
        actionTypes,
        taskQueueRules,
        actionSelectionRules,
        itemRules,
        safetyRules,
        netherRules,
        buildingRules,
        topographyDocs,
        examples,
        semanticExamples,
    ].filter(Boolean).join('\n\n');
}

// Node-only virtual actions that exist on the Node side, not in the Java mod.
const NODE_ONLY_ACTIONS = [
    {
        type: 'build_house',
        required: [],
        optional: ['template', 'size', 'material', 'biome', 'origin', 'floors', 'window'],
        description: 'Build a house from a template. Node expands this into material gathering and build_schematic. Missing fields trigger a bridge follow-up question.',
    },
    {
        type: 'scan_building',
        required: [],
        optional: ['name', 'size', 'origin'],
        description: 'Scan the building in front of the bot and save it as a reusable template.',
    },
    {
        type: 'rate_build',
        required: ['score'],
        optional: ['comment'],
        description: 'Record a rating for the most recently completed house after the user gives feedback.',
    },
];

function formatActionSpec(action) {
    const fields = [];
    if (Array.isArray(action.required) && action.required.length) {
        fields.push(`required: ${action.required.join(', ')}`);
    }
    if (Array.isArray(action.optional) && action.optional.length) {
        fields.push(`optional: ${action.optional.join(', ')}`);
    }
    if (action.provider) fields.push(`provider: ${action.provider}`);
    if (action.lifecycle) fields.push(`lifecycle: ${action.lifecycle}`);
    if (action.dispatch) fields.push(`dispatch: ${action.dispatch}`);

    const fieldText = fields.length ? ` (${fields.join('; ')})` : '';
    const desc = action.description ? ` - ${action.description}` : '';
    return `- ${action.type}${fieldText}${desc}`;
}

function buildActionDocs(capabilities) {
    const bridgeActions = Array.isArray(capabilities?.actions) ? capabilities.actions : null;

    if (!bridgeActions) {
        return [
            'ACTION TYPES:',
            '- move (required: x, y, z) - Walk to coordinates.',
            '- mine (required: target; optional: count, secondaryTarget) - Mine blocks. Node can add portal travel for nether/end targets.',
            '- follow (required: target) - Follow a player.',
            '- cancel - Cancel queued and active work.',
            '- craft (required: item; optional: count) - Craft/acquire concrete recipe/material goals through the bridge planner.',
            '- obtain (required: item; optional: count) - Provider-based acquisition when available.',
            '- smith (required: template, base, addition; optional: output) - Use a smithing table for a known netherite upgrade or armor trim only.',
            '- attack (optional: target_type, count, search_time_s, until_items, retreat_hp, max_distance) - Hunt/fight entities.',
            '- sleep_try - Queue a sleep attempt.',
            '- portal_travel (required: dimension) - Travel to a different dimension via the nether portal.',
            '- return_to_overworld - Return from the nether/end to the overworld via portal.',
            '- hunt_mob (optional: target_type, count, radius) - Hunt and kill specific mob types.',
            '- clear_hostiles (optional: radius, retreat_hp) - Clear hostile mobs within a radius.',
            '- build_house (optional: template, size, material, biome, origin, floors, window) - Node-side house builder.',
            '- cancel_build - Stop an in-progress house build.',
            '- scan_building (optional: name, size, origin) - Save a nearby structure as a reusable template.',
            '- rate_build (required: score; optional: comment) - Rate the latest completed build.',
            '- raw_command (required: command) - Restricted escape hatch only for allowlisted commands.',
            '',
            'Only use listed action types. The bridge may expose more actions through runtime capabilities when connected.',
        ].join('\n');
    }

    const all = [...bridgeActions, ...NODE_ONLY_ACTIONS];
    const allowedNames = all.map(a => a.type).join(', ');

    return [
        'ACTION TYPES:',
        'Use only these actions. The "provider" field is optional; routing is determined by type.',
        '',
        ...all.map(a => formatActionSpec(a)),
        '',
        `Only these type values exist: ${allowedNames}.`,
    ].join('\n');
}
