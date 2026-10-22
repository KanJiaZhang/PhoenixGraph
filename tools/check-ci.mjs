/*
 * Validates .github/workflows/ci.yml without needing a YAML parser or a CI runner.
 *
 *   node tools/check-ci.mjs
 *
 * What it is really for: the workflow is the only place where the *deployment* assumptions live (the JDK
 * version, the pinned Aerospike tag, the commands a contributor is told to run), and those assumptions are
 * written down in three other files. A workflow that says Java 17 while the pom says 21, or that pulls an
 * `aerospike:latest` while docker-compose.yml pins a version, is a green build that proves nothing - so this
 * checks the copies against each other and against the files on disk.
 */
import { existsSync, readFileSync, readdirSync } from 'node:fs';

const WORKFLOW = '.github/workflows/ci.yml';
const failures = [];
const check = (name, ok, detail) => {
  if (!ok) failures.push(name);
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? ' - ' + detail : ''}`);
};

const workflow = readFileSync(WORKFLOW, 'utf8');
const pom = readFileSync('pom.xml', 'utf8');
const compose = readFileSync('docker-compose.yml', 'utf8');

// 1. the jobs the plan promises, each with a runner
for (const job of ['unit', 'python-sdk', 'deployment', 'nightly']) {
  check(`job '${job}' exists and has runs-on`, new RegExp(`^  ${job}:[\\s\\S]*?runs-on:`, 'm').test(workflow));
}
check('the nightly job is scheduled, not per-push', /cron:/.test(workflow) && /if: github\.event_name == 'schedule'/.test(workflow));

// 2. the commands exist as files, and the checks the plan promised are wired in
for (const path of ['tools/api-endpoints.mjs', 'verification/run.mjs', 'tools/check-ci.mjs', 'tools/check-k8s.mjs']) {
  check(`the workflow calls ${path}, and it exists`, workflow.includes(path) && existsSync(path));
}
check('the workflow drives the deployment through docker compose',
  /docker compose (config --quiet|build|up -d|logs|down)/.test(workflow) && existsSync('docker-compose.yml'));
check('the unit job runs the generated-docs check', /api-endpoints\.mjs --check/.test(workflow));
check('the unit job checks the kubernetes manifests', /check-k8s.mjs/.test(workflow) && existsSync('deploy/k8s'));
check('the OpenAPI spec is generated and checked',
  existsSync('docs/openapi.json') && workflow.includes('tools/openapi.mjs --check'));
check('the Python client tests are wired in', workflow.includes('clients/python') && existsSync('clients/python/pyproject.toml'));
check('the Python job builds a wheel', workflow.includes('python -m build clients/python'));
check('the Python publish workflow exists', existsSync('.github/workflows/publish-python.yml'));
check('the connector tests are wired in', workflow.includes('tools/connectors/*.test.mjs') && existsSync('tools/connectors/confluence.test.mjs'));
check('the TypeScript client is wired in', workflow.includes('clients/typescript') && existsSync('clients/typescript/package.json') && existsSync('clients/typescript/package-lock.json'));
check('the MCP HTTP tests are wired in', workflow.includes('tools/mcp-server.test.mjs') && existsSync('tools/mcp-server.test.mjs'));
check('the deployment job runs the three-minute demo', workflow.includes('examples/three-minute-demo/demo.mjs') && existsSync('examples/three-minute-demo/demo.mjs'));
check('the deployment job exercises the document CLI and the incident rule pack',
  workflow.includes('tools/ingest-docs.mjs') && workflow.includes('--rule-pack incident') && existsSync('tools/ingest-docs.mjs'));
check('the deployment job asserts the 404/405 error contract, not just a 200',
  /%\{http_code\}/.test(workflow) && /404/.test(workflow) && /error/.test(workflow));

// 3. the copies of the version facts agree
const javaInPom = (/<java\.version>([^<]+)</.exec(pom) || [])[1];
const javaInCi = (/java-version: '([^']+)'/.exec(workflow) || [])[1];
check('the CI JDK matches the pom', javaInPom === javaInCi, `pom=${javaInPom} ci=${javaInCi}`);

const tagInCompose = (/image: aerospike\/aerospike-server:([^\s]+)/.exec(compose) || [])[1];
check('the deployment compose pins an Aerospike version (not `latest`)', !!tagInCompose && tagInCompose !== 'latest', tagInCompose);
const nightlyService = (/image: aerospike\/aerospike-server:([^\s]+)/.exec(workflow) || [])[1];
check('the CI service uses the same pinned store as the deployment', nightlyService === tagInCompose,
  `compose=${tagInCompose} ci=${nightlyService}`);

// 4. YAML shape: tabs are illegal, and every job must have at least one step
check('no tab characters (illegal in YAML)', !/\t/.test(workflow));
const steps = (workflow.match(/^\s{6}- /gm) || []).length;
check('the workflow has steps in every job', steps >= 8, `${steps} steps`);

// 5. the verification suite the nightly job runs must exist and be runnable
const checks = readdirSync('verification').filter((name) => /^verify-.*\.(mjs|cjs)$/.test(name));
check('verification/ holds the published checks', checks.length >= 8, `${checks.length} checks`);

console.log(failures.length
  ? `\n${failures.length} problem(s) in ${WORKFLOW}`
  : `\n${WORKFLOW} is coherent (jobs, commands, JDK ${javaInCi}, Aerospike ${tagInCompose}, ${checks.length} live checks)`);
process.exit(failures.length ? 1 : 0);
