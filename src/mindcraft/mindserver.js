import { Server } from 'socket.io';
import express from 'express';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import * as mindcraft from './mindcraft.js';
import { readFileSync, writeFileSync, readdirSync, existsSync, unlinkSync, mkdirSync } from 'fs';
import settings from '../../settings.js';
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Mindserver is:
// - central hub for communication between all agent processes
// - api to control from other languages and remote users 
// - host for webapp

let io;
let server;
const agent_connections = {};
const agent_listeners = [];

const settings_spec = JSON.parse(readFileSync(path.join(__dirname, 'public/settings_spec.json'), 'utf8'));

// Top-level settings the web UI persists across full app restarts. main.js
// merges this file over the settings.js defaults on startup. Profile-level
// fields are NOT stored here — they are written to each profile's JSON.
const SETTINGS_LOCAL_PATH = path.join(__dirname, '../../settings_local.json');
const NON_PERSISTED_KEYS = new Set(['profile', 'profile_path', 'profiles', 'task']);

const PROFILES_DIR = path.join(__dirname, '../../profiles');

function sanitizeProfileName(name) {
    const cleaned = String(name || '').trim().replace(/[^a-zA-Z0-9_-]/g, '');
    if (!cleaned) throw new Error('Profile name must contain only letters, numbers, underscores, and hyphens.');
    if (cleaned.length > 64) throw new Error('Profile name too long (max 64 chars).');
    return cleaned;
}

function validateProfile(profile) {
    if (!profile || typeof profile !== 'object' || Array.isArray(profile)) {
        throw new Error('Profile must be a JSON object.');
    }
    if (!profile.name || typeof profile.name !== 'string' || !profile.name.trim()) {
        throw new Error('Profile must have a non-empty "name" string.');
    }
    return true;
}

function profileFilePath(name) {
    return path.join(PROFILES_DIR, `${sanitizeProfileName(name)}.json`);
}

function readStartupProfiles() {
    return Array.isArray(settings.profiles) ? [...settings.profiles] : [];
}

function persistStartupProfiles(profiles) {
    settings.profiles = profiles;
    let existing = {};
    if (existsSync(SETTINGS_LOCAL_PATH)) {
        try { existing = JSON.parse(readFileSync(SETTINGS_LOCAL_PATH, 'utf8')) || {}; }
        catch { existing = {}; }
    }
    existing.profiles = profiles;
    writeFileSync(SETTINGS_LOCAL_PATH, JSON.stringify(existing, null, 4), 'utf8');
}

// Persist the top-level (non-profile) settings the UI sent so they survive a
// full restart. Stores only keys defined in settings_spec, minus profile data.
function persistGlobalSettings(settings) {
    try {
        let existing = {};
        if (existsSync(SETTINGS_LOCAL_PATH)) {
            try { existing = JSON.parse(readFileSync(SETTINGS_LOCAL_PATH, 'utf8')) || {}; }
            catch { existing = {}; }
        }
        const out = { ...existing };
        for (const key of Object.keys(settings)) {
            if (NON_PERSISTED_KEYS.has(key)) continue;
            if (!(key in settings_spec)) continue;
            out[key] = settings[key];
        }
        writeFileSync(SETTINGS_LOCAL_PATH, JSON.stringify(out, null, 4), 'utf8');
        console.log(`Persisted ${Object.keys(out).length} setting(s) to settings_local.json`);
    } catch (err) {
        console.error('Failed to persist settings_local.json:', err);
    }
}

class AgentConnection {
    constructor(settings) {
        this.socket = null;
        this.settings = settings;
        this.in_game = false;
        this.full_state = null;
        this.profile_path = settings.profile_path || null;
    }
    setSettings(settings) {
        this.settings = { ...this.settings, ...settings };
    }
}

export function registerAgent(settings) {
    let agentConnection = new AgentConnection(settings);
    agent_connections[settings.profile.name] = agentConnection;
}

export function logoutAgent(agentName) {
    if (agent_connections[agentName]) {
        agent_connections[agentName].in_game = false;
        agentsStatusUpdate();
    }
}

// Initialize the server
export function createMindServer(host_public = false, port = 8080) {
    const app = express();
    server = http.createServer(app);
    io = new Server(server);

    // Serve static files
    const __dirname = path.dirname(fileURLToPath(import.meta.url));
    app.use(express.static(path.join(__dirname, 'public')));

    // Socket.io connection handling
    io.on('connection', (socket) => {
        let curAgentName = null;
        console.log('Client connected');

        agentsStatusUpdate(socket);

        socket.on('create-agent', async (settings, callback) => {
            console.log('API create agent...');
            for (let key in settings_spec) {
                if (!(key in settings)) {
                    if (settings_spec[key].required) {
                        callback({ success: false, error: `Setting ${key} is required` });
                        return;
                    }
                    else {
                        settings[key] = settings_spec[key].default;
                    }
                }
            }
            for (let key in settings) {
                if (!(key in settings_spec)) {
                    delete settings[key];
                }
            }
            if (settings.profile?.name) {
                if (settings.profile.name in agent_connections) {
                    callback({ success: false, error: 'Agent already exists' });
                    return;
                }
                try {
                    validateProfile(settings.profile);
                    const safeName = sanitizeProfileName(settings.profile.name);
                    const filePath = path.join(PROFILES_DIR, `${safeName}.json`);
                    if (!existsSync(filePath)) {
                        if (!existsSync(PROFILES_DIR)) mkdirSync(PROFILES_DIR, { recursive: true });
                        writeFileSync(filePath, JSON.stringify(settings.profile, null, 4), 'utf8');
                        console.log(`Saved new profile to ${filePath}`);
                    }
                    settings.profile_path = filePath;
                } catch (err) {
                    callback({ success: false, error: `Failed to save profile: ${err.message}` });
                    return;
                }
                let returned = await mindcraft.createAgent(settings);
                callback({ success: returned.success, error: returned.error });
                let name = settings.profile.name;
                if (!returned.success && agent_connections[name]) {
                    mindcraft.destroyAgent(name);
                    delete agent_connections[name];
                }
                agentsStatusUpdate();
            }
            else {
                console.error('Agent name is required in profile');
                callback({ success: false, error: 'Agent name is required in profile' });
            }
        });

        socket.on('get-settings', (agentName, callback) => {
            if (agent_connections[agentName]) {
                callback({ settings: agent_connections[agentName].settings });
            } else {
                callback({ error: `Agent '${agentName}' not found.` });
            }
        });

        socket.on('get-agent-memory', (agentName, callback) => {
            const agent = agent_connections[agentName];
            if (agent && agent.socket) {
                agent.socket.emit('get-agent-memory', (memory) => {
                    callback({ success: true, memory: memory ?? '' });
                });
            } else {
                callback({ success: false, error: `Agent '${agentName}' not found or not connected.` });
            }
        });

        socket.on('connect-agent-process', (agentName) => {
            if (agent_connections[agentName]) {
                agent_connections[agentName].socket = socket;
                agentsStatusUpdate();
            }
        });

        socket.on('login-agent', (agentName) => {
            if (agent_connections[agentName]) {
                agent_connections[agentName].socket = socket;
                agent_connections[agentName].in_game = true;
                curAgentName = agentName;
                agentsStatusUpdate();
            }
            else {
                console.warn(`Unregistered agent ${agentName} tried to login`);
            }
        });

        socket.on('disconnect', () => {
            if (agent_connections[curAgentName]) {
                console.log(`Agent ${curAgentName} disconnected`);
                agent_connections[curAgentName].in_game = false;
                agent_connections[curAgentName].socket = null;
                agentsStatusUpdate();
            }
            if (agent_listeners.includes(socket)) {
                removeListener(socket);
            }
        });

        socket.on('chat-message', (agentName, json) => {
            if (!agent_connections[agentName]) {
                console.warn(`Agent ${agentName} tried to send a message but is not logged in`);
                return;
            }
            console.log(`${curAgentName} sending message to ${agentName}: ${json.message}`);
            agent_connections[agentName].socket.emit('chat-message', curAgentName, json);
        });

        socket.on('set-agent-settings', (agentName, settings, callback) => {
            try {
                const agent = agent_connections[agentName];
                if (!agent) {
                    console.warn(`set-agent-settings: no agent named '${agentName}'`);
                    if (callback) callback({ success: false, error: `Agent '${agentName}' not found.` });
                    return;
                }
                if (settings.profile) {
                    try { validateProfile(settings.profile); }
                    catch (err) {
                        if (callback) callback({ success: false, error: err.message });
                        return;
                    }
                }
                agent.setSettings(settings);
                if (agent.profile_path && settings.profile) {
                    try {
                        writeFileSync(agent.profile_path, JSON.stringify(settings.profile, null, 4), 'utf8');
                        console.log(`Saved profile for ${agentName} to ${agent.profile_path}`);
                    } catch (err) {
                        console.error(`Failed to save profile for ${agentName}:`, err);
                        if (callback) callback({ success: false, error: `Failed to save profile: ${err.message}` });
                        return;
                    }
                }
                persistGlobalSettings(settings);
                mindcraft.startAgent(agentName);
                if (callback) callback({ success: true });
            } catch (err) {
                console.error('set-agent-settings handler failed:', err);
                if (callback) callback({ success: false, error: err.message });
            }
        });

        socket.on('restart-agent', (agentName) => {
            try {
                console.log(`Restarting agent: ${agentName}`);
                mindcraft.startAgent(agentName);
            } catch (err) {
                console.error('restart-agent handler failed:', err);
            }
        });

        socket.on('stop-agent', (agentName) => {
            mindcraft.stopAgent(agentName);
        });

        socket.on('start-agent', (agentName) => {
            mindcraft.startAgent(agentName);
        });

        socket.on('destroy-agent', (agentName) => {
            if (agent_connections[agentName]) {
                mindcraft.destroyAgent(agentName);
                delete agent_connections[agentName];
            }
            agentsStatusUpdate();
        });

        socket.on('stop-all-agents', () => {
            console.log('Killing all agents');
            for (let agentName in agent_connections) {
                mindcraft.stopAgent(agentName);
            }
        });

        socket.on('shutdown', () => {
            console.log('Shutting down');
            for (let agentName in agent_connections) {
                mindcraft.stopAgent(agentName);
            }
            // wait 2 seconds
            setTimeout(() => {
                console.log('Exiting MindServer');
                process.exit(0);
            }, 2000);
            
        });

		socket.on('send-message', (agentName, data) => {
			if (!agent_connections[agentName]) {
				console.warn(`Agent ${agentName} not in game, cannot send message via MindServer.`);
				return;
			}
			try {
				agent_connections[agentName].socket.emit('send-message', data);
			} catch (error) {
				console.error('Error: ', error);
			}
		});

        socket.on('clear-agent-memory', (agentName, preserveImportant = false) => {
            const agent = agent_connections[agentName];
            if (agent?.socket) {
                agent.socket.emit('clear-agent-memory', preserveImportant);
            }
        });

        socket.on('compact-agent-memory', (agentName, reason = 'manual') => {
            const agent = agent_connections[agentName];
            if (agent?.socket) {
                agent.socket.emit('compact-agent-memory', reason);
            }
        });

        socket.on('set-important-memory', (agentName, memoryText) => {
            const agent = agent_connections[agentName];
            if (agent?.socket) {
                agent.socket.emit('set-important-memory', memoryText);
            }
        });

        socket.on('bot-output', (agentName, message) => {
            io.emit('bot-output', agentName, message);
        });

        socket.on('listen-to-agents', () => {
            addListener(socket);
        });
    });

    app.get('/api/keys', async (req, res) => {
        try {
            const keysPath = path.join(__dirname, '../../keys.json');
            const examplePath = path.join(__dirname, '../../keys.example.json');
            let keys = {};
            let exampleKeys = {};
            try {
                const data = readFileSync(keysPath, 'utf8');
                keys = JSON.parse(data);
            } catch (e) { /* keys.json may not exist yet */ }
            try {
                const data = readFileSync(examplePath, 'utf8');
                exampleKeys = JSON.parse(data);
            } catch (e) { /* no example either */ }

            const result = {};
            for (const k of Object.keys(exampleKeys)) {
                const val = keys[k];
                if (val && typeof val === 'string' && val.length > 0) {
                    const mask = val.length > 8 ? val.substring(0, 4) + '...' + val.substring(val.length - 4) : '***';
                    result[k] = { set: true, mask };
                } else {
                    result[k] = { set: false, mask: null };
                }
            }
            // Include any extra keys not in example
            for (const k of Object.keys(keys)) {
                if (!result[k]) {
                    const val = keys[k];
                    const mask = val.length > 8 ? val.substring(0, 4) + '...' + val.substring(val.length - 4) : '***';
                    result[k] = { set: true, mask };
                }
            }
            res.json(result);
        } catch (err) {
            console.error('Failed to load keys:', err);
            res.status(500).json({ error: 'Failed to load keys' });
        }
    });

    app.post('/api/keys', express.json(), async (req, res) => {
        try {
            const keysPath = path.join(__dirname, '../../keys.json');
            let keys = {};
            try {
                const data = readFileSync(keysPath, 'utf8');
                keys = JSON.parse(data);
            } catch (e) { /* may not exist */ }
            const updates = req.body;
            for (const [k, v] of Object.entries(updates)) {
                if (typeof v === 'string' && v.length > 0) {
                    keys[k] = v;
                } else {
                    delete keys[k];
                }
            }
            writeFileSync(keysPath, JSON.stringify(keys, null, 4), 'utf8');
            res.json({ success: true });
        } catch (err) {
            console.error('Failed to save keys:', err);
            res.status(500).json({ error: 'Failed to save keys' });
        }
    });

    app.get('/api/model_prefixes', async (req, res) => {
        try {
            const { apiMap } = await import('../models/_model_map.js');
            const prefixes = Object.keys(apiMap);
            // Also include common known APIs that don't require a prefix
            const knownSemantic = ['openai', 'anthropic', 'google', 'xai', 'mistral', 'deepseek', 'qwen'];
            const allApis = [...new Set([...prefixes, ...knownSemantic])].sort();
            res.json(allApis);
        } catch (err) {
            console.error('Failed to load model prefixes:', err);
            res.status(500).json({ error: 'Failed to load model prefixes' });
        }
    });

    // ── Profile library management ──────────────────────────────────────
    // List, create, read, update, and delete profile JSON files on disk.
    // All file operations are constrained to PROFILES_DIR to prevent path traversal.

    app.get('/api/profiles', async (req, res) => {
        try {
            const profiles = [];
            if (existsSync(PROFILES_DIR)) {
                const files = readdirSync(PROFILES_DIR).filter(f => f.endsWith('.json'));
                for (const f of files) {
                    const filePath = path.join(PROFILES_DIR, f);
                    try {
                        const data = JSON.parse(readFileSync(filePath, 'utf8'));
                        if (data && typeof data === 'object') {
                            const modelStr = data.model && typeof data.model === 'object'
                                ? (data.model.model || JSON.stringify(data.model))
                                : (data.model || 'unknown');
                            profiles.push({
                                name: data.name || path.basename(f, '.json'),
                                filename: f,
                                path: `./profiles/${f}`,
                                model: modelStr,
                                hasPersonality: !!(data.personality && data.personality.trim()),
                            });
                        }
                    } catch { /* skip unparseable */ }
                }
            }
            const repoRoot = path.join(__dirname, '../..');
            for (const f of readdirSync(repoRoot).filter(x => x.endsWith('.json') && x !== 'package.json' && x !== 'keys.json' && x !== 'keys.example.json' && x !== 'settings_local.json')) {
                const filePath = path.join(repoRoot, f);
                try {
                    const data = JSON.parse(readFileSync(filePath, 'utf8'));
                    if (data && typeof data === 'object' && data.name && (data.model || data.personality)) {
                        const modelStr = data.model && typeof data.model === 'object'
                            ? (data.model.model || JSON.stringify(data.model))
                            : (data.model || 'unknown');
                        profiles.push({
                            name: data.name,
                            filename: f,
                            path: `./${f}`,
                            model: modelStr,
                            hasPersonality: !!(data.personality && data.personality.trim()),
                        });
                    }
                } catch { /* skip */ }
            }
            profiles.sort((a, b) => a.name.localeCompare(b.name));
            res.json(profiles);
        } catch (err) {
            console.error('Failed to list profiles:', err);
            res.status(500).json({ error: 'Failed to list profiles' });
        }
    });

    app.get('/api/profiles/:name', async (req, res) => {
        try {
            const safeName = sanitizeProfileName(req.params.name);
            const filePath = path.join(PROFILES_DIR, `${safeName}.json`);
            if (!existsSync(filePath)) {
                return res.status(404).json({ error: `Profile '${safeName}' not found.` });
            }
            const data = JSON.parse(readFileSync(filePath, 'utf8'));
            res.json(data);
        } catch (err) {
            res.status(400).json({ error: err.message });
        }
    });

    app.post('/api/profiles', express.json(), async (req, res) => {
        try {
            const profile = req.body;
            validateProfile(profile);
            const safeName = sanitizeProfileName(profile.name);
            const filePath = path.join(PROFILES_DIR, `${safeName}.json`);
            if (existsSync(filePath)) {
                return res.status(409).json({ error: `Profile '${safeName}' already exists. Use PUT to update.` });
            }
            if (!existsSync(PROFILES_DIR)) mkdirSync(PROFILES_DIR, { recursive: true });
            writeFileSync(filePath, JSON.stringify(profile, null, 4), 'utf8');
            console.log(`Created profile ${safeName} at ${filePath}`);
            res.status(201).json({ success: true, path: `./profiles/${safeName}.json` });
        } catch (err) {
            res.status(400).json({ error: err.message });
        }
    });

    app.put('/api/profiles/:name', express.json(), async (req, res) => {
        try {
            const safeName = sanitizeProfileName(req.params.name);
            const profile = req.body;
            validateProfile(profile);
            const filePath = path.join(PROFILES_DIR, `${safeName}.json`);
            if (!existsSync(filePath)) {
                return res.status(404).json({ error: `Profile '${safeName}' not found.` });
            }
            writeFileSync(filePath, JSON.stringify(profile, null, 4), 'utf8');
            console.log(`Updated profile ${safeName} at ${filePath}`);
            const agentName = profile.name;
            if (agent_connections[agentName] && agent_connections[agentName].profile_path === filePath) {
                agent_connections[agentName].settings.profile = profile;
            }
            res.json({ success: true });
        } catch (err) {
            res.status(400).json({ error: err.message });
        }
    });

    app.delete('/api/profiles/:name', async (req, res) => {
        try {
            const safeName = sanitizeProfileName(req.params.name);
            const filePath = path.join(PROFILES_DIR, `${safeName}.json`);
            if (!existsSync(filePath)) {
                return res.status(404).json({ error: `Profile '${safeName}' not found.` });
            }
            for (const agentName in agent_connections) {
                if (agent_connections[agentName].profile_path === filePath) {
                    return res.status(409).json({ error: `Profile is in use by agent '${agentName}'. Stop the agent first.` });
                }
            }
            unlinkSync(filePath);
            console.log(`Deleted profile ${safeName}`);
            const startup = readStartupProfiles().filter(p => p !== `./profiles/${safeName}.json`);
            if (startup.length !== readStartupProfiles().length) {
                persistStartupProfiles(startup);
            }
            res.json({ success: true });
        } catch (err) {
            res.status(400).json({ error: err.message });
        }
    });

    // ── Startup profile list management ─────────────────────────────────
    // Controls which profiles load automatically at boot (settings.profiles).

    app.get('/api/startup-profiles', async (req, res) => {
        res.json(readStartupProfiles());
    });

    app.put('/api/startup-profiles', express.json(), async (req, res) => {
        try {
            const profiles = req.body;
            if (!Array.isArray(profiles)) {
                return res.status(400).json({ error: 'Expected a JSON array of profile paths.' });
            }
            for (const p of profiles) {
                if (typeof p !== 'string') {
                    return res.status(400).json({ error: 'All entries must be strings.' });
                }
            }
            persistStartupProfiles(profiles);
            console.log(`Updated startup profiles: ${profiles.join(', ')}`);
            res.json({ success: true, profiles });
        } catch (err) {
            res.status(400).json({ error: err.message });
        }
    });

    app.get('/api/debug_stats', async (req, res) => {
        const stats = {
            models: [],
            memory: 'Unknown',
            ollama_loaded: []
        };

        for (const agentName in agent_connections) {
            const conn = agent_connections[agentName];
            const profile = conn.settings && conn.settings.profile;
            if (profile) {
                const modelVal = profile.model;
                const modelStr = modelVal && typeof modelVal === 'object'
                    ? (modelVal.model || JSON.stringify(modelVal))
                    : (modelVal || 'unknown');
                stats.models.push({ name: profile.name || agentName, model: modelStr });
            }
        }

        try {
            const ollamaRes = await fetch('http://127.0.0.1:11434/api/ps');
            if (ollamaRes.ok) {
                const data = await ollamaRes.json();
                if (data.models && data.models.length > 0) {
                    let totalMemoryBytes = 0;
                    data.models.forEach(m => { totalMemoryBytes += (m.size_vram || m.size || 0); });
                    stats.memory = (totalMemoryBytes / 1024 / 1024 / 1024).toFixed(2) + ' GB';
                    stats.ollama_loaded = data.models.map(m => m.name);
                } else {
                    stats.memory = '0 GB (No models loaded)';
                }
            }
        } catch (err) {
            stats.memory = 'Ollama not reachable';
        }

        res.json(stats);
    });

    if (host_public) {
        console.log('Public hosting not supported yet. Using localhost.');
    }
    const host = 'localhost';
    server.listen(port, host, () => {
        console.log(`MindServer running on port ${port} on host ${host}`);
    });

    return server;
}

function agentsStatusUpdate(socket) {
    if (!socket) {
        socket = io;
    }
    let agents = [];
    for (let agentName in agent_connections) {
        const conn = agent_connections[agentName];
        agents.push({
            name: agentName, 
            in_game: conn.in_game,
            socket_connected: !!conn.socket
        });
    };
    socket.emit('agents-status', agents);
}


let listenerInterval = null;
function addListener(listener_socket) {
    agent_listeners.push(listener_socket);
    if (agent_listeners.length === 1) {
        listenerInterval = setInterval(async () => {
            const states = {};
            for (let agentName in agent_connections) {
                let agent = agent_connections[agentName];
                if (agent.in_game) {
                    try {
                        const state = await new Promise((resolve) => {
                            agent.socket.emit('get-full-state', (s) => resolve(s));
                        });
                        states[agentName] = state;
                    } catch (e) {
                        states[agentName] = { error: String(e) };
                    }
                }
            }
            for (let listener of agent_listeners) {
                listener.emit('state-update', states);
            }
        }, 1000);
    }
}

function removeListener(listener_socket) {
    agent_listeners.splice(agent_listeners.indexOf(listener_socket), 1);
    if (agent_listeners.length === 0) {
        clearInterval(listenerInterval);
        listenerInterval = null;
    }
}

// Optional: export these if you need access to them from other files
export const getIO = () => io;
export const getServer = () => server;
export const numStateListeners = () => agent_listeners.length;
