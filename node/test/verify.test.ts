// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const PACKAGE_ROOT = resolve(fileURLToPath(new URL("../..", import.meta.url)));
const SCRIPT_PATH = join(PACKAGE_ROOT, "scripts", "verify.mjs");

const RELEASABLE = {
  name: "@aws/developer-toolkit-elasticache",
  version: "1.2.3",
  repository: {
    type: "git",
    url: "git+https://github.com/aws/developer-toolkit-elasticache.git",
  },
};
const CHANGELOG_WITH_ENTRY = "# Changelog\n\n## [1.2.3] - 2026-01-01\n\n- Something.\n";

function runVerify(
  args: string[],
  packageJson: Record<string, unknown>,
  changelog = CHANGELOG_WITH_ENTRY,
) {
  const temporaryRoot = mkdtempSync(join(tmpdir(), "elasticache-node-verify-"));
  const scriptsDirectory = join(temporaryRoot, "scripts");
  const temporaryScript = join(scriptsDirectory, "verify.mjs");

  try {
    mkdirSync(scriptsDirectory);
    copyFileSync(SCRIPT_PATH, temporaryScript);
    writeFileSync(join(temporaryRoot, "package.json"), JSON.stringify(packageJson));
    writeFileSync(join(temporaryRoot, "CHANGELOG.md"), changelog);
    return spawnSync(process.execPath, [temporaryScript, ...args], {
      encoding: "utf8",
      env: { ...process.env, GITHUB_REF_NAME: "" },
    });
  } finally {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
}

test("accepts a matching tag on a publishable package", () => {
  const result = runVerify(["node-v1.2.3"], RELEASABLE);

  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /Publishing version 1\.2\.3\./);
});

test("reads the tag from GITHUB_REF_NAME when no argument is given", () => {
  const temporaryRoot = mkdtempSync(join(tmpdir(), "elasticache-node-verify-"));
  try {
    mkdirSync(join(temporaryRoot, "scripts"));
    copyFileSync(SCRIPT_PATH, join(temporaryRoot, "scripts", "verify.mjs"));
    writeFileSync(join(temporaryRoot, "package.json"), JSON.stringify(RELEASABLE));
    writeFileSync(join(temporaryRoot, "CHANGELOG.md"), CHANGELOG_WITH_ENTRY);
    const result = spawnSync(
      process.execPath,
      [join(temporaryRoot, "scripts", "verify.mjs")],
      {
        encoding: "utf8",
        env: { ...process.env, GITHUB_REF_NAME: "node-v1.2.3" },
      },
    );
    assert.equal(result.status, 0, result.stdout + result.stderr);
  } finally {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
});

const rejectedCases: {
  name: string;
  args: string[];
  packageJson: Record<string, unknown>;
  changelog?: string;
  expectedError: RegExp;
}[] = [
  {
    name: "rejects a tag that does not match the version",
    args: ["node-v1.2.4"],
    packageJson: RELEASABLE,
    expectedError: /does not match the version in package\.json \(1\.2\.3\)/,
  },
  {
    name: "rejects a tag without the node-v prefix",
    args: ["v1.2.3"],
    packageJson: RELEASABLE,
    expectedError: /Expected node-v1\.2\.3/,
  },
  {
    name: "rejects a package still marked private",
    args: ["node-v1.2.3"],
    packageJson: { ...RELEASABLE, private: true },
    expectedError: /"private": true/,
  },
  {
    name: "rejects a missing repository url",
    args: ["node-v1.2.3"],
    packageJson: { name: RELEASABLE.name, version: RELEASABLE.version },
    expectedError: /repository\.url must point at/,
  },
  {
    name: "rejects a changelog without an entry for the version",
    args: ["node-v1.2.3"],
    packageJson: RELEASABLE,
    changelog: "# Changelog\n\n## [Unreleased]\n\n- Something.\n",
    expectedError: /CHANGELOG\.md has no "## \[1\.2\.3\]" entry/,
  },
  {
    name: "rejects an npm older than the required version",
    args: ["node-v1.2.3", "--require-npm", "999.0.0"],
    packageJson: RELEASABLE,
    expectedError: /older than 999\.0\.0/,
  },
];

for (const testCase of rejectedCases) {
  test(testCase.name, () => {
    const result = runVerify(testCase.args, testCase.packageJson, testCase.changelog);

    assert.notEqual(result.status, 0);
    assert.match(result.stdout, /^::error::/m);
    assert.match(result.stdout, testCase.expectedError);
  });
}

test("accepts an npm at or above the required version", () => {
  const result = runVerify(["node-v1.2.3", "--require-npm", "1.0.0"], RELEASABLE);

  assert.equal(result.status, 0, result.stdout + result.stderr);
});
