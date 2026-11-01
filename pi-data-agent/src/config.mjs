/**
 * Configuration and small durable state.
 *
 * Precedence (low to high): defaults, ~/.pi/agent/state/pi-data-agent.json, <cwd>/.pi/data-agent.json,
 * environment, explicit tool/CLI options. The project file is the team's (endpoint, graph, fact graph and
 * vertex type); the state file is the user's last choice; neither is written by the extractor.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';

export const DEFAULTS = {
  endpoint: 'http://localhost:8081',
  graph: 'data-agent',
  dataGraph: null,
  vertexType: null,
  apiKey: '',
  sources: [],
};

export function stateFile(home = homedir()) {
  return process.env.PI_DATA_AGENT_STATE_FILE || join(home, '.pi', 'agent', 'state', 'pi-data-agent.json');
}

export function projectFile(cwd = process.cwd()) {
  return join(cwd, '.pi', 'data-agent.json');
}

export function readJson(path) {
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf8'));
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

export function writeJson(path, value) {
  try {
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
    return true;
  } catch {
    return false;
  }
}

export function loadConfig(options = {}) {
  const cwd = options.cwd || process.cwd();
  const home = options.home || homedir();
  const project = readJson(projectFile(cwd));
  const state = readJson(stateFile(home));
  const env = options.env || process.env;
  const merged = {
    ...DEFAULTS,
    ...state,
    ...project,
    ...(env.PHOENIXGRAPH_API ? { endpoint: env.PHOENIXGRAPH_API } : {}),
    ...(env.PHOENIXGRAPH_API_KEY ? { apiKey: env.PHOENIXGRAPH_API_KEY } : {}),
    ...(env.PI_DATA_AGENT_GRAPH ? { graph: env.PI_DATA_AGENT_GRAPH } : {}),
    ...(env.PI_DATA_AGENT_DATA_GRAPH ? { dataGraph: env.PI_DATA_AGENT_DATA_GRAPH } : {}),
    ...(env.PI_DATA_AGENT_VERTEX_TYPE ? { vertexType: env.PI_DATA_AGENT_VERTEX_TYPE } : {}),
  };
  for (const [key, value] of Object.entries(options)) {
    if (value !== undefined && value !== null && value !== '') merged[key] = value;
  }
  merged.endpoint = String(merged.endpoint || DEFAULTS.endpoint).replace(/\/+$/, '');
  merged.sources = Array.isArray(merged.sources) ? merged.sources : [];
  return merged;
}

/** Remembers the user's last graph / fact graph / endpoint without touching the project file. */
export function remember(changes, home = homedir()) {
  const path = stateFile(home);
  const current = readJson(path);
  const next = { ...current, ...changes };
  writeJson(path, next);
  return next;
}

export function ensureDir(path) {
  mkdirSync(path, { recursive: true });
  return path;
}

/**
 * The project the artifacts belong to: the nearest ancestor with a .git (then .pi). Running the CLI from
 * inside the package must still put snapshots, audits, dashboards and schema copies where the service and
 * the repository expect them - not in the package directory. Falls back to the current directory.
 */
export function projectRoot(start = process.cwd()) {
  let directory = resolve(start);
  for (let depth = 0; depth < 12; depth += 1) {
    if (existsSync(join(directory, '.git'))) return directory;
    const parent = dirname(directory);
    if (parent === directory) break;
    directory = parent;
  }
  directory = resolve(start);
  for (let depth = 0; depth < 12; depth += 1) {
    if (existsSync(join(directory, '.pi'))) return directory;
    const parent = dirname(directory);
    if (parent === directory) break;
    directory = parent;
  }
  return resolve(start);
}

export function exists(path) {
  return existsSync(path);
}
