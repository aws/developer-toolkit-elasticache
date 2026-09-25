// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

// Release gate, run by node-release.yml before anything is built or published:
//
//   node scripts/verify.mjs [TAG] [--require-npm <version>]
//
// Checks that the release tag matches package.json's version, that the package is no
// longer marked private (the publish guard), that the version is not a prerelease, that
// CHANGELOG.md has an entry for the version, and that package.json declares the
// repository.
// With --require-npm, also checks the local npm is at least that version, which the
// publish job needs for trusted publishing. Prints a GitHub Actions error annotation
// and exits non-zero on the first failure.

import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { argv, env, exit, platform, stdout } from "node:process";
import { URL } from "node:url";

const TAG_PREFIX = "node-v";
const EXPECTED_REPOSITORY = "https://github.com/aws/developer-toolkit-elasticache";

function fail(message) {
  stdout.write(`::error::${message}\n`);
  exit(1);
}

function versionParts(value, what) {
  const parts = value.split(".").map(Number);
  if (parts.some((part) => !Number.isInteger(part) || part < 0)) {
    fail(`${what} is not a dotted numeric version: ${value}`);
  }
  return parts;
}

/** True when dotted version `a` is older than `b`. */
function olderThan(a, b) {
  const [left, right] = [
    versionParts(a, "the installed npm version"),
    versionParts(b, "--require-npm"),
  ];
  for (let i = 0; i < Math.max(left.length, right.length); i++) {
    const [l, r] = [left[i] ?? 0, right[i] ?? 0];
    if (l !== r) {
      return l < r;
    }
  }
  return false;
}

const args = argv.slice(2);
const flagIndex = args.indexOf("--require-npm");
const requiredNpm = flagIndex === -1 ? undefined : args[flagIndex + 1];
if (flagIndex !== -1 && (requiredNpm === undefined || requiredNpm.startsWith("-"))) {
  fail("--require-npm needs a version, for example --require-npm 11.5.1.");
}
const positional =
  flagIndex === -1 ? args : args.filter((_, i) => i !== flagIndex && i !== flagIndex + 1);
const tag = positional[0] ?? env.GITHUB_REF_NAME ?? "";

const packageJson = JSON.parse(
  readFileSync(new URL("../package.json", import.meta.url), "utf8"),
);
const version = packageJson.version;

if (tag !== `${TAG_PREFIX}${version}`) {
  fail(
    `Release tag does not match the version in package.json (${version}). ` +
      `Expected ${TAG_PREFIX}${version}. Bump the version and re-tag.`,
  );
}

if (packageJson.private === true) {
  fail(
    'package.json still has "private": true. Remove it in the release commit once ' +
      "the open-source review has cleared.",
  );
}

if (version.includes("-")) {
  fail(
    `Version ${version} is a prerelease. Only release versions (X.Y.Z) are published.`,
  );
}

const repositoryUrl =
  typeof packageJson.repository === "string"
    ? packageJson.repository
    : packageJson.repository?.url;
if (!repositoryUrl || !repositoryUrl.includes(EXPECTED_REPOSITORY)) {
  fail(
    `package.json repository.url must point at ${EXPECTED_REPOSITORY}; ` +
      "npm provenance verifies it against the publishing repository.",
  );
}

const changelog = readFileSync(new URL("../CHANGELOG.md", import.meta.url), "utf8");
if (!changelog.includes(`## [${version}]`)) {
  fail(`CHANGELOG.md has no "## [${version}]" entry. Add one in the release commit.`);
}

if (requiredNpm !== undefined) {
  // Windows: npm is a .cmd shim, which Node only spawns through a shell.
  const npm = platform === "win32" ? "npm.cmd" : "npm";
  const installed = execFileSync(npm, ["--version"], {
    encoding: "utf8",
    shell: platform === "win32",
  }).trim();
  if (olderThan(installed, requiredNpm)) {
    fail(
      `npm ${installed} is older than ${requiredNpm}, the minimum for trusted ` +
        "publishing. Use a Node.js release that bundles a newer npm.",
    );
  }
}

stdout.write(`Publishing version ${version}.\n`);
