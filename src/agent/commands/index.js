import { getBlockId, getItemId } from "../../utils/mcdata.js";
import { actionsList } from './actions.js';
import { queryList } from './queries.js';
import { baritoneList } from './baritone.js';
import settings from '../settings.js';

let suppressNoDomainWarning = true;

const commandList = queryList.concat(actionsList).concat(settings.use_baritone ? baritoneList : []);
const commandMap = {};
for (let command of commandList) {
    commandMap[command.name] = command;
}

export function getCommand(name) {
    return commandMap[name];
}

export function blacklistCommands(commands) {
    const unblockable = ['!stop', '!stats', '!inventory', '!goal'];
    for (let command_name of commands) {
        if (unblockable.includes(command_name)){
            console.warn(`Command ${command_name} is unblockable`);
            continue;
        }
        delete commandMap[command_name];
        delete commandList.find(command => command.name === command_name);
    }
}

const commandRegex = /!(\w+)(?:\(((?:-?\d+(?:\.\d+)?|true|false|"[^"]*")(?:\s*,\s*(?:-?\d+(?:\.\d+)?|true|false|"[^"]*"))*)\))?/
const argRegex = /-?\d+(?:\.\d+)?|true|false|"[^"]*"/g;

export function containsCommand(message) {
    const commandMatch = message.match(commandRegex);
    if (commandMatch)
        return "!" + commandMatch[1];
    return null;
}

export function commandExists(commandName) {
    if (!commandName.startsWith("!"))
        commandName = "!" + commandName;
    return commandMap[commandName] !== undefined;
}

/**
 * Converts a string into a boolean.
 * @param {string} input
 * @returns {boolean | null} the boolean or `null` if it could not be parsed.
 * */
function parseBoolean(input) {
    switch(input.toLowerCase()) {
        case 'false': //These are interpreted as flase;
        case 'f':
        case '0':
        case 'off':
            return false;
        case 'true': //These are interpreted as true;
        case 't':
        case '1':
        case 'on':
            return true;
        default:
            return null;
    }
}

/**
 * @param {number} value - the value to check
 * @param {number} lowerBound
 * @param {number} upperBound
 * @param {string} endpointType - The type of the endpoints represented as a two character string. `'[)'` `'()'` 
 */
function checkInInterval(number, lowerBound, upperBound, endpointType) {
    switch (endpointType) {
        case '[)':
            return lowerBound <= number && number < upperBound;
        case '()':
            return lowerBound < number && number < upperBound;
        case '(]':
            return lowerBound < number && number <= upperBound;
        case '[]':
            return lowerBound <= number && number <= upperBound;
        default:
            throw new Error('Unknown endpoint type:', endpointType)
    }
}



// todo: handle arrays?
/**
 * Returns an object containing the command, the command name, and the comand parameters.
 * If parsing unsuccessful, returns an error message as a string.
 * @param {string} message - A message from a player or language model containing a command.
 * @returns {string | Object}
 */
export function parseCommandMessage(message) {
    const commandMatch = message.match(commandRegex);
    if (!commandMatch) return `Command is incorrectly formatted`;

    const commandName = "!"+commandMatch[1];

    let args;
    if (commandMatch[2]) args = commandMatch[2].match(argRegex);
    else args = [];

    const command = getCommand(commandName);
    if(!command) return `${commandName} is not a command.`

    const params = commandParams(command);
    const paramNames = commandParamNames(command);
    
    if (args.length !== params.length)
        return `Command ${command.name} was given ${args.length} args, but requires ${params.length} args.`;

    
    for (let i = 0; i < args.length; i++) {
        const param = params[i];
        //Remove any extra characters
        let arg = args[i].trim();
        if ((arg.startsWith('"') && arg.endsWith('"')) || (arg.startsWith("'") && arg.endsWith("'"))) {
            arg = arg.substring(1, arg.length-1);
        }
        
        //Convert to the correct type
        switch(param.type) {
            case 'int':
                arg = Number.parseInt(arg); break;
            case 'float':
                arg = Number.parseFloat(arg); break;
            case 'boolean':
                arg = parseBoolean(arg); break;
            case 'BlockName':
            case 'BlockOrItemName':
            case 'ItemName':
                if (arg.endsWith('plank') || arg.endsWith('seed'))
                    arg += 's'; // add 's' to for common mistakes like "oak_plank" or "wheat_seed"
            case 'string':
                break;
            default:
                throw new Error(`Command '${commandName}' parameter '${paramNames[i]}' has an unknown type: ${param.type}`);
        }
        if(arg === null || Number.isNaN(arg))
            return `Error: Param '${paramNames[i]}' must be of type ${param.type}.`

        if(typeof arg === 'number') { //Check the domain of numbers
            const domain = param.domain;
            if(domain) {
                /**
                 * Javascript has a built in object for sets but not intervals.
                 * Currently the interval (lowerbound,upperbound] is represented as an Array: `[lowerbound, upperbound, '(]']`
                 */
                if (!domain[2]) domain[2] = '[)'; //By default, lower bound is included. Upper is not.

                if(!checkInInterval(arg, ...domain)) {
                    return `Error: Param '${paramNames[i]}' must be an element of ${domain[2][0]}${domain[0]}, ${domain[1]}${domain[2][1]}.`;
                    //Alternatively arg could be set to the nearest value in the domain.
                }
            } else if (!suppressNoDomainWarning) {
                console.warn(`Command '${commandName}' parameter '${paramNames[i]}' has no domain set. Expect any value [-Infinity, Infinity].`)
                suppressNoDomainWarning = true; //Don't spam console. Only give the warning once.
            }
        } else if(param.type === 'BlockName') { //Check that there is a block with this name
            if(getBlockId(arg) == null) return  `Invalid block type: ${arg}.`
        } else if(param.type === 'ItemName') { //Check that there is an item with this name
            if(getItemId(arg) == null) return `Invalid item type: ${arg}.`
        } else if(param.type === 'BlockOrItemName') {
            if(getBlockId(arg) == null && getItemId(arg) == null) return  `Invalid block or item type: ${arg}.`
        }
        args[i] = arg;
    }
    
    return { commandName, args };
}

export function truncCommandMessage(message) {
    const commandMatch = message.match(commandRegex);
    if (commandMatch) {
        return message.substring(0, commandMatch.index + commandMatch[0].length);
    }
    return message;
}

export function isAction(name) {
    return actionsList.find(action => action.name === name) !== undefined;
}

/**
 * @param {Object} command
 * @returns {Object[]} The command's parameters.
 */
function commandParams(command) {
    if (!command.params)
        return [];
    return Object.values(command.params);
}

/**
 * @param {Object} command
 * @returns {string[]} The names of the command's parameters.
 */
function commandParamNames(command) {
    if (!command.params)
        return [];
    return Object.keys(command.params);
}

function numParams(command) {
    return commandParams(command).length;
}

export async function executeCommand(agent, message) {
    let parsed = parseCommandMessage(message);
    if (typeof parsed === 'string')
        return parsed; //The command was incorrectly formatted or an invalid input was given.
    else {
        console.log('parsed command:', parsed);
        const command = getCommand(parsed.commandName);
        let numArgs = 0;
        if (parsed.args) {
            numArgs = parsed.args.length;
        }
        if (numArgs !== numParams(command))
            return `Command ${command.name} was given ${numArgs} args, but requires ${numParams(command)} args.`;
        else {
            const result = await command.perform(agent, ...parsed.args);
            return result;
        }
    }
}

export function getCommandDocs(agent) {
    const typeTranslations = {
        //This was added to keep the prompt the same as before type checks were implemented.
        //If the language model is giving invalid inputs changing this might help.
        'float':             'number',
        'int':               'number',
        'BlockName':         'string',
        'ItemName':          'string',
        'BlockOrItemName':   'string',
        'boolean':           'bool'
    }
    const sourceCommands = agent?.isBridgeAgent ? baritoneList : commandList;
    let docs = `\n*COMMAND DOCS\n You can use the following commands to perform actions and get information about the world. 
    Use the commands with the syntax: !commandName or !commandName("arg1", 1.2, ...) if the command takes arguments.\n
    Do not use codeblocks. Use double quotes for strings. Only use one command in each response, trailing commands and comments will be ignored.\n`;
    for (let command of sourceCommands) {
        if (agent.blocked_actions.includes(command.name)) {
            continue;
        }
        docs += command.name + ': ' + command.description + '\n';
        if (command.params) {
            docs += 'Params:\n';
            for (let param in command.params) {
                docs += `${param}: (${typeTranslations[command.params[param].type]??command.params[param].type}) ${command.params[param].description}\n`;
            }
        }
    }
    return docs + '*\n';
}

/**
 * Returns a system prompt snippet informing the model to use native tool calls
 * instead of typing !commands in text. Used in place of $COMMAND_DOCS when
 * the model supports native tool calling.
 * @returns {string}
 */
function formatToolExample(toolName, command) {
    if (!command.params || Object.keys(command.params).length === 0) {
        return `${toolName}()`;
    }
    const args = Object.keys(command.params).map(paramName => {
        const param = command.params[paramName];
        const example = param.type === 'string' || param.type === 'BlockName' || param.type === 'ItemName' || param.type === 'BlockOrItemName'
            ? `"${paramName}_example"`
            : param.type === 'boolean'
                ? 'true'
                : '1';
        return `${paramName}: ${example}`;
    });
    return `${toolName}({ ${args.join(', ')} })`;
}

export function getToolCallDocs(agent) {
    const typeTranslations = {
        'float':             'number',
        'int':               'number',
        'BlockName':         'string',
        'ItemName':          'string',
        'BlockOrItemName':   'string',
        'boolean':           'bool'
    };

    let docs = '\n*TOOL CALLING\n Use the provided tools to perform actions and get information about the world. ' +
        'Do NOT type !commands in your text response. Call the appropriate tool directly for any action you want to perform. ' +
        'Tool names are the command names without the leading !. ' +
        'For example, use `mine` for the mine command or `baritoneMine` for the Baritone mine helper. ' +
        'You may include a brief conversational message alongside your tool call if appropriate.*\n\n';

    if (!agent) {
        return docs + '*\n';
    }

    const sourceCommands = agent.isBridgeAgent ? baritoneList : commandList;
    for (let command of sourceCommands) {
        if (agent.blocked_actions.includes(command.name)) {
            continue;
        }
        const toolName = command.name.substring(1);
        docs += `${toolName}: ${command.description}\n`;
        if (command.params) {
            docs += 'Params:\n';
            for (let paramName in command.params) {
                const paramDef = command.params[paramName];
                docs += `  ${paramName}: (${typeTranslations[paramDef.type] ?? paramDef.type}) ${paramDef.description}\n`;
            }
            docs += `Example: ${formatToolExample(toolName, command)}\n`;
        }
        docs += '\n';
    }
    return docs + '*\n';
}

/**
 * Converts the available commands into an array of tool schemas compatible with the
 * Ollama / OpenAI function-calling specification.
 * @param {Object} agent
 * @returns {Array}
 */
export function getToolSchemas(agent) {
    const typeMap = {
        'float':           'number',
        'int':             'integer',
        'boolean':         'boolean',
        'string':          'string',
        'BlockName':       'string',
        'ItemName':        'string',
        'BlockOrItemName': 'string',
    };

    return commandList
        .filter(command => !agent.blocked_actions.includes(command.name))
        .map(command => {
            const toolName = command.name.substring(1);
            const toolFunc = {
                name: toolName, // strip the leading '!'
                description: `${command.description}`,
                parameters: { type: 'object', properties: {} },
            };

            if (command.params && Object.keys(command.params).length > 0) {
                toolFunc.parameters.required = [];
                const exampleArgs = [];
                for (const [paramName, paramDef] of Object.entries(command.params)) {
                    toolFunc.parameters.properties[paramName] = {
                        type: typeMap[paramDef.type] || 'string',
                        description: paramDef.description,
                    };
                    toolFunc.parameters.required.push(paramName);
                    const exampleValue = paramDef.type === 'string' || paramDef.type === 'BlockName' || paramDef.type === 'ItemName' || paramDef.type === 'BlockOrItemName'
                        ? `${paramName}_example`
                        : paramDef.type === 'boolean'
                            ? 'true'
                            : '1';
                    exampleArgs.push(`${paramName}: ${exampleValue}`);
                }
                toolFunc.description += ` Example: ${toolName}({ ${exampleArgs.join(', ')} })`;
            }

            return { type: 'function', function: toolFunc };
        });
}

/**
 * Converts a native tool call (function name + arguments object) back into the
 * equivalent `!command(arg1, arg2, ...)` string that the existing command pipeline
 * can parse and execute.
 *
 * Arguments are ordered according to the command's parameter definition so the
 * result is always valid regardless of how the model ordered the JSON keys.
 *
 * @param {string} toolName  - Function name as returned by the model (no `!` prefix).
 * @param {Object} toolArgs  - Named arguments returned by the model.
 * @returns {string|null}    - The `!command(...)` string, or null if unknown command.
 */
export function toolCallToCommand(toolName, toolArgs) {
    const commandName = '!' + toolName;
    const command = commandMap[commandName];
    if (!command) return null;

    if (!command.params || Object.keys(command.params).length === 0) {
        return commandName;
    }

    // Use the param order from the command definition, not from the model's JSON.
    const args = Object.keys(command.params).map(paramName => {
        const val = toolArgs[paramName];
        if (typeof val === 'string') return `"${val}"`;
        return String(val);
    });

    return `${commandName}(${args.join(', ')})`;
}
