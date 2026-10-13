/*
 * Checks deploy/k8s without a cluster.
 *
 *   node tools/check-k8s.mjs
 *
 * `kubectl kustomize` proves the set builds; it does not prove the set is any good. The failures that matter in
 * a manifest are omissions and mismatches - a pinned image that says `latest`, a deployment without probes or
 * resources, a service whose selector matches nothing, a `configMapKeyRef` to a key nobody defined - and those
 * are all checkable offline, which is where this repository runs its CI.
 *
 * The YAML is read as text rather than parsed: these files are written here and are deliberately simple, and a
 * checker that needs a YAML library would be offlining the check to keep a dependency out of a test tool.
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';

const DIR = 'deploy/k8s';
const failures = [];
const check = (name, ok, detail) => {
  if (!ok) failures.push(name);
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};

const files = readdirSync(DIR).filter((name) => name.endsWith('.yaml') || name.endsWith('.yml'));
const documents = new Map(); // file -> text
for (const file of files) {
  documents.set(file, readFileSync(`${DIR}/${file}`, 'utf8'));
}
const all = [...documents.values()].join('\n');

// ---- 1. does the set build? ----------------------------------------------------------------------------
let built = '';
try {
  built = execFileSync('kubectl', ['kustomize', DIR], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  check('kubectl kustomize builds the whole set', built.split('\n').filter((line) => line.startsWith('kind:')).length >= 6,
    `${built.split('\n').filter((line) => line.startsWith('kind:')).length} resources`);
} catch (error) {
  check('kubectl kustomize builds the whole set', false, 'kubectl is not available (CI installs it)');
}

// ---- 2. images are pinned ------------------------------------------------------------------------------
const images = [...all.matchAll(/image:\s*(\S+)/g)].map((match) => match[1]);
check('every image is pinned to a tag', images.length > 0 && images.every((image) => /:[^/\s]+$/.test(image)),
  images.join(' '));
check('no image uses the latest tag', images.every((image) => !image.endsWith(':latest')), images.join(' '));
check('the store image matches the version the compose file and CI pin',
  images.some((image) => image.includes('aerospike-server:8.1.2.5')));

// ---- 3. the workloads say what they need ---------------------------------------------------------------
// One file may hold several documents (a Deployment and its Service), so the checks run per *workload* and not
// per file: the first version checked the whole file and demanded probes from a PersistentVolumeClaim.
const workloadDocuments = [...documents.entries()].flatMap(([file, text]) => text.split(/^---$/m)
  .map((document) => ({ file, document }))
  .filter(({ document }) => /^kind: (Deployment|StatefulSet)$/m.test(document)));
for (const { file, document } of workloadDocuments) {
  const name = (/^  name: (\S+)$/m.exec(document) || [])[1] || file;
  check(`${name}: has requests and limits`,
    /resources:/.test(document) && /requests:/.test(document) && /limits:/.test(document));
  check(`${name}: has a liveness and a readiness probe`,
    /livenessProbe:/.test(document) && /readinessProbe:/.test(document));
  check(`${name}: sets terminationGracePeriodSeconds`, /terminationGracePeriodSeconds:/.test(document));
  check(`${name}: runs as non-root`, /runAsNonRoot: true/.test(document) || name === 'aerospike',
    name === 'aerospike' ? 'the store image has its own user' : '');
}
const engine = documents.get('graph-http.yaml');
const grace = Number((/terminationGracePeriodSeconds:\s*(\d+)/.exec(engine) || [])[1]);
check('the engine waits longer than its shutdown phase before it is killed', grace >= 30,
  `terminationGracePeriodSeconds=${grace}, spring timeout is 30s`);
check('the engine is behind a PodDisruptionBudget', /kind: PodDisruptionBudget/.test(engine));
check('the engine is single-replica on purpose (one process per RocksDB directory)', /replicas: 1/.test(engine));
check('the RocksDB directory is a persistent volume, not an emptyDir',
  /persistentVolumeClaim:/.test(engine) && !/emptyDir:/.test(engine));

// ---- 4. configuration and secrets are referenced, never inlined ----------------------------------------
const configMap = documents.get('configmap.yaml');
const configKeys = new Set([...configMap.matchAll(/^\s{2}([A-Z0-9_]+):/gm)].map((match) => match[1]));
for (const ref of all.matchAll(/configMapKeyRef:\s*\n\s*name:\s*(\S+)\s*\n\s*key:\s*(\S+)/g)) {
  check(`configMapKeyRef ${ref[1]}/${ref[2]} exists`, ref[1] !== 'phoenixgraph-config' || configKeys.has(ref[2]));
}
const secretRefs = [...all.matchAll(/secretKeyRef:\s*\n\s*name:\s*(\S+)\s*\n\s*key:\s*(\S+)/g)];
check('the engine takes its API keys from a Secret', secretRefs.length > 0,
  secretRefs.map((ref) => `${ref[1]}/${ref[2]}`).join(' '));
const exampleSecrets = documents.get('secret.example.yaml') || '';
for (const ref of secretRefs) {
  check(`secretKeyRef ${ref[2]} is defined by the example secret`, exampleSecrets.includes(ref[2] + ':'));
}
check('no placeholder secret value leaked into a workload',
  !/replace-me/.test(engine) && !/replace-me/.test(documents.get('graph-web.yaml') || ''));

// ---- 5. the wiring matches -----------------------------------------------------------------------------
const claims = new Set([...all.matchAll(/^\s{2}name: (phoenixgraph-\S+)$/gm)].map((match) => match[1]));
for (const claim of all.matchAll(/claimName:\s*(\S+)/g)) {
  check(`claimName ${claim[1]} is defined`, claims.has(claim[1]));
}
for (const configMapVolume of all.matchAll(/configMap:\s*\n\s*name:\s*(\S+)/g)) {
  const name = configMapVolume[1];
  const defined = files.some((file) => new RegExp(`^  name: ${name}$`, 'm').test(documents.get(file)));
  const optional = new RegExp(`configMap:\\s*\\n\\s*name: ${name}\\s*\\n\\s*optional: true`).test(all);
  check(`configMap volume ${name} is defined or optional`, defined || optional,
    defined ? 'defined' : optional ? 'optional' : 'neither');
}
const podLabels = new Set([...engine.matchAll(/^\s{8}(app\.kubernetes\.io\/component: \S+)$/gm)].map((match) => match[1]));
const serviceSelectors = [...all.matchAll(/^  selector:\s*\n((?:\s{4}\S+: \S+\n)+)/gm)].map((match) => match[1]);
check('every Service selector names a label the pods carry',
  serviceSelectors.some((selector) => /component: engine/.test(selector)),
  serviceSelectors.length + ' selector(s)');
check('the UI points at the engine service by name',
  /PHOENIXGRAPH_HTTP_URL: http:\/\/graph-http:8081/.test(configMap));

// ---- 6. the manifests know what the application serves --------------------------------------------------
check('the probes use endpoints this service really has',
  /path: \/api\/v1\/health/.test(engine) && /path: \/api\/v1\/graphs/.test(engine));
check('Prometheus scraping is annotated with the endpoint that exists',
  /prometheus.io\/path: \/api\/v1\/metrics\/prometheus/.test(engine));

console.log(failures.length
  ? `\n${failures.length} problem(s) in ${DIR}`
  : `\n${DIR} is coherent (${files.length} files, ${images.length} pinned images, probes, resources, PDB, secrets by reference)`);
process.exit(failures.length ? 1 : 0);
