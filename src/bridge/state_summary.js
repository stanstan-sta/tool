function stripNamespace(value) {
    return String(value || '').replace(/^minecraft:/i, '');
}

function stackName(stack) {
    if (!stack) return '';
    if (typeof stack === 'string') return stripNamespace(stack);
    return stripNamespace(stack.item || stack.id || stack.name || stack.type || '');
}

function formatStack(stack) {
    const name = stackName(stack);
    if (!name) return '';
    const count = Number(stack?.count ?? stack?.qty ?? stack?.quantity ?? 1);
    return `${Number.isFinite(count) && count > 1 ? `${count}x ` : ''}${name}`;
}

function summarizeList(items, formatter, limit = 12) {
    const values = (Array.isArray(items) ? items : [])
        .map(formatter)
        .filter(Boolean);
    if (values.length === 0) return 'none';
    const shown = values.slice(0, limit);
    const remaining = values.length - shown.length;
    return remaining > 0 ? `${shown.join(', ')} (+${remaining} more)` : shown.join(', ');
}

function summarizeObjectEntries(obj, limit = 10) {
    if (!obj || typeof obj !== 'object') return '';
    const entries = Object.entries(obj)
        .filter(([, value]) => value !== null && value !== undefined && value !== '' && !(Array.isArray(value) && value.length === 0))
        .slice(0, limit)
        .map(([key, value]) => {
            if (typeof value === 'object') return `${key}=${JSON.stringify(value).slice(0, 120)}`;
            return `${key}=${String(value)}`;
        });
    return entries.join(', ');
}

export function summarizeInventory(inventory, limit = 16) {
    const stacks = (Array.isArray(inventory) ? inventory : [])
        .filter(stack => stack && stackName(stack));
    if (stacks.length === 0) return 'empty';
    const shown = stacks.slice(0, limit).map(formatStack);
    const remaining = stacks.length - shown.length;
    return remaining > 0 ? `${shown.join(', ')} (+${remaining} more)` : shown.join(', ');
}

export function summarizeEquipment(state) {
    const held = state?.held_items?.main_hand ? formatStack(state.held_items.main_hand) : 'empty';
    const offhand = state?.held_items?.offhand ? formatStack(state.held_items.offhand) : 'empty';
    const equipment = state?.equipment || {};
    const armor = ['head', 'chest', 'legs', 'feet']
        .map(slot => stackName(equipment[slot]) || 'empty')
        .join('/');
    const detail = state?.equipment_detail
        ? summarizeObjectEntries(state.equipment_detail, 8) || summarizeList(state.equipment_detail, formatStack, 8)
        : '';
    return {
        held,
        offhand,
        armor: state?.equipment ? armor : 'unknown',
        detail,
    };
}

export function summarizeOpenScreen(openScreen, limit = 12) {
    if (!openScreen?.open) return '';
    const label = openScreen.title || openScreen.name || openScreen.handler_class || openScreen.screen_class || '?';
    const parts = [`Open screen: ${label}`];
    if (openScreen.sync_id !== undefined) parts.push(`sync=${openScreen.sync_id}`);

    const slots = Array.isArray(openScreen.slots)
        ? openScreen.slots
        : (Array.isArray(openScreen.slot_summary) ? openScreen.slot_summary : []);
    const slotSummary = summarizeList(slots, slot => {
        const idx = slot.index ?? slot.slot ?? slot.id;
        const stack = formatStack(slot.stack || slot);
        return stack ? `${idx !== undefined ? `${idx}:` : ''}${stack}` : '';
    }, limit);
    if (slotSummary !== 'none') parts.push(`slots=${slotSummary}`);
    return parts.join(' | ');
}

export function summarizeTarget(state) {
    const parts = [];
    const block = state?.targeted_block || state?.target_block || state?.look_block;
    if (block) {
        const name = stripNamespace(block.id || block.block || block.type || block.name || block.item || '');
        const pos = [block.x, block.y, block.z].every(Number.isFinite) ? `@(${block.x},${block.y},${block.z})` : '';
        parts.push(`Targeted block: ${name || '?'}${pos}`);
    }
    const entity = state?.targeted_entity || state?.target_entity || state?.look_entity;
    if (entity) {
        const name = stripNamespace(entity.type || entity.name || entity.id || '');
        const pos = [entity.x, entity.y, entity.z].every(Number.isFinite) ? `@(${entity.x},${entity.y},${entity.z})` : '';
        parts.push(`Targeted entity: ${name || '?'}${pos}`);
    }
    return parts.join('\n');
}

export function summarizeEnvironment(environment) {
    if (!environment) return '';
    if (typeof environment === 'string') return environment;
    if (Array.isArray(environment)) return summarizeList(environment, value => String(value || ''), 10);
    return summarizeObjectEntries(environment, 10);
}

export function buildFabricStateLines(state, options = {}) {
    if (!state || !state.connected) return ['Fabric client not connected.'];

    const dim = stripNamespace(state.dimension || 'overworld').replace(/^the_/, '');
    const equipment = summarizeEquipment(state);
    const nearbyPlayers = (state.nearby_players || []).join(', ') || 'none';
    const nearbyEntities = summarizeList(
        (state.nearby_entities || []).slice(0, options.entityLimit || 5),
        e => `${stripNamespace(e.type || '?')}@(${e.x},${e.y},${e.z})`,
        options.entityLimit || 5,
    );

    const lines = [
        `Position: x=${state.x}, y=${state.y}, z=${state.z}  Dimension: ${dim}`,
        `Health: ${state.health}/20  Hunger: ${state.hunger}/20  Mode: ${state.gameMode || '?'}`,
        `Held: ${equipment.held}  Offhand: ${equipment.offhand}  Armor: ${equipment.armor}`,
    ];
    if (equipment.detail) lines.push(`Equipment detail: ${equipment.detail}`);

    const environment = summarizeEnvironment(state.environment);
    if (environment) lines.push(`Environment: ${environment}`);

    const target = summarizeTarget(state);
    if (target) lines.push(...target.split('\n'));

    if (state.effects && state.effects.length > 0) {
        lines.push(`Effects: ${state.effects.map(e => `${stripNamespace(e.id)}(${(e.amplifier ?? 0) + 1})`).join(', ')}`);
    }

    const screen = summarizeOpenScreen(state.open_screen, options.screenSlotLimit || 12);
    if (screen) lines.push(screen);

    lines.push(`Inventory: ${summarizeInventory(state.inventory, options.inventoryLimit || 16)}`);
    lines.push(`Nearby players: ${nearbyPlayers}`);
    lines.push(`Nearby entities: ${nearbyEntities}`);

    if (state.queue && state.queue.status !== 'idle' && state.queue.status !== 'disabled') {
        const q = state.queue;
        let queueLine = `Queue: ${q.status} | Pending: ${q.pending}`;
        if (q.active) queueLine += ` | Active: ${q.active}`;
        if (q.paused) queueLine += ' | PAUSED';
        if (q.lastFailure) queueLine += ` | Last failure: ${q.lastFailure}`;
        lines.push(queueLine);
    }

    return lines;
}
