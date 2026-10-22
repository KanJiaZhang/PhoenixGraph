// Confluence Cloud/Server connector: pages of one space, as documents for /api/v1/agent/ingest.
//
//   const docs = await confluenceDocuments({ baseUrl, space, email, token });
//
// baseUrl is the wiki root, e.g. https://acme.atlassian.net/wiki. Cloud uses Basic auth with an API token
// (email + token); Server/Data Center can use a personal access token as a Bearer token (omit email).

import { stripHtml } from './common.mjs';

const authHeader = ({ email, token }) => {
  if (!token) throw new Error('Confluence needs a token (CONFLUENCE_TOKEN)');
  return email
    ? `Basic ${Buffer.from(`${email}:${token}`).toString('base64')}`
    : `Bearer ${token}`;
};

export async function confluenceDocuments({
  baseUrl,
  space,
  email = '',
  token = '',
  limit = 100,
  timeoutMs = 30000,
} = {}) {
  if (!baseUrl) throw new Error('Confluence needs a base URL (--base or CONFLUENCE_BASE_URL)');
  if (!space) throw new Error('Confluence needs a space key (--space or CONFLUENCE_SPACE)');
  const root = baseUrl.replace(/\/+$/, '');
  const authorization = authHeader({ email, token });
  const documents = [];
  let url = `${root}/rest/api/content?type=page&spaceKey=${encodeURIComponent(space)}`
    + '&expand=body.storage&limit=50';
  while (url && documents.length < limit) {
    const response = await fetch(url, {
      headers: { Accept: 'application/json', Authorization: authorization },
      signal: AbortSignal.timeout(timeoutMs),
    });
    const text = await response.text();
    if (!response.ok) {
      throw new Error(`Confluence answered ${response.status}: ${text.slice(0, 200)}`);
    }
    let payload;
    try {
      payload = JSON.parse(text);
    } catch {
      throw new Error(`Confluence returned a non-JSON body: ${text.slice(0, 200)}`);
    }
    for (const page of payload.results || []) {
      const body = page.body?.storage?.value || page.body?.view?.value || '';
      const content = stripHtml(body);
      if (!content) continue;
      documents.push({
        id: `confluence:${page.id}`,
        title: page.title || `Confluence page ${page.id}`,
        text: content,
      });
      if (documents.length >= limit) break;
    }
    const next = payload._links?.next;
    if (!next) {
      url = null;
    } else if (next.startsWith('http')) {
      url = next;
    } else if (next.startsWith('/wiki/') && root.endsWith('/wiki')) {
      url = root.slice(0, -'/wiki'.length) + next;
    } else {
      url = root + next;
    }
  }
  return documents;
}
