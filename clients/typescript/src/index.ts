/**
 * A zero-dependency TypeScript client for the PhoenixGraph HTTP API.
 *
 * The client mirrors the API rather than hiding it: every method has a `request` escape hatch, and the
 * answers are the JSON documents the service returns, so citations, coverage and quality stay visible.
 */

export interface ClientOptions {
  baseUrl?: string;
  apiKey?: string;
  timeoutMs?: number;
  headers?: Record<string, string>;
  fetch?: typeof fetch;
}

export interface Document {
  id: string;
  text: string;
  title?: string;
}

export interface IngestOptions {
  dictionary?: Record<string, string>;
  aliases?: Record<string, string>;
  gold?: string[];
  apply?: boolean;
  replace?: boolean;
  description?: string;
  rulePack?: string;
  dedupe?: boolean;
}

export interface SemanticOptions {
  dimensions?: string[];
  grain?: string;
  dialect?: string;
  filters?: Array<Record<string, unknown>>;
  order?: Array<Record<string, unknown>>;
  limit?: number;
  dataGraph?: string;
}

export interface AskOptions {
  mode?: string;
  confirmToken?: string;
  dataGraph?: string;
}

export class PhoenixGraphError extends Error {
  readonly status: number;
  readonly body: unknown;
  readonly requestId?: string;

  constructor(message: string, status: number, body: unknown, requestId?: string) {
    super(message);
    this.name = 'PhoenixGraphError';
    this.status = status;
    this.body = body;
    this.requestId = requestId;
  }
}

export class PhoenixGraphConnectionError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'PhoenixGraphConnectionError';
  }
}

const TOOL_PATHS = {
  find_entities: '/api/v1/agent/find-entities',
  search: '/api/v1/agent/search',
  related: '/api/v1/agent/related',
  similar: '/api/v1/agent/similar',
  related_similar: '/api/v1/agent/related-similar',
  similar_related: '/api/v1/agent/similar-related',
  path_between: '/api/v1/agent/path',
  context: '/api/v1/agent/context',
} as const;

const normalizeDocuments = (documents: Document[]): Required<Document>[] => {
  if (!documents.length) throw new Error('ingest needs at least one document');
  return documents.map((document) => {
    if (!document.id) throw new Error('a document needs an id');
    if (!document.text) throw new Error(`document '${document.id}' has no text`);
    return { id: String(document.id), title: String(document.title || ''), text: String(document.text) };
  });
};

const queryString = (params?: Record<string, unknown>): string => {
  if (!params) return '';
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) query.append(key, String(value));
  }
  const text = query.toString();
  return text ? `?${text}` : '';
};

export class PhoenixGraphClient {
  readonly baseUrl: string;
  readonly apiKey?: string;
  readonly timeoutMs: number;
  private readonly headers: Record<string, string>;
  private readonly fetcher: typeof fetch;

  constructor(options: ClientOptions | string = {}) {
    const config = typeof options === 'string' ? { baseUrl: options } : options;
    this.baseUrl = (config.baseUrl || 'http://localhost:8081').replace(/\/+$/, '');
    this.apiKey = config.apiKey;
    this.timeoutMs = config.timeoutMs ?? 30_000;
    this.headers = {
      Accept: 'application/json',
      'User-Agent': 'phoenixgraph-typescript/0.1.0',
      ...(config.headers || {}),
    };
    this.fetcher = config.fetch || globalThis.fetch;
    if (typeof this.fetcher !== 'function') {
      throw new Error('fetch is not available; pass a fetch implementation in ClientOptions.fetch');
    }
  }

  private async requestRaw(
    method: string,
    path: string,
    body?: unknown,
    params?: Record<string, unknown>,
  ): Promise<Response> {
    const headers: Record<string, string> = { ...this.headers };
    if (this.apiKey) headers.Authorization = `Bearer ${this.apiKey}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8';
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let response: Response;
    try {
      response = await this.fetcher(`${this.baseUrl}${path}${queryString(params)}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: controller.signal,
      });
    } catch (error) {
      if (controller.signal.aborted) {
        throw new PhoenixGraphConnectionError(
          `PhoenixGraph at ${this.baseUrl} did not answer within ${this.timeoutMs}ms`,
          { cause: error },
        );
      }
      throw new PhoenixGraphConnectionError(`Cannot reach PhoenixGraph at ${this.baseUrl}`, { cause: error });
    } finally {
      clearTimeout(timer);
    }
    if (!response.ok) {
      const text = await response.text();
      let payload: unknown = text;
      try {
        payload = text ? JSON.parse(text) : null;
      } catch {
        // keep the raw text
      }
      const object = payload && typeof payload === 'object' ? payload as Record<string, unknown> : {};
      let message = `PhoenixGraph answered HTTP ${response.status}`;
      if (typeof object.error === 'string') message = object.error;
      else if (typeof object.message === 'string') message = object.message;
      else if (text) message = text;
      if (typeof object.hint === 'string') message += ` (${object.hint})`;
      throw new PhoenixGraphError(message, response.status, payload, response.headers.get('x-request-id') || undefined);
    }
    return response;
  }

  private async decode<T>(response: Response): Promise<T> {
    const text = await response.text();
    if (!text) return null as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      return text as T;
    }
  }

  async request<T = any>(
    method: string,
    path: string,
    body?: unknown,
    params?: Record<string, unknown>,
  ): Promise<T> {
    return this.decode<T>(await this.requestRaw(method, path, body, params));
  }

  // -- service -----------------------------------------------------------------

  health<T = any>(): Promise<T> { return this.request('GET', '/api/v1/health'); }
  graphs<T = any>(): Promise<T> { return this.request('GET', '/api/v1/graphs'); }
  metrics<T = any>(graph?: string): Promise<T> {
    return this.request('GET', graph === undefined ? '/api/v1/metrics' : `/api/v1/metrics/${encodeURIComponent(graph)}`);
  }
  prometheus(): Promise<string> { return this.request<string>('GET', '/api/v1/metrics/prometheus'); }

  query<T = any>(
    graph: string,
    query: string,
    options: {
      language?: string;
      params?: Record<string, string>;
      includeResult?: boolean;
      returnSubgraph?: boolean;
      source?: string;
      script?: boolean;
    } = {},
  ): Promise<T> {
    const body: Record<string, unknown> = {
      graphName: graph,
      query,
      language: options.language ?? 'gremlin',
      includeResult: options.includeResult ?? true,
      returnSubgraph: options.returnSubgraph ?? false,
    };
    if (options.params) body.params = options.params;
    if (options.source !== undefined) body.source = options.source;
    if (options.script !== undefined) body.script = options.script;
    return this.request('POST', '/api/v1/query', body);
  }

  // -- ingestion and tools -----------------------------------------------------

  ingest<T = any>(graph: string, documents: Document[], options: IngestOptions = {}): Promise<T> {
    const body: Record<string, unknown> = {
      graph,
      documents: normalizeDocuments(documents),
      apply: options.apply ?? true,
      replace: options.replace ?? false,
      dedupe: options.dedupe ?? true,
    };
    if (options.description !== undefined) body.description = options.description;
    if (options.rulePack !== undefined) body.rulePack = options.rulePack;
    if (options.dictionary) body.dictionary = options.dictionary;
    if (options.aliases) body.aliases = options.aliases;
    if (options.gold) body.gold = options.gold;
    return this.request('POST', '/api/v1/agent/ingest', body);
  }

  quality<T = any>(graph: string): Promise<T> {
    return this.request('GET', `/api/v1/agent/quality/${encodeURIComponent(graph)}`);
  }
  index<T = any>(graph: string, vectors = false): Promise<T> {
    return this.request('GET', `/api/v1/agent/index/${encodeURIComponent(graph)}`, undefined, { vectors });
  }
  tools<T = any>(): Promise<T> { return this.request('GET', '/api/v1/agent/tools'); }

  private tool<T>(name: keyof typeof TOOL_PATHS, graph: string, fields: Record<string, unknown>): Promise<T> {
    if (!graph) throw new Error('a tool call needs a graph');
    const body: Record<string, unknown> = { graph };
    for (const [key, value] of Object.entries(fields)) {
      if (value !== undefined && value !== null) body[key] = value;
    }
    return this.request('POST', TOOL_PATHS[name], body);
  }

  findEntities<T = any>(graph: string, question: string, k = 5): Promise<T> {
    return this.tool('find_entities', graph, { question, k });
  }
  search<T = any>(graph: string, question: string, k = 5): Promise<T> {
    return this.tool('search', graph, { question, k });
  }
  related<T = any>(graph: string, entity: string, options: { relation?: string; hops?: number } = {}): Promise<T> {
    return this.tool('related', graph, { entity, relation: options.relation, hops: options.hops ?? 1 });
  }
  similar<T = any>(graph: string, entity: string, k = 5): Promise<T> {
    return this.tool('similar', graph, { entity, k });
  }
  relatedSimilar<T = any>(graph: string, entity: string, k = 3): Promise<T> {
    return this.tool('related_similar', graph, { entity, k });
  }
  similarRelated<T = any>(graph: string, entity: string, k = 3): Promise<T> {
    return this.tool('similar_related', graph, { entity, k });
  }
  pathBetween<T = any>(graph: string, sourceEntity: string, targetEntity: string, maxHops = 3): Promise<T> {
    return this.tool('path_between', graph, { from: sourceEntity, to: targetEntity, maxHops });
  }
  context<T = any>(graph: string, question: string, k = 5, hops = 1): Promise<T> {
    return this.tool('context', graph, { question, k, hops });
  }

  // -- semantic layer and data tools ------------------------------------------

  semanticModel<T = any>(graph: string, dataGraph?: string): Promise<T> {
    return this.request('GET', `/api/v1/semantic/${encodeURIComponent(graph)}/model`, undefined, { dataGraph });
  }

  private semanticBody(metric: string, options: SemanticOptions): Record<string, unknown> {
    const body: Record<string, unknown> = { metric };
    if (options.dimensions !== undefined) body.dimensions = options.dimensions;
    if (options.grain !== undefined) body.grain = options.grain;
    if (options.dialect !== undefined) body.dialect = options.dialect;
    if (options.filters !== undefined) body.filters = options.filters;
    if (options.order !== undefined) body.order = options.order;
    if (options.limit !== undefined) body.limit = options.limit;
    if (options.dataGraph !== undefined) body.dataGraph = options.dataGraph;
    return body;
  }

  semanticTranslate<T = any>(graph: string, metric: string, options: SemanticOptions = {}): Promise<T> {
    return this.request('POST', `/api/v1/semantic/${encodeURIComponent(graph)}/translate`, this.semanticBody(metric, options));
  }
  semanticValidate<T = any>(graph: string, metric: string, options: SemanticOptions = {}): Promise<T> {
    return this.request('POST', `/api/v1/semantic/${encodeURIComponent(graph)}/validate`, this.semanticBody(metric, options));
  }

  askData<T = any>(graph: string, question: string, options: AskOptions = {}): Promise<T> {
    const body: Record<string, unknown> = { question, mode: options.mode ?? 'auto' };
    if (options.confirmToken !== undefined) body.confirmToken = options.confirmToken;
    if (options.dataGraph !== undefined) body.dataGraph = options.dataGraph;
    return this.request('POST', `/api/v1/semantic/${encodeURIComponent(graph)}/ask`, body);
  }

  analyzeBusiness<T = any>(
    graph: string,
    options: AskOptions & { question?: string; metric?: string; dimensions?: string[]; periods?: string[] } = {},
  ): Promise<T> {
    const body: Record<string, unknown> = { mode: options.mode ?? 'auto' };
    for (const key of ['question', 'metric', 'dimensions', 'periods', 'confirmToken', 'dataGraph'] as const) {
      if (options[key] !== undefined) body[key] = options[key];
    }
    return this.request('POST', `/api/v1/analysis/${encodeURIComponent(graph)}/analyze`, body);
  }

  attributeChange<T = any>(
    graph: string,
    options: AskOptions & { question?: string; metric?: string; by?: string[]; from?: string; to?: string } = {},
  ): Promise<T> {
    const body: Record<string, unknown> = { mode: options.mode ?? 'auto' };
    for (const key of ['question', 'metric', 'by', 'from', 'to', 'confirmToken', 'dataGraph'] as const) {
      if (options[key] !== undefined) body[key] = options[key];
    }
    return this.request('POST', `/api/v1/analysis/${encodeURIComponent(graph)}/attribute`, body);
  }

  close(): void {
    // There is no persistent connection; this exists for symmetry with the Python client.
  }
}

export default PhoenixGraphClient;
