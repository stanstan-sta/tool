import { spawn } from 'child_process';
import { logoutAgent, issueAgentToken } from '../mindcraft/mindserver.js';

export class AgentProcess {
    constructor(name, port, bridge_mode = false) {
        this.name = name;
        this.port = port;
        this.bridge_mode = bridge_mode;
        this._awaitingManualRestart = false;
        this._stopRequested = false;
    }

    start(load_memory=false, init_message=null, count_id=0) {
        this.count_id = count_id;
        this.running = true;
        this._stopRequested = false;

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
            env: { ...process.env, MINDSERVER_TOKEN: issueAgentToken(this.name) },
            stdio: 'inherit',
            stderr: 'inherit',
        });

        let last_restart = Date.now();
        // Identity guard: only the current child may drive restart decisions.
        // A superseded child's late exit must never start another replacement
        // (previously it could cascade into duplicate children).
        agentProcess.on('exit', (code, signal) => {
            if (this.process !== agentProcess) {
                console.log(`Ignoring exit from superseded agent child for ${this.name} (code ${code}, signal ${signal}).`);
                return;
            }
            console.log(`Agent process exited with code ${code} and signal ${signal}`);
            this.running = false;
            logoutAgent(this.name);

            // Skip ALL auto-restart logic if forceRestart() is driving the
            // lifecycle — it attaches its own .once('exit') handler.
            if (this._awaitingManualRestart || this._stopRequested) {
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
            // Identity guard: a late error from a superseded child must not
            // touch replacement-agent state (it never mutated state, but it
            // must not even log as if it belonged to the current child).
            if (this.process !== agentProcess) {
                console.log(`Ignoring error from superseded agent child for ${this.name}.`);
                return;
            }
            console.error('Agent process error:', err);
        });

        this.process = agentProcess;
    }

    stop() {
        this._stopRequested = true;
        if (!this.running || !this.process) return;
        this.process.kill('SIGINT');
    }

    forceRestart() {
        const current = this.process;
        // process.killed only means a signal was sent, not that the child
        // exited. Liveness is decided by the 'exit' event (resolved flag),
        // never by re-reading .killed (which is already true after SIGINT and
        // previously made the SIGKILL escalation below dead code).
        if (this.running && current && current.exitCode === null && current.signalCode === null) {
            // Restart races: a second forceRestart while one is already
            // driving the lifecycle must not attach duplicate handlers and
            // start a duplicate replacement.
            if (this._awaitingManualRestart) {
                console.warn(`Agent ${this.name} restart already in progress; ignoring duplicate forceRestart.`);
                return;
            }
            console.log(`Agent process for ${this.name} is still running. Attempting to force restart.`);

            // Flag so the auto-exit handler defers to the .once('exit') below.
            this._stopRequested = false;
            this._awaitingManualRestart = true;

            let resolved = false;
            let killGraceTimeout = null;
            const finishWithRestart = (message) => {
                if (resolved) return;
                // Only the child we were asked to restart may trigger its
                // replacement; a superseded exit must never start one.
                if (this.process !== current) return;
                resolved = true;
                clearTimeout(restartTimeout);
                if (killGraceTimeout) clearTimeout(killGraceTimeout);
                this._awaitingManualRestart = false;
                if (this._stopRequested) return;
                console.log(message);
                this.start(true, 'Agent process restarted.', this.count_id);
            };
            const finishBlocked = () => {
                if (resolved) return;
                resolved = true;
                this._awaitingManualRestart = false;
                // A replacement must never run alongside its predecessor, so
                // absence of confirmed exit blocks the replacement. The old
                // child stays registered, so its eventual exit still flows
                // through the normal auto-restart policy instead of being
                // orphaned or duplicated.
                console.error(`Agent ${this.name} (pid ${current.pid}) refused to exit after SIGINT+SIGKILL. Replacement blocked: manual restart required once pid ${current.pid} exits.`);
            };
            const restartTimeout = setTimeout(() => {
                if (resolved) return;
                // The child may have exited in the gap between the liveness
                // check above and the .once('exit') attach below; its exit
                // event is then already gone, so confirm exit here instead of
                // escalating against a dead child.
                if (current.exitCode !== null || current.signalCode !== null) {
                    finishWithRestart(`Agent ${this.name} already exited. Now restarting.`);
                    return;
                }
                // SIGINT is advisory on Windows and often ignored when the
                // child is mid-await on an HTTP call. Escalate to SIGKILL
                // so the restart actually happens.
                console.warn(`Agent ${this.name} did not stop after SIGINT. Escalating to SIGKILL.`);
                try {
                    if (current.exitCode === null && current.signalCode === null) {
                        current.kill('SIGKILL');
                    }
                } catch (err) {
                    console.error(`Failed to SIGKILL ${this.name}:`, err);
                }
                // If the old child still has not exited some time after
                // SIGKILL (e.g. unkillable), fail loudly and block the
                // replacement instead of running two children at once.
                killGraceTimeout = setTimeout(() => {
                    if (resolved) return;
                    if (current.exitCode !== null || current.signalCode !== null) {
                        finishWithRestart(`Agent ${this.name} exited late. Now restarting.`);
                        return;
                    }
                    finishBlocked();
                }, 3000);
            }, 5000);

            current.once('exit', () => {
                 finishWithRestart(`Stopped hanging agent ${this.name}. Now restarting.`);
            });
            current.kill('SIGINT');
        } else {
             this.start(true, 'Agent process restarted.', this.count_id);
        }
    }
}
