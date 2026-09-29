import * as Mindcraft from './src/mindcraft/mindcraft.js';
import settings from './settings.js';
import yargs from 'yargs';
import { hideBin } from 'yargs/helpers';
import { readFileSync, existsSync } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { readStartupProfile, validateProfile } from './src/mindcraft/startup_profiles.js';

// Persisted overrides written by the web UI (top-level settings only; profile
// fields live in each profile JSON). Merged over the settings.js defaults so UI
// changes survive a full app restart without editing settings.js. Env vars and
// CLI args below still take precedence over these.
const SETTINGS_LOCAL_PATH = path.join(path.dirname(fileURLToPath(import.meta.url)), 'settings_local.json');
if (existsSync(SETTINGS_LOCAL_PATH)) {
    try {
        const local = JSON.parse(readFileSync(SETTINGS_LOCAL_PATH, 'utf8'));
        if (local && typeof local === 'object' && !Array.isArray(local)) {
            if (Object.hasOwn(local, 'profiles')) {
                const profiles = [];
                for (const profilePath of Array.isArray(local.profiles) ? local.profiles : []) {
                    try { profiles.push(readStartupProfile(profilePath).path); }
                    catch (err) { console.error(`Ignoring invalid saved startup profile '${profilePath}':`, err.message); }
                }
                local.profiles = [...new Set(profiles)];
            }
            Object.assign(settings, local);
            console.log(`Loaded ${Object.keys(local).length} persisted setting(s) from settings_local.json`);
        }
    } catch (err) {
        console.error('Failed to read settings_local.json (ignoring):', err.message);
    }
}

// Socket request failures are contained by MindServer's listener boundary.
// Errors escaping that boundary are fatal: stop children and exit nonzero.
let shuttingDown = false;
function fatalError(kind, error) {
    if (shuttingDown) return;
    shuttingDown = true;
    console.error(`[mindserver] ${kind}:`, error);
    try { Mindcraft.shutdown(1); }
    catch (shutdownError) {
        console.error('[mindserver] shutdown failed:', shutdownError);
        process.exit(1);
    }
}
process.on('uncaughtException', err => fatalError('uncaughtException', err));
process.on('unhandledRejection', reason => fatalError('unhandledRejection', reason));

function parseArguments() {
    return yargs(hideBin(process.argv))
        .option('profiles', {
            type: 'array',
            describe: 'List of agent profile paths',
        })
        .option('task_path', {
            type: 'string',
            describe: 'Path to task file to execute'
        })
        .option('task_id', {
            type: 'string',
            describe: 'Task ID to execute'
        })
        .help()
        .alias('help', 'h')
        .parse();
}
const args = parseArguments();
if (args.profiles) {
    settings.profiles = args.profiles;
}
if (args.task_path) {
    let tasks = JSON.parse(readFileSync(args.task_path, 'utf8'));
    if (args.task_id) {
        settings.task = tasks[args.task_id];
        settings.task.task_id = args.task_id;
    }
    else {
        throw new Error('task_id is required when task_path is provided');
    }
}

// these environment variables override certain settings
if (process.env.MINECRAFT_PORT) {
    settings.port = process.env.MINECRAFT_PORT;
}
if (process.env.MINDSERVER_PORT) {
    settings.mindserver_port = process.env.MINDSERVER_PORT;
}
if (process.env.PROFILES && JSON.parse(process.env.PROFILES).length > 0) {
    settings.profiles = JSON.parse(process.env.PROFILES);
}
if (process.env.INSECURE_CODING) {
    settings.allow_insecure_coding = true;
}
if (process.env.BLOCKED_ACTIONS) {
    settings.blocked_actions = JSON.parse(process.env.BLOCKED_ACTIONS);
}
if (process.env.MAX_MESSAGES) {
    settings.max_messages = process.env.MAX_MESSAGES;
}
if (process.env.NUM_EXAMPLES) {
    settings.num_examples = process.env.NUM_EXAMPLES;
}
if (process.env.LOG_ALL) {
    settings.log_all_prompts = process.env.LOG_ALL;
}
if (process.env.SETTINGS_JSON) {
    try {
        Object.assign(settings, JSON.parse(process.env.SETTINGS_JSON));
    } catch (err) {
        console.error("Failed to parse environment variable for SETTINGS_JSON:", err);
    }
}


Mindcraft.init(false, settings.mindserver_port, settings.auto_open_ui);

for (const profile of Array.isArray(settings.profiles) ? settings.profiles : []) {
    try {
        const profile_json = JSON.parse(readFileSync(profile, 'utf8'));
        validateProfile(profile_json);
        settings.profile = profile_json;
        settings.profile_path = profile;
        Mindcraft.createAgent(settings).catch(err => console.error(`Failed to start profile '${profile}':`, err.message));
    } catch (err) {
        console.error(`Skipping invalid startup profile '${profile}':`, err.message);
    }
}
