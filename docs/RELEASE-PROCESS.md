# The release process

What the branches and tags mean, how a release is cut, and how the state of a release is *checked* rather than
assumed. `tools/check-release.mjs` asserts everything in §5 against the repository; `tools/release-1.0.sh` does
the cutting.

## 1. Branches

| Branch | Meaning | Who writes to it |
|---|---|---|
| `main` | **The released line.** What a stranger clones and what the tags point at. It moves only to a commit that has been released (a fast-forward from `release/*`, or a merge of one). | nobody directly |
| `release/1.0` | **The 1.0 maintenance line.** Patches for 1.0.x are committed here and released from here, so a fix does not have to wait for whatever is in flight on `develop`. Merged back into `develop` after each patch. | 1.0.x fixes |
| `develop` | **Integration.** All work lands here first; it is the branch the protection rules should allow pushes to. | everything |

`main` is not "the newest code": it is "the code of the last release". That is why the 1.0 release commit is on
all three branches and why `git checkout main` gives a build whose `pom.xml` says `1.0.1` (checked in §5).

## 2. Tags

- **One tag per release, annotated, named `v<version>`, pointing at a commit whose `pom.xml` says exactly that
  version.** `git tag -n` shows the message; `tools/release-1.0.sh --tag` creates it and pushes it.
- **A tag is never moved and never reused.** A mistake is fixed forward with a new patch version - which is what
  happened between `v1.0.0` and `v1.0.1`: the on-call drill found a wrong alert rule and a percentile that could
  not decay, and the fix went out as 1.0.1 instead of re-pointing 1.0.0. The release script refuses to run when
  the tag already exists for exactly this reason.
- **Tags are the release identity everywhere**: the image tag, the changelog section, the release note and the
  git tag all carry the same string (`docs/RELEASE-1.0.0.md`, `CHANGELOG.md`, `phoenixgraph-http:1.0.1`).
- The `0.1.0` and `0.2.0` changelog sections are **milestone names, not tagged releases**: the pom version came
  from Maven (`0.1.0-SNAPSHOT`) and the milestone numbering lived in the plan. They are kept for the record and
  are marked as such; from 1.0 on, the two agree and no untagged version is ever named in the changelog.

## 3. Cutting a release

```bash
# on the branch the release is cut from (release/1.0 for a patch, develop for a minor)
$EDITOR CHANGELOG.md            # move [Unreleased] into a new [x.y.z] section, leave [Unreleased] empty
$EDITOR pom.xml                 # <version>x.y.z</version> (all modules inherit it)
$EDITOR README.md docs/RELEASE-*.md   # if the user-visible surface changed
node tools/check-release.mjs    # fails until the repository agrees with itself
git commit -am "x.y.z: ..."
git checkout main && git merge --ff-only release/1.0 && git checkout develop
tools/release-1.0.sh --tag      # packages, checksums, builds and probes both images, tags, pushes
```

The script stops on a dirty working tree and on a tag that already exists, because those are the two ways a
release stops describing the code it claims to describe. It also probes the built image (`/api/v1/health` plus
`/api/v1/graphs`) so a release cannot be tagged with an image that does not start.

## 4. After the tag

| Step | How | Why it is on this list |
|---|---|---|
| Move `main` | `git push origin main` after the fast-forward | `main` must be the released line, not "whatever was newest when someone looked" |
| Merge back | `git merge release/1.0` into `develop` | a patch that only exists on the release line is lost at the next minor |
| Push the images | `docker push phoenixgraph-http:x.y.z` (+ `web`) | the tag names the artifact; the image is the artifact |
| Publish the note | `docs/RELEASE-x.y.z.md` as the GitHub release body, with `dist/phoenixgraph.jar` + `.sha256` attached | the release page is what people read |
| Set the default branch | GitHub → Settings → Branches → default `main` | needs the web UI or the API (this repository has no `gh` CLI in its toolchain) |
| Prune | delete `release/*` when the minor line closes | otherwise "which branch is 1.0?" has three answers |

## 5. What `tools/check-release.mjs` asserts

Run it before a release and in CI; it reads the repository, not a running service (so it costs a second).

1. every module pom carries the **same** version, and the root pom's version is the newest one
2. if `dist/RELEASE.txt` exists, it describes that same version and commit
3. there is a **git tag `v<version>`**, it is **annotated**, and the commit it points at is the current `HEAD`
   of the release line
4. `CHANGELOG.md` has a `## [x.y.z] - <date>` section for that version, with at least one `### ` subsection
5. the changelog's link definitions resolve: every `[x.y.z]` it references has a tag, and `[Unreleased]`
   compares the newest tag to `HEAD`
6. the `Dockerfile`'s `VERSION` label and the Kubernetes manifests' image tags carry that version
7. `README.md` names the same version where it states the current one, and `docs/RELEASE-x.y.z.md` exists
8. no `0.x` version is named as a release without being marked as an untagged milestone (§2)

`--json` prints the same findings as data, for a pipeline that wants to publish them.

## 6. The state at 1.0

| | |
|---|---|
| released | `v1.0.0` (b1312e3), `v1.0.1` (3bd637c) - both annotated, both pushed |
| `main` / `release/1.0` / `develop` | all three at `3bd637c` (= `v1.0.1`) at the time of writing |
| open defect carried into 1.0.x | the latency percentiles do not decay (`docs/DATA-QUALITY.md` §12) - fix it on `release/1.0`, release 1.0.2, merge back |
