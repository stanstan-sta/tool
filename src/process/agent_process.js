import { spawn } from 'child_process';
import { logoutAgent } from '../mindcraft/mindserver.js';

export class AgentProcess {
    constructor(name, port, bridge_mode = false) {
        this.name = name;
        this.port = port;
        this.bridge_mode = bridge_mode;
        this._awaitingManualRestart = false;
    }

    start(load_memory=false, init_message=null, count_id=0) {
        this.count_id = count_id;
        this.running = true;

        const initScript = this.bridge_mode
            ? 'src/process/init_bridge_agent.js'
            : 'src/process/init_agent.js';

        let args = [initScript, this.name];
        args.push('-n', this.name);
        args.push('-c', count_id);
        if (load_memory)
            args.push('-l', load_memory);
        if (init_message)
            args.push('-m', init_message);
        args.push('-p', this.port);

        const agentProcess = spawn('node', args, {
            stdio: 'inherit',
            stderr: 'inherit',
        });
        
        let last_restart = Date.now();
        agentProcess.on('exit', (code, signal) => {
            console.log(`Agent process exited with code ${code} and signal ${signal}`);
            this.running = false;
            logoutAgent(this.name);

            // Skip ALL auto-restart logic if forceRestart() is driving the
            // lifecycle — it attaches its own .once('exit') handler.
            if (this._awaitingManualRestart) {
                this._awaitingManualRestart = false;
                return;
            }

            // An exit code > 1 used to kill the parent MindServer ("if my
            // child dies messily, so do I"). That turns a child-side bug
            // into total system failure. Log loudly and move on.
            if (code > 1) {
                console.error(`Agent ${this.name} exited with code ${code}. Not restarting (manual restart required).`);
                return;
            }

            if (code !== 0 && signal !== 'SIGINT') {
                // agent must run for at least 10 seconds before restarting
                if (Date.now() - last_restart < 10000) {
                    console.error(`Agent process exited too quickly and will not be restarted.`);
                    return;
                }
                console.log('Restarting agent...');
                this.start(true, 'Agent process restarted.', count_id);
                last_restart = Date.now();
            }
        });
    
        agentProcess.on('error', (err) => {
            console.error('Agent process error:', err);
        });

        this.process = agentProcess;
    }

    stop() {
        if (!this.running) return;
        this.process.kill('SIGINT');
    }

    forceRestart() {
        if (this.running && this.process && !this.process.killed) {
            console.log(`Agent process for ${this.name} is still running. Attempting to force restart.`);

            // Flag so the auto-exit handler defers to the .once('exit') below.
            this._awaitingManualRestart = true;

            let resolved = false;
            const restartTimeout = setTimeout(() => {
                if (resolved) return;
                // SIGINT is advisory on Windows and often ignored when the
                // child is mid-await on an HTTP call. Escalate to SIGKILL
                // so the restart actually happens.
                console.warn(`Agent ${this.name} did not stop after SIGINT. Escalating to SIGKILL.`);
                try {
                    if (this.process && !this.process.killed) {
                        this.process.kill('SIGKILL');
                    }
                } catch (err) {
                    console.error(`Failed to SIGKILL ${this.name}:`, err);
                }
                // If SIGKILL also doesn't take for some reason (e.g. process
                // already gone), start the replacement after a short delay so
                // we don't deadlock the caller waiting for .once('exit').
                setTimeout(() => {
                    if (resolved) return;
                    resolved = true;
                    console.warn(`Agent ${this.name} still not exited 3s after SIGKILL. Starting replacement anyway.`);
                    this._awaitingManualRestart = false;
                    this.start(true, 'Agent process restarted.', this.count_id);
                }, 3000);
            }, 5000);

            this.process.once('exit', () => {
                 if (resolved) return;
                 resolved = true;
                 clearTimeout(restartTimeout);
                 console.log(`Stopped hanging agent ${this.name}. Now restarting.`);
                 this.start(true, 'Agent process restarted.', this.count_id);
            });
            this.stop(); // sends SIGINT
        } else {
             this.start(true, 'Agent process restarted.', this.count_id);
        }
    }
}