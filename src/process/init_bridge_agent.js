import { BridgeAgent } from '../bridge/bridge_agent.js';
import { serverProxy } from '../agent/mindserver_proxy.js';
import yargs from 'yargs';

const args = process.argv.slice(2);
if (args.length < 1) {
    console.log('Usage: node init_bridge_agent.js -n <agent_name> -p <port> [-l] [-m <init_message>] [-c <count_id>]');
    process.exit(1);
}

const argv = yargs(args)
    .option('name', { alias: 'n', type: 'string' })
    .option('load_memory', { alias: 'l', type: 'boolean' })
    .option('init_message', { alias: 'm', type: 'string' })
    .option('count_id', { alias: 'c', type: 'number', default: 0 })
    .option('port', { alias: 'p', type: 'number' })
    .argv;

(async () => {
    // Graceful shutdown. Without this, Node on Windows may ignore SIGINT
    // while we're mid-await on an HTTP call, causing the parent's restart
    // flow to time out. With it, SIGINT reliably exits even mid-request.
    const shutdown = (code = 0) => {
        try {
            if (typeof global !== 'undefined' && global.gc) global.gc();
        } catch {}
        process.exit(code);
    };
    process.on('SIGINT',  () => shutdown(0));
    process.on('SIGTERM', () => shutdown(0));
    process.on('SIGHUP',  () => shutdown(0));

    try {
        console.log('Bridge agent: Connecting to MindServer...');
        await serverProxy.connect(argv.name, argv.port);
        console.log('Bridge agent: Starting...');
        const agent = new BridgeAgent();
        serverProxy.setAgent(agent);
        await agent.start(argv.load_memory, argv.init_message);
    } catch (error) {
        console.error('Failed to start bridge agent:');
        console.error(error.message);
        console.error(error.stack);
        process.exit(1);
    }
})();
