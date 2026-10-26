#!/usr/bin/env node
// Generates docs/openapi.json from the same controller scan as docs/API.md.
//
//   node tools/openapi.mjs            # rewrite docs/openapi.json
//   node tools/openapi.mjs --check    # exit 1 if it is stale (what CI runs)
//   node tools/openapi.mjs --stdout   # print it, write nothing
//
// The paths, verbs, operation ids and Java method names are exact. The request/response *fields* are not:
// that needs DTO annotations, and a generated schema that lies is worse than a generic object. A client
// generator can use the paths and operation ids today; docs/API.md is the field-level reference.
import { readFileSync, writeFileSync } from 'node:fs';
import { collectEndpoints } from './api-endpoints.mjs';

const TARGET = 'docs/openapi.json';
const { all } = collectEndpoints();
const version = (/<version>([^<]+)<\/version>/.exec(readFileSync('pom.xml', 'utf8')) || [])[1] || '1.0.0';

const tagOf = (path) => {
  const parts = path.replace(/^\/api\/v1\//, '').split('/');
  return parts[0] || 'root';
};

const components = {
  securitySchemes: {
    bearerAuth: {
      type: 'http',
      scheme: 'bearer',
      description: 'API key from phoenixgraph.security.keys. Send Authorization: Bearer <key> or X-API-Key.',
    },
  },
  schemas: {},
};

const schemaFor = (javaType) => {
  const type = (javaType || '').trim();
  if (!type) return { type: 'object' };
  if (type === 'String') return { type: 'string' };
  if (type === 'Object') return {};
  const name = type.replace(/[<>[\],.\s]/g, '_');
  components.schemas[name] = {
    type: 'object',
    description: `Request/response type ${type}; see docs/API.md for its fields.`,
  };
  return { $ref: `#/components/schemas/${name}` };
};

const paths = {};
for (const endpoint of all) {
  const path = endpoint.path;
  const item = paths[path] || (paths[path] = {});
  const operation = {
    operationId: `${endpoint.controller.replace(/Controller$/, '')}_${endpoint.method}`,
    tags: [tagOf(path)],
    summary: `${endpoint.verb} ${path}`,
    description: `Java: ${endpoint.controller}#${endpoint.method}`,
    responses: {
      200: {
        description: String(endpoint.returns || 'OK'),
        content: {
          'application/json': { schema: schemaFor(endpoint.returns) },
        },
      },
      default: {
        description: 'Error body {error, exception, hint, requestId}; see docs/API.md §3.',
        content: { 'application/json': { schema: { type: 'object' } } },
      },
    },
    'x-phoenixgraph-controller': endpoint.controller,
    'x-phoenixgraph-method': endpoint.method,
  };
  const parameters = [...path.matchAll(/\{([^}]+)\}/g)].map((match) => ({
    name: match[1],
    in: 'path',
    required: true,
    schema: { type: 'string' },
  }));
  if (parameters.length) operation.parameters = parameters;
  if (endpoint.body && ['POST', 'PUT', 'PATCH'].includes(endpoint.verb)) {
    operation.requestBody = {
      required: true,
      content: {
        'application/json': { schema: schemaFor(endpoint.body) },
      },
    };
  }
  item[endpoint.verb.toLowerCase()] = operation;
}

const spec = {
  openapi: '3.0.3',
  info: {
    title: 'PhoenixGraph API',
    version,
    description:
      'Auditable context layer: query, write, agent tools and semantic layer. Paths and operation ids are '
      + 'generated from the controllers; field-level request/response documentation is in docs/API.md.',
  },
  servers: [
    { url: 'http://localhost:8081', description: 'local service' },
  ],
  security: [{ bearerAuth: [] }],
  tags: [...new Set(all.map((endpoint) => tagOf(endpoint.path)))].sort().map((name) => ({ name })),
  paths,
  components,
};

const json = `${JSON.stringify(spec, null, 2)}\n`;

if (process.argv.includes('--stdout')) {
  process.stdout.write(json);
  process.exit(0);
}
if (process.argv.includes('--check')) {
  let current = '';
  try {
    current = readFileSync(TARGET, 'utf8');
  } catch {
    current = '';
  }
  // A checkout with core.autocrlf (Windows) has CRLF in the working copy while the generator writes LF. That is
  // not staleness: compare the text, not the bytes, or the check fails on every Windows machine and passes in CI.
  const normalise = (text) => text.replace(/\r\n/g, '\n');
  if (normalise(current) !== normalise(json)) {
    console.error(`${TARGET} is out of date: run node tools/openapi.mjs`);
    process.exit(1);
  }
  console.log(`${TARGET} matches the ${all.length} endpoints`);
  process.exit(0);
}
writeFileSync(TARGET, json);
console.log(`wrote ${all.length} paths into ${TARGET}`);
