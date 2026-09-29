import { Server } from 'socket.io';
import express from 'express';
import http from 'http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import path from 'path';
import { fileURLToPath } from 'url';
import * as mindcraft from './mindcraft.js';
import { readFileSync, writeFileSync, renameSync, readdirSync, existsSync, unlinkSync, mkdirSync } from 'fs';
import settings from '../../settings.js';
import { readStartupProfile, validateProfile } from './startup_profiles.js';
const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Mindserver is:
// - central hub for communication between all agent processes
// - api to control from other languages and remote users 
// - host for webapp

let io;
let server;
const agent_connections = Object.create(null);
const agent_listeners = [];

const settings_spec = JSON.parse(readFileSync(path.join(__dirname, 'public/settings_spec.json'), 'utf8'));

// Top-level settings the web UI persists across full app restarts. main.js
// merges this file over the settings.js defaults on startup. Profile-level
// fields are NOT stored here — they are written to each profile's JSON.
const SETTINGS_LOCAL_PATH = path.join(__dirname, '../../settings_local.json');
const NON_PERSISTED_KEYS = new Set(['profile', 'profile_path', 'profiles', 'task']);

const PROFILES_DIR = path.join(__dirname, '../../profiles');
const AGENT_ACK_TIMEOUT_MS = 1000;
const accessToken = randomBytes(32).toString('hex');

export const getAccessToken = () => accessToken;

function validAccessToken(token, expectedToken = accessToken) {
    if (typeof token !== 'string' || typeof expectedToken !== 'string') return false;
    const supplied = Buffer.from(token);
    const expected = Buffer.from(expectedToken);
    return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}

function emitWithAck(socket, event, timeoutMs, signal) {
    return new Promise((resolve, reject) => {
        if (!socket || socket.connected === false) {
            reject(new Error('Agent is not connected.'));
            return;
        }
        if (signal?.aborted) {
            reject(new Error('Agent request was cancelled.'));
            return;
        }

        let settled = false;
        const cleanup = () => {
            socket.off?.('disconnect', onDisconnect);
            signal?.removeEventListener('abort', onAbort);
        };
        const finish = (error, value) => {
            if (settled) return;
            settled = true;
            cleanup();
            if (error) reject(error);
            else resolve(value);
        };
        const onDisconnect = () => finish(new Error('Agent disconnected before acknowledging.'));
        const onAbort = () => finish(new Error('Agent request was cancelled.'));

        socket.on?.('disconnect', onDisconnect);
        signal?.addEventListener('abort', onAbort, { once: true });
        try {
            // Use Socket.IO's built-in timeout so the internal ack entry in
            // socket.acks is removed on timeout. A manual setTimeout around a
            // plain socket.emit(event, cb) would reject our Promise but leave
            // the ack callback retained in socket.acks until the client finally
            // acks or disconnects (unbounded retention under a hung agent).
            socket.timeout(timeoutMs).emit(event, (err, response) => {
                if (err) {
                    if (err && typeof err.message === 'string' && /timed out/i.test(err.message)) {
                        finish(new Error('Agent acknowledgement timed out.'));
                    } else {
                        finish(err);
                    }
                    return;
                }
                finish(null, response);
            });
        } catch (error) {
            finish(error);
        }
    });
}

function sanitizeProfileName(name) {
    const cleaned = String(name || '').trim().replace(/[^a-zA-Z0-9_-]/g, '');
    if (!cleaned) throw new Error('Profile name must contain only letters, numbers, underscores, and hyphens.');
    if (cleaned.length > 64) throw new Error('Profile name too long (max 64 chars).');
    return cleaned;
}

function profileFilePath(name) {
    return path.join(PROFILES_DIR, `${sanitizeProfileName(name)}.json`);
}

function sameProfilePath(first, second) {
    if (typeof first !== 'string' || !first || typeof second !== 'string' || !second) return false;
    const normalize = p => {
        const absolute = path.resolve(__dirname, '../..', p);
        return process.platform === 'win32' ? absolute.toLowerCase() : absolute;
    };
    return normalize(first) === normalize(second);
}

function readKeyFile(filePath) {
    let data;
    try { data = JSON.parse(readFileSync(filePath, 'utf8')); }
    catch (error) {
        if (error.code === 'ENOENT') return {};
        throw error;
    }
    if (!data || typeof data !== 'object' || Array.isArray(data) || Object.values(data).some(value => typeof value !== 'string')) {
        throw new Error('Invalid key file format.');
    }
    return data;
}

function writeKeyFile(filePath, keys) {
    const temporary = `${filePath}.${randomBytes(12).toString('hex')}.tmp`;
    try {
        writeFileSync(temporary, JSON.stringify(keys, null, 4), { encoding: 'utf8', mode: 0o600, flag: 'wx' });
        renameSync(temporary, filePath);
    } finally {
        if (existsSync(temporary)) unlinkSync(temporary);
    }
}

function readStartupProfiles() {
    return Array.isArray(settings.profiles) ? [...settings.profiles] : [];
}

function persistStartupProfiles(profiles) {
    let existing = {};
    if (existsSync(SETTINGS_LOCAL_PATH)) {
        try { existing = JSON.parse(readFileSync(SETTINGS_LOCAL_PATH, 'utf8')) || {}; }
        catch { existing = {}; }
    }
    existing.profiles = profiles;
    writeFileSync(SETTINGS_LOCAL_PATH, JSON.stringify(existing, null, 4), 'utf8');
    settings.profiles = profiles;
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
    agent_connections[settings.profile.name]?.socket?.disconnect(true);
    let agentConnection = new AgentConnection(settings);
    agent_connections[settings.profile.name] = agentConnection;
}

// A launch credential is consumed by the handshake and rotated on every spawn.
export function issueAgentToken(agentName) {
    const agent = agent_connections[agentName];
    if (!agent) throw new Error('Agent is not registered.');
    agent.socket?.disconnect(true);
    agent.processToken = randomBytes(32).toString('hex');
    return agent.processToken;
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
    io.use((socket, next) => {
        const { token, agentName } = socket.handshake.auth || {};
        if (validAccessToken(token)) {
            socket.data.role = 'owner';
            return next();
        }
        const agent = typeof agentName === 'string' && agent_connections[agentName];
        if (agent && validAccessToken(token, agent.processToken)) {
            agent.processToken = null;
            socket.data.role = 'agent';
            socket.data.agentName = agentName;
            socket.data.agent = agent;
            return next();
        }
        next(new Error('Unauthorized'));
    });
    app.use('/api', (req, res, next) => {
        const authorization = req.headers.authorization;
        if (!authorization?.startsWith('Bearer ') || !validAccessToken(authorization.slice(7))) {
            return res.status(401).json({ error: 'Unauthorized' });
        }
        res.set('Cache-Control', 'no-store');
        next();
    });

    // Serve static files
    const __dirname = path.dirname(fileURLToPath(import.meta.url));
    app.use(express.static(path.join(__dirname, 'public')));

    // Socket.io connection handling
    io.on('connection', (socket) => {
        let curAgentName = socket.data.agentName || null;
        if (socket.data.role === 'owner') socket.join('dashboard');
        else socket.data.agent.socket = socket;
        // Contain request-handler failures here; truly uncaught parent errors
        // must not leave the process serving from potentially corrupt state.
        const on = (event, handler) => socket.on(event, (...args) => {
            if (event !== 'disconnect' && socket.data.role === 'agent') {
                const agent = agent_connections[curAgentName];
                const current = agent === socket.data.agent && agent?.socket === socket;
                const ownEvent = ['get-settings', 'connect-agent-process', 'login-agent', 'bot-output'].includes(event) && args[0] === curAgentName;
                // Legacy benchmark tasks intentionally stop the whole run on completion.
                const taskShutdown = event === 'shutdown' && typeof agent?.settings.task?.task_id === 'string' && agent.settings.task.task_id.length > 0;
                if (!current || !(ownEvent || event === 'chat-message' || taskShutdown)) {
                    const callback = args[args.length - 1];
                    if (typeof callback === 'function') callback({ success: false, error: 'Forbidden' });
                    return;
                }
            }
            const failed = error => {
                console.error(`[mindserver] ${event} handler failed:`, error);
                const callback = args[args.length - 1];
                if (typeof callback === 'function') callback({ success: false, error: 'Request failed.' });
            };
            try { Promise.resolve(handler(...args)).catch(failed); }
            catch (error) { failed(error); }
        });
        console.log('Client connected');

        agentsStatusUpdate(socket);

        on('create-agent', async (settings, callback) => {
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

        on('get-settings', (agentName, callback) => {
            if (agent_connections[agentName]) {
                callback({ settings: agent_connections[agentName].settings });
            } else {
                callback({ error: `Agent '${agentName}' not found.` });
            }
        });

        on('get-agent-memory', (agentName, callback) => {
            const agent = agent_connections[agentName];
            if (agent && agent.socket) {
                const agentSocket = agent.socket;
                return emitWithAck(agentSocket, 'get-agent-memory', AGENT_ACK_TIMEOUT_MS)
                    .then((memory) => {
                        if (agent_connections[agentName] !== agent || agent.socket !== agentSocket || agentSocket.connected === false) {
                            callback({ success: false, error: `Agent '${agentName}' disconnected before responding.` });
                            return;
                        }
                        callback({ success: true, memory: memory ?? '' });
                    }, (error) => callback({ success: false, error: error.message }));
            } else {
                callback({ success: false, error: `Agent '${agentName}' not found or not connected.` });
            }
        });

        on('connect-agent-process', (agentName) => {
            if (agent_connections[agentName]) {
                agent_connections[agentName].socket = socket;
                curAgentName = agentName;
                agentsStatusUpdate();
            }
        });

        on('login-agent', (agentName) => {
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

        on('disconnect', () => {
            const registered = agent_connections[curAgentName];
            if (registered && registered.socket === socket) {
                console.log(`Agent ${curAgentName} disconnected`);
                registered.in_game = false;
                registered.socket = null;
                agentsStatusUpdate();
            }
            if (agent_listeners.includes(socket)) {
                removeListener(socket);
            }
        });

        on('chat-message', (agentName, json) => {
            if (!agent_connections[agentName]) {
                console.warn(`Agent ${agentName} tried to send a message but is not logged in`);
                return;
            }
            console.log(`${curAgentName} sending message to ${agentName}: ${json.message}`);
            agent_connections[agentName].socket.emit('chat-message', curAgentName, json);
        });

        on('set-agent-settings', (agentName, settings, callback) => {
            try {
                const agent = agent_connections[agentName];
                if (!agent) {
                    console.warn(`set-agent-settings: no agent named '${agentName}'`);
                    if (callback) callback({ success: false, error: `Agent '${agentName}' not found.` });
                    return;
                }
                if (settings.profile) {
                    try {
                        validateProfile(settings.profile);
                        if (settings.profile.name !== agentName) throw new Error('Cannot rename an existing agent through its settings.');
                    }
                    catch (err) {
                        if (callback) callback({ success: false, error: err.message });
                        return;
                    }
                }
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
                agent.setSettings(settings);
                persistGlobalSettings(settings);
                mindcraft.startAgent(agentName);
                if (callback) callback({ success: true });
            } catch (err) {
                console.error('set-agent-settings handler failed:', err);
                if (callback) callback({ success: false, error: err.message });
            }
        });

        on('restart-agent', (agentName) => {
            try {
                console.log(`Restarting agent: ${agentName}`);
                mindcraft.startAgent(agentName);
            } catch (err) {
                console.error('restart-agent handler failed:', err);
            }
        });

        on('stop-agent', (agentName) => {
            mindcraft.stopAgent(agentName);
        });

        on('start-agent', (agentName) => {
            mindcraft.startAgent(agentName);
        });

        on('destroy-agent', (agentName) => {
            if (agent_connections[agentName]) {
                mindcraft.destroyAgent(agentName);
                delete agent_connections[agentName];
            }
            agentsStatusUpdate();
        });

        on('stop-all-agents', () => {
            console.log('Killing all agents');
            for (let agentName in agent_connections) {
                mindcraft.stopAgent(agentName);
            }
        });

        on('shutdown', () => {
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

		on('send-message', (agentName, data) => {
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

        on('clear-agent-memory', (agentName, preserveImportant = false) => {
            const agent = agent_connections[agentName];
            if (agent?.socket) {
                agent.socket.emit('clear-agent-memory', preserveImportant);
            }
        });

        on('compact-agent-memory', (agentName, reason = 'manual') => {
            const agent = agent_connections[agentName];
            if (agent?.socket) {
                agent.socket.emit('compact-agent-memory', reason);
            }
        });

        on('set-important-memory', (agentName, memoryText) => {
            const agent = agent_connections[agentName];
            if (agent?.socket) {
                agent.socket.emit('set-important-memory', memoryText);
            }
        });

        on('bot-output', (agentName, message) => {
            io.to('dashboard').emit('bot-output', agentName, message);
        });

        on('listen-to-agents', () => {
            addListener(socket);
        });
    });

    app.get('/api/keys', async (req, res) => {
        try {
            const keysPath = path.join(__dirname, '../../keys.json');
            const examplePath = path.join(__dirname, '../../keys.example.json');
            const keys = readKeyFile(keysPath);
            const exampleKeys = readKeyFile(examplePath);
            const result = Object.create(null);
            for (const k of Object.keys(exampleKeys)) {
                const set = Object.hasOwn(keys, k) && keys[k].length > 0;
                result[k] = { set, mask: set ? '***' : null };
            }
            res.json(result);
        } catch (err) {
            console.error('Failed to load keys:', err.code || err.name);
            res.status(500).json({ error: 'Failed to load keys' });
        }
    });

    app.post('/api/keys', express.json(), async (req, res) => {
        try {
            const keysPath = path.join(__dirname, '../../keys.json');
            const allowed = readKeyFile(path.join(__dirname, '../../keys.example.json'));
            const updates = req.body;
            if (!updates || typeof updates !== 'object' || Array.isArray(updates) ||
                Object.entries(updates).some(([key, value]) => !Object.hasOwn(allowed, key) || typeof value !== 'string')) {
                return res.status(400).json({ error: 'Expected known key names with string values.' });
            }
            const keys = readKeyFile(keysPath);
            for (const [k, v] of Object.entries(updates)) {
                if (typeof v === 'string' && v.length > 0) {
                    keys[k] = v;
                } else {
                    delete keys[k];
                }
            }
            writeKeyFile(keysPath, keys);
            res.json({ success: true });
        } catch (err) {
            console.error('Failed to save keys:', err.code || err.name);
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
            if (profile.name !== req.params.name) {
                return res.status(400).json({ error: 'Profile name must match the requested profile. Use Clone to create a different profile.' });
            }
            const filePath = path.join(PROFILES_DIR, `${safeName}.json`);
            if (!existsSync(filePath)) {
                return res.status(404).json({ error: `Profile '${safeName}' not found.` });
            }
            const existingProfile = JSON.parse(readFileSync(filePath, 'utf8'));
            if (existingProfile.name !== profile.name) {
                return res.status(409).json({ error: 'Stored profile identity does not match the requested profile.' });
            }
            writeFileSync(filePath, JSON.stringify(profile, null, 4), 'utf8');
            console.log(`Updated profile ${safeName} at ${filePath}`);
            const agentName = profile.name;
            if (agent_connections[agentName] && sameProfilePath(agent_connections[agentName].profile_path, filePath)) {
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
                if (sameProfilePath(agent_connections[agentName].profile_path, filePath)) {
                    return res.status(409).json({ error: `Profile is in use by agent '${agentName}'. Remove the agent first.` });
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
            if (!Array.isArray(req.body)) {
                return res.status(400).json({ error: 'Expected a JSON array of profile paths.' });
            }
            const profiles = [...new Set(req.body.map(p => readStartupProfile(p).path))];
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
let activeListenerPoll = null;
function addListener(listener_socket) {
    if (agent_listeners.includes(listener_socket)) return;
    agent_listeners.push(listener_socket);
    if (agent_listeners.length === 1) {
        listenerInterval = setInterval(async () => {
            if (activeListenerPoll) return;
            const poll = { controller: new AbortController() };
            activeListenerPoll = poll;
            const states = {};
            try {
                const requests = Object.entries(agent_connections)
                    .filter(([, agent]) => agent.in_game)
                    .map(async ([agentName, agent]) => {
                        const agentSocket = agent.socket;
                        try {
                            const state = await emitWithAck(agentSocket, 'get-full-state', AGENT_ACK_TIMEOUT_MS, poll.controller.signal);
                            if (poll.controller.signal.aborted || agent_connections[agentName] !== agent || agent.socket !== agentSocket || agentSocket?.connected === false) return;
                            states[agentName] = state;
                        } catch (error) {
                            if (poll.controller.signal.aborted || agent_connections[agentName] !== agent || agent.socket !== agentSocket) return;
                            states[agentName] = { error: error.message };
                        }
                    });
                await Promise.all(requests);
                if (!poll.controller.signal.aborted && activeListenerPoll === poll) {
                    for (let listener of agent_listeners) {
                        listener.emit('state-update', states);
                    }
                }
            } finally {
                if (activeListenerPoll === poll) activeListenerPoll = null;
            }
        }, 1000);
    }
}

function removeListener(listener_socket) {
    let removed = false;
    for (let i = agent_listeners.length - 1; i >= 0; i--) {
        if (agent_listeners[i] === listener_socket) {
            agent_listeners.splice(i, 1);
            removed = true;
        }
    }
    if (!removed) return;
    if (agent_listeners.length === 0) {
        clearInterval(listenerInterval);
        listenerInterval = null;
        activeListenerPoll?.controller.abort();
        activeListenerPoll = null;
    }
}

// Optional: export these if you need access to them from other files
export const getIO = () => io;
export const getServer = () => server;
export const numStateListeners = () => agent_listeners.length;
