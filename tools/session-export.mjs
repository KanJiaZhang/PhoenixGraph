#!/usr/bin/env node
/**
 * Turn a pi session transcript (JSONL under ~/.pi/agent/sessions/--<cwd>--/) into readable
 * Markdown: the user's prompts, the model's reasoning and answers, every tool call with its
 * result, and the compaction summaries that were produced along the way.
 *
 *   node tools/session-export.mjs                                  # this session, whole file
 *   node tools/session-export.mjs --from 2026-09-17                # only today (local time)
 *   node tools/session-export.mjs --session latest --out today.md  # write to a file
 *   node tools/session-export.mjs --thinking none --tools brief    # skim only the answers
 *
 * Options
 *   --session <path|id|latest>  session file (default: $PI_SESSION_FILE, else the newest one)
 *   --from / --to <YYYY-MM-DD>  include turns whose timestamp falls in [from, to] (local)
 *   --thinking full|brief|none  how much reasoning to keep (default: full)
 *   --tools full|brief|none     how much tool traffic to keep (default: brief)
 *   --max-chars <n>             truncate each tool call/result to n characters (default: 2000)
 *   --out <file>                write here instead of stdout
 */
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const args = process.argv.slice(2);
const arg = (name, fallback = null) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const thinkingMode = arg('thinking', 'full');
const toolMode = arg('tools', 'brief');
const maxChars = Number(arg('max-chars', '2000'));
const from = arg('from');
const to = arg('to');
const out = arg('out');

/** the session directory for the current working directory (pi slugs the path) */
function sessionsDir(cwd) {
  const slug = `--${cwd.replace(/[\\/]/g, '-').replace(/[:]/g, '')}--`;
  return join(homedir(), '.pi', 'agent', 'sessions', slug);
}

function resolveSession() {
  const wanted = arg('session') || process.env.PI_SESSION_FILE;
  if (wanted && wanted !== 'latest') return wanted;
  const dir = sessionsDir(process.cwd());
  let best = null;
  for (const name of readdirSync(dir)) {
    if (!name.endsWith('.jsonl')) continue;
    const path = join(dir, name);
    const mtime = statSync(path).mtimeMs;
    if (!best || mtime > best.mtime) best = { path, mtime };
  }
  if (!best) throw new Error(`no sessions in ${dir}`);
  return best.path;
}

/** YYYY-MM-DD in local time, so "today" means the day you were working */
const day = (iso) => new Date(iso).toLocaleDateString('sv-SE');

const clip = (text, limit = maxChars) => {
  const s = String(text ?? '');
  return s.length <= limit ? s : `${s.slice(0, limit)}\n… [${s.length - limit} more characters]`;
};

/** one line for a tool call: name plus the arguments that matter */
function callLine(name, input) {
  if (toolMode === 'brief') {
    const hint = input?.command || input?.path || input?.query || input?.url || input?.pattern || '';
    return `- \`${name}\` ${clip(String(hint).replace(/\s+/g, ' '), 160)}`;
  }
  return `- \`${name}\`\n\n\`\`\`json\n${clip(JSON.stringify(input ?? {}, null, 1))}\n\`\`\``;
}

function render(records) {
  const lines = [];
  const keep = (ts) => (!from || day(ts) >= from) && (!to || day(ts) <= to);
  let kept = 0;
  const tools = new Map(); // toolCallId -> name (tool results carry only the id)

  for (const record of records) {
    if (record.type === 'compaction') {
      if (!keep(record.timestamp)) continue;
      lines.push(`\n---\n\n## 🗜 compaction (${record.timestamp})\n`);
      lines.push(record.summary || '');
      lines.push('');
      continue;
    }
    if (record.type === 'model_change' || record.type === 'thinking_level_change') {
      if (!keep(record.timestamp)) continue;
      lines.push(`> ⚙︎ ${record.type}: ${record.modelId || record.provider || record.level || ''}`);
      continue;
    }
    if (record.type !== 'message') continue;
    const message = record.message || {};
    const body = Array.isArray(message.content) ? message.content : [];
    const text = body.filter((c) => c.type === 'text').map((c) => c.text).join('\n');
    const thinking = body.filter((c) => c.type === 'thinking' || c.type === 'reasoning');
    const calls = body.filter((c) => c.type === 'toolCall');

    if (message.role === 'user') {
      // tool results arrive as a separate role: keep the transcript readable
      if (!keep(record.timestamp)) continue;
      kept++;
      lines.push(`\n---\n\n## 👤 user · ${new Date(record.timestamp).toLocaleString('sv-SE')}\n`);
      lines.push(text);
    } else if (message.role === 'assistant') {
      if (!keep(record.timestamp)) continue;
      kept++;
      lines.push(`\n### 🤖 assistant · ${new Date(record.timestamp).toLocaleString('sv-SE')}\n`);
      if (thinking.length && thinkingMode !== 'none') {
        const joined = thinking.map((c) => c.thinking || c.text || '').join('\n\n');
        lines.push('<details><summary>💭 思考</summary>\n');
        lines.push(thinkingMode === 'brief' ? clip(joined, 1200) : joined);
        lines.push('\n</details>\n');
      }
      if (text) lines.push(text);
      if (calls.length && toolMode !== 'none') {
        lines.push('');
        for (const call of calls) {
          tools.set(call.id || call.toolCallId, call.name);
          lines.push(callLine(call.name, call.arguments ?? call.input));
        }
      }
      lines.push('');
    } else if (message.role === 'toolResult' && toolMode === 'full') {
      if (!keep(record.timestamp)) continue;
      const name = tools.get(message.toolCallId) || 'tool';
      lines.push(`<details><summary>📄 ${name} → 输出</summary>\n`);
      lines.push('```');
      lines.push(clip(body.filter((c) => c.type === 'text').map((c) => c.text).join('\n')));
      lines.push('```');
      lines.push('\n</details>\n');
    }
  }
  return { markdown: lines.join('\n'), kept };
}

const session = resolveSession();
const records = readFileSync(session, 'utf8')
  .split('\n')
  .filter(Boolean)
  .flatMap((line) => {
    try { return [JSON.parse(line)]; } catch { return []; }
  });
const { markdown, kept } = render(records);

const header = [
  `# Session transcript`,
  '',
  `- file: \`${session}\``,
  `- records: ${records.length} (rendered turns: ${kept})`,
  `- filter: ${from || '*'} … ${to || '*'}`,
  `- thinking: ${thinkingMode}, tools: ${toolMode}`,
].join('\n');

if (out) {
  writeFileSync(out, `${header}\n${markdown}\n`, 'utf8');
  console.log(`${out}: ${kept} turns, ${(statSync(out).size / 1024).toFixed(0)} KB`);
} else {
  console.log(header);
  console.log(markdown);
}
