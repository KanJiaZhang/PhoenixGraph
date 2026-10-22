// Notion connector: pages the integration can see, with their block text.
//
//   const docs = await notionDocuments({ token });
//
// `baseUrl` is overridable for a mock/test server; production uses https://api.notion.com/v1.

import { stripHtml } from './common.mjs';

const VERSION = '2022-06-28';

const headers = (token) => ({
  Authorization: `Bearer ${token}`,
  'Notion-Version': VERSION,
  'Content-Type': 'application/json',
  Accept: 'application/json',
});

const plain = (richText) => (richText || []).map((item) => item.plain_text || '').join('');

const pageTitle = (page) => {
  for (const property of Object.values(page.properties || {})) {
    if (property.type === 'title') {
      const title = plain(property.title).trim();
      if (title) return title;
    }
  }
  return `Notion page ${page.id}`;
};

async function blockText(blockId, token, baseUrl, timeoutMs, depth = 0) {
  const parts = [];
  let cursor;
  do {
    const url = `${baseUrl}/blocks/${encodeURIComponent(blockId)}/children?page_size=100`
      + (cursor ? `&start_cursor=${encodeURIComponent(cursor)}` : '');
    const response = await fetch(url, { headers: headers(token), signal: AbortSignal.timeout(timeoutMs) });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`Notion answered ${response.status}: ${text.slice(0, 200)}`);
    }
    const payload = JSON.parse(text);
    for (const block of payload.results || []) {
      const data = block[block.type] || {};
      if (data.rich_text) {
        const line = plain(data.rich_text).trim();
        if (line) parts.push(block.type.startsWith('heading') ? `# ${line}` : line);
      }
      if (block.type === 'child_page' && data.title) {
        parts.push(`# ${data.title}`);
      }
      if (block.has_children && depth < 2) {
        const nested = await blockText(block.id, token, baseUrl, timeoutMs, depth + 1);
        if (nested) parts.push(nested);
      }
    }
    cursor = payload.has_more ? payload.next_cursor : null;
  } while (cursor);
  return stripHtml(parts.join('\n'));
}

export async function notionDocuments({
  token = '',
  query = '',
  limit = 100,
  baseUrl = 'https://api.notion.com/v1',
  timeoutMs = 30000,
} = {}) {
  if (!token) throw new Error('Notion needs an integration token (--token or NOTION_TOKEN)');
  const root = baseUrl.replace(/\/+$/, '');
  const documents = [];
  let cursor;
  while (documents.length < limit) {
    const response = await fetch(`${root}/search`, {
      method: 'POST',
      headers: headers(token),
      body: JSON.stringify({
        filter: { property: 'object', value: 'page' },
        page_size: Math.min(100, limit - documents.length),
        ...(query ? { query } : {}),
        ...(cursor ? { start_cursor: cursor } : {}),
      }),
      signal: AbortSignal.timeout(timeoutMs),
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`Notion answered ${response.status}: ${text.slice(0, 200)}`);
    }
    const payload = JSON.parse(text);
    for (const page of payload.results || []) {
      const body = await blockText(page.id, token, root, timeoutMs);
      if (!body) continue;
      documents.push({ id: `notion:${page.id}`, title: pageTitle(page), text: body });
      if (documents.length >= limit) break;
    }
    if (!payload.has_more) break;
    cursor = payload.next_cursor;
  }
  return documents;
}
