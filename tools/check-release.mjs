#!/usr/bin/env node
// Checks that this repository is in a state that can be called a release, and says what is wrong when it is not.
//
//   node tools/check-release.mjs            # human-readable, exit 1 on a problem
//   node tools/check-release.mjs --json     # the same findings as data
//
// Why this exists: `docs/RELEASE-PROCESS.md` describes a release as a set of facts that have to agree - the
// version in every pom, an annotated tag that points at the commit, a changelog section, the image tag, the
// release note. Each of those is one command away from being wrong, and the wrong version of a release is the
// one nobody notices until an operator deploys the artifact and reads a version they did not expect. So the
// agreement is a script, not a convention.
//
// It reads the working tree and git, not a running service, so it is safe to run anywhere and costs a second.
// `tools/release-1.0.sh` runs it before tagging (with --tag, against the commit that is about to be tagged).
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const JSON_OUT = process.argv.includes('--json');
// the release script runs this *before* it creates the tag, so the tag facts cannot hold yet: in that mode they
// are reported as pending, and every other fact (versions, changelog, images, note) is still enforced
const EXPECT_UNTAGGED = process.argv.includes('--expect-untagged');
const findings = [];
const ok = (name, detail) => findings.push({ level: 'ok', name, detail });
const bad = (name, detail) => findings.push({ level: 'fail', name, detail });
const warn = (name, detail) => findings.push({ level: 'warn', name, detail });

const git = (...args) => execFileSync('git', args, { encoding: 'utf8' }).trim();
const versionOf = (pom) => {
  const text = readFileSync(pom, 'utf8');
  // the first <version> of a module pom is the parent reference; the root pom's first one is its own version
  const match = /<version>([^<]+)<\/version>/.exec(text);
  return match ? match[1] : null;
};

// ---- 1. every pom carries the same version, and the root pom is the newest ---------------------------------
const modules = readdirSync('.').filter((name) => existsSync(join(name, 'pom.xml')));
const rootVersion = versionOf('pom.xml');
const mismatched = modules.map((m) => [m, versionOf(join(m, 'pom.xml'))])
  .filter(([, version]) => version !== rootVersion);
if (mismatched.length === 0) {
  ok('every module inherits the release version', `${rootVersion} across ${modules.length + 1} poms`);
} else {
  bad('a module pom disagrees about the version',
    mismatched.map(([m, v]) => `${m}=${v}`).slice(0, 5).join(' '));
}

// ---- 2. the staged release manifest, when there is one ------------------------------------------------------
if (existsSync('dist/RELEASE.txt')) {
  const manifest = readFileSync('dist/RELEASE.txt', 'utf8');
  const declared = /^version:\s*(.+)$/m.exec(manifest)?.[1]?.trim();
  const commit = /^commit:\s*(.+)$/m.exec(manifest)?.[1]?.trim();
  if (declared === rootVersion) {
    ok('dist/RELEASE.txt describes this version', `${declared} at ${commit?.slice(0, 8)}`);
  } else {
    bad('dist/RELEASE.txt is from another version', `manifest=${declared} pom=${rootVersion}`);
  }
  if (commit && commit !== git('rev-parse', 'HEAD')) {
    warn('dist/RELEASE.txt was built from another commit',
      `manifest=${commit.slice(0, 8)} HEAD=${git('rev-parse', '--short', 'HEAD')} (rebuild before publishing)`);
  }
} else {
  warn('no dist/RELEASE.txt', 'run tools/release-1.0.sh to build the artifact and the manifest');
}

// ---- 3. the tag: exists, annotated, and points at the release commit ----------------------------------------
const tag = `v${rootVersion}`;
const tagExists = (() => {
  try {
    git('rev-parse', '-q', '--verify', `refs/tags/${tag}`);
    return true;
  } catch {
    return false;
  }
})();
if (!tagExists) {
  (EXPECT_UNTAGGED ? warn : bad)(`no tag ${tag}`,
    EXPECT_UNTAGGED ? 'pending: this run is expected to create it'
      : 'docs/RELEASE-PROCESS.md §3: tools/release-1.0.sh --tag creates it');
} else {
  const type = git('cat-file', '-t', tag);
  const commit = git('rev-parse', `${tag}^{commit}`);
  ok(`tag ${tag} exists`, `${type}, commit ${commit.slice(0, 8)}`);
  if (type !== 'tag') {
    bad(`${tag} is a lightweight tag`, 'an annotated tag carries the release message: git tag -a');
  }
  // is the tagged commit on the released line?
  const released = ['main', 'release/1.0'].find((branch) => {
    try {
      execFileSync('git', ['merge-base', '--is-ancestor', commit, branch], { stdio: 'ignore' });
      return true;
    } catch {
      return false;
    }
  });
  if (released) {
    ok(`${tag} is on the released line`, `reachable from ${released}`);
  } else {
    bad(`${tag} is not reachable from main or release/1.0`, 'tag the commit that is actually released');
  }
  // the pom at the tag must say the version the tag names - the mismatch that makes `git checkout v1.0.1` lie
  let taggedVersion = null;
  try {
    taggedVersion = /<version>([^<]+)<\/version>/.exec(git('show', `${tag}:pom.xml`))?.[1] ?? null;
  } catch {
    // the tag may not contain a pom (not this repository's shape, but do not crash on it)
  }
  if (taggedVersion === rootVersion) {
    ok('the pom at the tag says the tag\u2019s version', `${tag} -> ${taggedVersion}`);
  } else {
    bad('the pom at the tag disagrees with the tag', `${tag} -> ${taggedVersion}, pom.xml -> ${rootVersion}`);
  }
}

// ---- 4. the changelog section -------------------------------------------------------------------------------
const changelog = readFileSync('CHANGELOG.md', 'utf8');
const sectionStart = changelog.indexOf(`## [${rootVersion}]`);
if (sectionStart < 0) {
  bad(`CHANGELOG.md has no [${rootVersion}] section`, 'Keep a Changelog: one section per released version');
} else {
  const section = changelog.slice(sectionStart, changelog.indexOf('\n## ', sectionStart + 1) + 1 || undefined);
  const date = /^## \[[^\]]+\] - (\d{4}-\d{2}-\d{2})/m.exec(section)?.[1];
  const subsections = (section.match(/^### /gm) || []).length;
  if (date) {
    ok(`CHANGELOG.md has a dated [${rootVersion}] section`, `${date}, ${subsections} subsection(s)`);
  } else {
    bad(`the [${rootVersion}] section has no date`, 'the format is "## [1.0.1] - 2026-10-14"');
  }
  if (subsections === 0) {
    bad(`the [${rootVersion}] section says nothing`, 'expected at least one ### Added/Changed/Fixed');
  }
}

// ---- 5. the changelog's links resolve to tags ---------------------------------------------------------------
const tags = new Set(git('tag', '--list').split('\n').filter(Boolean));
const referenced = [...changelog.matchAll(/^\[(\d+\.\d+\.\d+)\]:/gm)].map((match) => match[1]);
const missing = referenced.filter((version) => !tags.has(`v${version}`));
if (missing.length === 0) {
  ok('every version the changelog links to has a tag', referenced.map((v) => `v${v}`).join(' '));
} else {
  warn('the changelog links to versions that were never tagged',
    `${missing.map((v) => `v${v}`).join(' ')} - mark them as untagged milestones (docs/RELEASE-PROCESS.md §2)`);
}
if (changelog.includes(`[Unreleased]: https://github.com/KanJiaZhang/PhoenixGraph/compare/v${rootVersion}...HEAD`)) {
  ok('[Unreleased] compares the newest tag to HEAD', `v${rootVersion}...HEAD`);
} else {
  warn('[Unreleased] does not compare the newest tag to HEAD',
    `expected .../compare/v${rootVersion}...HEAD`);
}

// ---- 6. the artifact tags follow the version ----------------------------------------------------------------
const dockerfile = readFileSync('Dockerfile', 'utf8');
const label = /ARG VERSION=([0-9.]+)/.exec(dockerfile)?.[1];
if (label === rootVersion) {
  ok('the image label carries the release version', `ARG VERSION=${label}`);
} else {
  bad('the Dockerfile label disagrees with the pom', `Dockerfile=${label} pom=${rootVersion}`);
}
const manifests = ['deploy/k8s/graph-http.yaml', 'deploy/k8s/graph-web.yaml']
  .flatMap((file) => [...readFileSync(file, 'utf8').matchAll(/image: (phoenixgraph-\w+):([0-9.]+)/g)]
    .map((match) => [file, match[1], match[2]]));
const wrongImages = manifests.filter(([, , version]) => version !== rootVersion);
if (wrongImages.length === 0) {
  ok('the Kubernetes manifests pin this version', manifests.map(([, name, v]) => `${name}:${v}`).join(' '));
} else {
  bad('a Kubernetes manifest pins another version',
    wrongImages.map(([file, name, v]) => `${file} ${name}:${v}`).join(' '));
}

// ---- 7. the release note exists -----------------------------------------------------------------------------
const note = `docs/RELEASE-${rootVersion}.md`;
if (existsSync(note)) {
  ok('the release note exists', note);
} else {
  warn(`no ${note}`, 'publish it as the GitHub release body (docs/RELEASE-PROCESS.md §4)');
}

// ---- 8. no untagged version is presented as a release -------------------------------------------------------
const untaggedHeads = [...changelog.matchAll(/^## \[(\d+\.\d+\.\d+)\]/gm)]
  .map((match) => match[1])
  .filter((version) => version !== rootVersion && !tags.has(`v${version}`));
const untaggedMarked = untaggedHeads.every((version) => {
  const at = changelog.indexOf(`## [${version}]`);
  return /milestone|not tagged|untagged/i.test(changelog.slice(at, at + 400));
});
if (untaggedHeads.length === 0 || untaggedMarked) {
  ok('every pre-1.0 changelog section is marked as an untagged milestone or tagged',
    untaggedHeads.length === 0 ? 'none' : untaggedHeads.join(', '));
} else {
  warn('a changelog section names a version that was never tagged and does not say so',
    `${untaggedHeads.join(', ')} - see docs/RELEASE-PROCESS.md §2`);
}

// ---- the verdict ---------------------------------------------------------------------------------------------
const failures = findings.filter((finding) => finding.level === 'fail').length;
if (JSON_OUT) {
  console.log(JSON.stringify({ version: rootVersion, head: git('rev-parse', 'HEAD'), findings,
    failures }, null, 2));
} else {
  for (const finding of findings) {
    console.log(`${finding.level === 'ok' ? 'ok  ' : finding.level === 'warn' ? 'warn' : 'FAIL'} `
      + `${finding.name}${finding.detail ? ' - ' + finding.detail : ''}`);
  }
  console.log('');
  console.log(failures === 0
    ? `the repository agrees with itself about release ${rootVersion}`
    : `${failures} problem(s) to fix before this can be called a release`);
}
process.exit(failures === 0 ? 0 : 1);
