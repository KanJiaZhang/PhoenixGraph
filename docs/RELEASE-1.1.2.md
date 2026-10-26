# PhoenixGraph 1.1.2 — release notes

**Tag:** `v1.1.2` · **Image:** `phoenixgraph-http:1.1.2` / `phoenixgraph-web:1.1.2` · **Jar:** `dist/phoenixgraph.jar` (+ `.sha256`)
**From:** `v1.1.1` · **Branch:** `release/1.1` (a patch release; see [`RELEASE-PROCESS.md`](RELEASE-PROCESS.md))

A documentation release. No API change and no change to an answer: the artifact is 1.1.1 with the generated
documents agreeing with it.

## Why this exists

`v1.1.1` went out with `docs/openapi.json` still saying `info.version: 1.1.0`. The spec is generated from the pom,
so the version bump made it stale, and nothing in the release ritual looked at the generated documents -
`check-release.mjs` verifies what the repository says about itself, not what a generator would write. CI caught it
one commit after the tag.

Moving a tag is not an option here (`RELEASE-PROCESS.md` §2: a tag is never moved, a mistake is fixed forward), so
the fix is this patch, plus the guard that makes the git-tag-and-image-tag ritual fail before it builds anything
when a generated document is stale.

## Changed

- **`docs/openapi.json` regenerated** (`info.version: 1.1.2`, 60 paths), and `docs/API.md` re-checked against the
  controllers (60 endpoints, 15 controllers).
- **`tools/release-1.0.sh` runs `--check` on both generators and refuses to continue** with the command to run,
  and `docs/RELEASE-PROCESS.md` §3 lists the regeneration between the pom bump and `check-release.mjs`.
- **`--check` compares text, not bytes.** The guard above immediately failed on this Windows machine, which was
  cutting the release: `core.autocrlf` gives the working copy CRLF while the generator writes LF, so the same file
  with the same content answered "stale" here and "current" in CI. Both generators normalise line endings before
  comparing, because a line ending is not a stale document.

## Verifying this release

```bash
node tools/openapi.mjs --check         # the committed spec is what the controllers describe
node tools/api-endpoints.mjs --check   # the API table agrees with the controllers
node tools/check-release.mjs           # the repository agrees with itself about 1.1.2
node verification/run.mjs              # the live checks, if a service is running
```

The product itself is unchanged from 1.1.1: [`RELEASE-1.1.1.md`](RELEASE-1.1.1.md) says what that patch fixed
(the pipeline that proves 1.1.0), and [`RELEASE-1.1.0.md`](RELEASE-1.1.0.md) says what 1.1 contains.
