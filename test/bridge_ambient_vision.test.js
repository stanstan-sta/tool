import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';

import settings from '../src/agent/settings.js';
import { BridgeAgent } from '../src/bridge/bridge_agent.js';
import { FabricBridge } from '../src/bridge/fabric_bridge.js';

function withSettings(values, fn) {
    const oldValues = {};
    const hadKey = {};
    for (const key of Object.keys(values)) {
        hadKey[key] = Object.prototype.hasOwnProperty.call(settings, key);
        oldValues[key] = settings[key];
        settings[key] = values[key];
    }
    return Promise.resolve()
        .then(fn)
        .finally(() => {
            for (const key of Object.keys(values)) {
                if (hadKey[key]) settings[key] = oldValues[key];
                else delete settings[key];
            }
        });
}

test('FabricBridge.getScreenshot returns jpeg buffer and forwards compression params', async () => {
    let requestedUrl = '';
    const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xd9]);
    const server = http.createServer((req, res) => {
        requestedUrl = req.url || '';
        res.writeHead(200, {
            'Content-Type': 'image/jpeg',
            'X-Mindcraft-Image-Width': '320',
            'X-Mindcraft-Image-Height': '180',
            'X-Mindcraft-Image-Quality': '0.8',
            'X-Mindcraft-Image-Downscale': '2',
        });
        res.end(jpeg);
    });

    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    try {
        const { port } = server.address();
        const bridge = new FabricBridge(`http://127.0.0.1:${port}`);
        const shot = await bridge.getScreenshot({ quality: 0.8, downscale: 2 });

        assert.equal(requestedUrl, '/screenshot?quality=0.8&downscale=2');
        assert.deepEqual(shot.buffer, jpeg);
        assert.equal(shot.mimeType, 'image/jpeg');
        assert.equal(shot.width, 320);
        assert.equal(shot.height, 180);
        assert.equal(shot.quality, 0.8);
        assert.equal(shot.downscale, 2);
    } finally {
        await new Promise(resolve => server.close(resolve));
    }
});

test('ambient vision capture is gated to ambient mode and configured quality', async () => {
    await withSettings({
        allow_vision: true,
        bridge_ambient_vision_enabled: true,
        bridge_ambient_vision_min_gap_ms: 30000,
        bridge_vision_quality: 0.8,
        bridge_vision_downscale: 2,
    }, async () => {
        const calls = [];
        const agent = Object.create(BridgeAgent.prototype);
        agent.prompter = { vision_model: { sendVisionRequest() {} } };
        agent._lastAmbientVisionAt = 0;
        agent._ambientLog = () => {};
        agent.bridge = {
            async getScreenshot(options) {
                calls.push(options);
                return {
                    buffer: Buffer.from([1, 2, 3]),
                    mimeType: 'image/jpeg',
                    width: 320,
                    height: 180,
                    quality: options.quality,
                    downscale: options.downscale,
                };
            }
        };

        const eventShot = await agent._captureAmbientVisionIfEligible({ connected: true }, 'event:night_start');
        assert.equal(eventShot, null);
        assert.equal(calls.length, 0);

        const ambientShot = await agent._captureAmbientVisionIfEligible({ connected: true }, 'ambient');
        assert.deepEqual(calls, [{ quality: 0.8, downscale: 2 }]);
        assert.equal(ambientShot.buffer.length, 3);

        const throttled = await agent._captureAmbientVisionIfEligible({ connected: true }, 'ambient');
        assert.equal(throttled, null);
        assert.equal(calls.length, 1);
    });
});

test('ambient vision capture is disabled when allow_vision is false', async () => {
    await withSettings({
        allow_vision: false,
        bridge_ambient_vision_enabled: true,
        bridge_ambient_vision_min_gap_ms: 30000,
    }, async () => {
        const agent = Object.create(BridgeAgent.prototype);
        agent.prompter = { vision_model: { sendVisionRequest() {} } };
        agent._lastAmbientVisionAt = 0;
        agent._ambientLog = () => {};
        agent.bridge = {
            async getScreenshot() {
                throw new Error('should not capture');
            }
        };

        const shot = await agent._captureAmbientVisionIfEligible({ connected: true }, 'ambient');
        assert.equal(shot, null);
    });
});

test('vision inspect screen slot question is answered from open-screen slots without screenshot', async () => {
    await withSettings({}, async () => {
        const addedHistory = [];
        const agent = Object.create(BridgeAgent.prototype);
        agent.name = 'Andy';
        agent.prompter = { vision_model: {} };
        agent._lastState = {
            connected: true,
            open_screen: {
                open: true,
                handler_class: 'GenericContainerScreenHandler',
                sync_id: 4,
                slots: [
                    { index: 0, item: 'minecraft:oak_log', count: 3 },
                    { index: 1, item: 'minecraft:iron_ingot', count: 2 },
                ],
            },
        };
        agent._buildStateContext = () => 'CURRENT STATE: compact';
        agent.history = {
            getHistory: () => [],
            add: (role, content) => addedHistory.push({ role, content }),
        };
        agent.bridge = {
            async getScreenshot() {
                throw new Error('slot-backed screen inspect should not capture a screenshot');
            },
            async sendBatch() {
                throw new Error('vision inspect action should not be forwarded to Java');
            },
        };
        agent._promptBridgeVisionLocked = async () => {
            throw new Error('slot-backed screen inspect should not call vision');
        };

        const result = await agent._sendBatchWithBuildExpansion([
            { type: 'inspect_screen_with_vision', question: 'What is in this container?' },
        ]);

        assert.equal(result.success, true);
        assert.equal(result.queued, 0);
        assert.equal(result.output, 'Open screen slots: GenericContainerScreenHandler (sync 4): slot 0: 3x oak_log, slot 1: 2x iron_ingot');
        assert.equal(addedHistory[0].content, 'Vision inspect (inspect_screen_with_vision): Open screen slots: GenericContainerScreenHandler (sync 4): slot 0: 3x oak_log, slot 1: 2x iron_ingot');
    });
});

test('look_and_inspect dispatches look_at before screenshot and vision prompt', async () => {
    await withSettings({
        bridge_vision_quality: 0.7,
        bridge_vision_downscale: 3,
        bridge_vision_look_stabilize_ms: 0,
    }, async () => {
        const events = [];
        const addedHistory = [];
        const agent = Object.create(BridgeAgent.prototype);
        agent.name = 'Andy';
        agent.prompter = { vision_model: { sendVisionRequest() {} } };
        agent._lastState = { connected: true };
        agent._buildStateContext = () => 'CURRENT STATE: compact';
        agent.history = {
            getHistory: () => [],
            add: (role, content) => addedHistory.push({ role, content }),
        };
        agent.bridge = {
            async sendBatch(actions) {
                events.push({ type: 'look', actions });
                return { success: true, queued: 0, output: 'look_at: set' };
            },
            async getScreenshot(options) {
                events.push({ type: 'screenshot', options });
                return {
                    buffer: Buffer.from([1, 2, 3]),
                    mimeType: 'image/jpeg',
                    width: 320,
                    height: 180,
                };
            },
        };
        agent._promptBridgeVisionLocked = async (_label, history, imageBuffer) => {
            events.push({
                type: 'vision',
                prompt: history.map(h => h.content).join('\n'),
                imageBytes: imageBuffer.length,
            });
            return '{"reply":"The target area has a chest.","actions":[]}';
        };

        const result = await agent._sendBatchWithBuildExpansion([
            { type: 'look_and_inspect', x: 10, y: 65, z: -2, question: 'What is at that position?' },
        ]);

        assert.deepEqual(events.map(event => event.type), ['look', 'screenshot', 'vision']);
        assert.deepEqual(events[0].actions, [{ type: 'look_at', x: 10, y: 65, z: -2 }]);
        assert.deepEqual(events[1].options, { quality: 0.7, downscale: 3 });
        assert.equal(events[2].imageBytes, 3);
        assert.match(events[2].prompt, /Inspect what the bot is looking at/);
        assert.equal(result.success, true);
        assert.equal(result.queued, 0);
        assert.equal(result.output, 'The target area has a chest.');
        assert.equal(addedHistory[0].content, 'Vision inspect (look_and_inspect): The target area has a chest.');
    });
});

test('vision inspect relays unsupported marker without screenshot capture', async () => {
    const agent = Object.create(BridgeAgent.prototype);
    agent.name = 'Andy';
    agent.prompter = { vision_model: {} };
    agent.history = { add() {} };
    agent.bridge = {
        async getScreenshot() {
            throw new Error('should not capture without vision support');
        },
    };

    const result = await agent._sendBatchWithBuildExpansion([
        { type: 'inspect_view_with_vision' },
    ]);

    assert.equal(result.success, true);
    assert.equal(result.output, 'vision_model_unsupported');
});

test('state formatting summarizes target, screen slots, environment, and long inventory', () => {
    const inventory = Array.from({ length: 18 }, (_, index) => ({
        item: `minecraft:item_${index}`,
        count: index + 1,
    }));
    const formatted = FabricBridge.formatState({
        connected: true,
        x: 1,
        y: 64,
        z: -2,
        dimension: 'minecraft:overworld',
        health: 20,
        hunger: 19,
        gameMode: 'survival',
        held_items: { main_hand: { item: 'minecraft:diamond_pickaxe', count: 1 } },
        equipment: { head: { item: 'minecraft:iron_helmet', count: 1 } },
        equipment_detail: { main_hand_durability: '120/250' },
        environment: { biome: 'plains', light: 12, weather: 'clear' },
        targeted_block: { id: 'minecraft:chest', x: 2, y: 64, z: -3 },
        open_screen: {
            open: true,
            handler_class: 'GenericContainerScreenHandler',
            slots: [{ index: 5, item: 'minecraft:apple', count: 4 }],
        },
        inventory,
        nearby_players: [],
        nearby_entities: [],
    });

    assert.match(formatted, /Equipment detail: main_hand_durability=120\/250/);
    assert.match(formatted, /Environment: biome=plains, light=12, weather=clear/);
    assert.match(formatted, /Targeted block: chest@\(2,64,-3\)/);
    assert.match(formatted, /Open screen: GenericContainerScreenHandler .*slots=5:4x apple/);
    assert.match(formatted, /Inventory: .*item_0.*\(\+2 more\)/);
});
