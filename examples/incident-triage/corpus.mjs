// The corpus of the triage case: the documents and the window each one's facts hold in. It lives apart from
// triage.mjs because two programs need it - the example and the check that asserts it - and a corpus written
// twice is a corpus that drifts (a re-ingest without the windows silently un-versioned the graph once).
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

/**
 * When each document's facts hold. The topology, hosts and metrics are structural (no window: true at every
 * instant); the September RCA describes the state before deploy-48, so its facts close the moment deploy-48
 * landed. That is the dimension this case illustrates: an expired fact is not deleted, it stops being true.
 */
export const WINDOWS = {
  '20-inc-1042.md': { validFrom: '2026-09-12', validTo: '2026-10-24' },
  '21-inc-1051.md': { validFrom: '2026-10-02' },
  '22-inc-1063.md': { validFrom: '2026-10-11' },
  '23-inc-1070.md': { validFrom: '2026-10-20' },
  '30-change-window.md': { validFrom: '2026-10-24' },
};

/** The documents of the corpus, each with the window from {@link WINDOWS} when it has one. */
export function documents() {
  const dir = join(HERE, 'documents');
  return readdirSync(dir).filter((name) => name.endsWith('.md')).sort()
    .map((name) => ({
      id: name.replace(/\.md$/, ''), title: name, text: readFileSync(join(dir, name), 'utf8'),
      ...(WINDOWS[name] || {}),
    }));
}

/** The gazetteer the extraction uses. */
export function dictionary() {
  return JSON.parse(readFileSync(join(HERE, 'dictionary.json'), 'utf8'));
}

/** The graph the example builds, unless the caller names another one. */
export const DEFAULT_GRAPH = 'incident-triage';
