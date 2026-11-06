# CI/CD

What runs on every push, what a tag publishes, and how a release reaches a cluster. Everything here is a file in
`.github/` and a command in `tools/` — nothing lives only in the GitHub UI except the repository settings and the
optional secrets, and `node tools/check-ci.mjs` asserts the workflows still agree with the repository.

## 1. The pipeline at a glance

| Trigger | Workflow | What it proves |
|---|---|---|
| every push / PR to `develop`, `main` | `ci.yml` → **unit** | the 20 modules that need no service, the generated docs (`docs/API.md`, `docs/openapi.json`), the connector and MCP tests, the pi extension and the data-agent package, the release state, the workflow and the k8s manifests |
| | `ci.yml` → **python-sdk** | the Python client's tests, and that the sdist and wheel assemble |
| | `ci.yml` → **typescript-sdk** | the TypeScript client builds and its tests pass |
| | `ci.yml` → **deployment** | the container path a stranger follows: `docker compose build/up`, the API and the UI answer, an unknown path is a 404 with an error body, the three-minute demo and the document CLI |
| | `ci.yml` → **integration** | a real Aerospike + a real engine: the context-graph case (it seeds its own graph) and the input/limits contract |
| nightly `03:00` and manual | `ci.yml` → **nightly** | the whole live verification suite (`verification/run.mjs --retry 0`) against a seeded stack, including the browser checks |
| a `v*` tag | `release.yml` | the release state, the jars, the generated references, the package tests — then a GitHub Release with the two fat jars attached and the notes generated from `CHANGELOG.md` |
| | `publish-images.yml` | `ghcr.io/<owner>/phoenixgraph-http:<version>` and `-web:<version>` (plus `:latest`), with buildx cache |
| | `publish-python.yml` | the client wheel is always built; it is published when `PYPI_API_TOKEN` or `PYPI_TRUSTED_PUBLISHING=true` |
| | `publish-npm.yml` | the `pi-data-agent` tarball is always packed and checked (does it carry `src/`, `skills/`, `prompts/`, …?); it is published when `NPM_TOKEN` or `NPM_TRUSTED_PUBLISHING=true` |
| manual | `deploy.yml` | renders `deploy/k8s` with the tag being deployed, diffs it, and applies it only with a kubeconfig and `DEPLOY_ENABLED=true` |
| weekly | `dependabot.yml` | grouped updates for Maven, npm (client, pi package), pip and the actions |

Nothing in `ci.yml` needs a secret: the repository is public, the store is the pinned `aerospike/aerospike-server`
image and the browsers come from Playwright (nightly).

## 2. Cutting a release

```bash
# 1. the version is in one place, and the changelog is the note
#    pom.xml <version>, CHANGELOG.md [x.y.z], docs/RELEASE-x.y.z.md, deploy/k8s tags (check-release asserts the copies)
node tools/check-release.mjs --expect-untagged     # before tagging: everything that does not need the tag
git commit -am "x.y.z: ..."
git tag -a vx.y.z -m "x.y.z: ..." && git push origin vx.y.z
```

The tag starts four workflows at once: `release` (verify + GitHub Release), `publish-images` (GHCR), `publish-python`
(wheel → PyPI when configured) and `publish-npm` (pi package). They are independent so a missing PyPI token does not
fail the Release page — each says in its summary what it did and what it skipped.

The release notes are generated: `node tools/release-notes.mjs x.y.z` prints the `CHANGELOG.md` section for that
version with the real image coordinates, and `release.yml` uses exactly that as the Release body. A version missing
from the changelog is an error, because a release whose notes are empty is a release nobody can read later.

`docs/RELEASE-PROCESS.md` keeps the policy (what a tag means, `main` vs `develop`, never reusing a tag); this page
keeps the mechanics.

## 3. Deploying what a tag published

```bash
gh workflow run deploy -f environment=staging -f tag=1.2.0            # render + diff (dry run by default)
gh workflow run deploy -f environment=production -f tag=1.2.0 -f dry_run=false
```

- The **render** job writes the tag into `deploy/k8s/kustomization.yaml`, runs `kubectl kustomize` and asserts both
  images carry that tag, then uploads `rendered.yaml` — the artifact a reviewer diffs.
- The **deploy** job needs `environment:` (GitHub can require reviewers for `production`), the secret `KUBE_CONFIG`
  and the variable `DEPLOY_ENABLED=true`. Without them it renders, validates and says so instead of failing.
- It runs `kubectl diff` before `apply`, waits for both rollouts, and prints the rollback command
  (`kubectl rollout undo deploy/graph-http`).

Local equivalent, for a laptop cluster:

```bash
kubectl apply -k deploy/k8s
kubectl -n default rollout status deploy/graph-http
```

## 4. Secrets and variables

| Name | Kind | Used by | Needed? |
|---|---|---|---|
| `GITHUB_TOKEN` | automatic | release (Release page), publish-images (GHCR) | always, no setup |
| `PYPI_API_TOKEN` | secret | publish-python | only to publish to PyPI |
| `PYPI_TRUSTED_PUBLISHING` | variable (`true`) | publish-python | alternative to the token (OIDC) |
| `NPM_TOKEN` | secret | publish-npm | only to publish the pi package |
| `NPM_TRUSTED_PUBLISHING` | variable (`true`) | publish-npm | alternative to the token |
| `KUBE_CONFIG` | secret | deploy | only to apply to a cluster |
| `DEPLOY_ENABLED` | variable (`true`) | deploy | second lock next to the environment gate |

## 5. Keeping the pipeline honest

`node tools/check-ci.mjs` reads every workflow and asserts the facts that would otherwise drift silently: the jobs
and their runners, the JDK matching `pom.xml`, the Aerospike tag matching `docker-compose.yml`, the generated-docs
checks being wired in, the kustomization tags matching the manifests and the pom, the release chain (tag trigger,
verify before publish, notes from the changelog, jars attached), the deploy guards, the npm package checks, and no tab
characters anywhere. It runs in CI on every push, so a workflow that stops matching the repository fails the build
that changed it.
