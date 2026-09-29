import { spawn } from 'node:child_process';
import { accessSync, constants } from 'node:fs';

const executable = process.env.LLAMA_SERVER_PATH;
const model = process.env.DECIDER_MODEL_PATH;

if (!executable || !model) {
    console.error('Set LLAMA_SERVER_PATH and DECIDER_MODEL_PATH before running system-one:serve.');
    process.exit(2);
}

try {
    accessSync(executable, constants.X_OK);
    accessSync(model, constants.R_OK);
} catch (err) {
    console.error(`System One path is not accessible: ${err.message}`);
    process.exit(2);
}

const args = [
    '-m', model,
    '--port', process.env.SYSTEM_ONE_PORT || '8781',
    '--host', process.env.SYSTEM_ONE_HOST || '127.0.0.1',
    '-ngl', process.env.SYSTEM_ONE_NGL || '99',
    '-c', process.env.SYSTEM_ONE_CONTEXT || '4096',
    '-np', process.env.SYSTEM_ONE_PARALLEL || '1',
];

const child = spawn(executable, args, { stdio: 'inherit', shell: false });

for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => {
        if (!child.killed) child.kill(signal);
    });
}

child.on('error', err => {
    console.error(`Failed to start llama-server: ${err.message}`);
    process.exitCode = 1;
});
child.on('exit', (code, signal) => {
    if (signal) {
        console.error(`llama-server exited from signal ${signal}`);
        process.exitCode = 1;
    } else {
        process.exitCode = code ?? 1;
    }
});
